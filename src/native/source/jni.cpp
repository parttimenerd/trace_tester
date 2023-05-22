/*
 * Copyright (c) 2023, SAP SE. All rights reserved.
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "jni.h"
#include "jvmti.h"
#include "tester_Tracer.h"
#include "helper.hpp"
#include "trace.hpp"
#include <algorithm>
#include <assert.h>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <iostream>
#include <iterator>
#include <mutex>
#include <optional>
#include <profile.h>
#include <pthread.h>
#include <random>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <sys/types.h>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <condition_variable>

#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE 600
#endif
#include <array>
#include <atomic>
#include <ucontext.h>

#if defined(__linux__)
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include <sys/resource.h>

/** maximum size of stack trace arrays */
const int MAX_DEPTH = 1024;

static JavaVM *jvm;

static ThreadIdMap threadIdMap;

struct ThreadState {
  pthread_t thread;
  JNIEnv *env;
};

jlong obtainJavaThreadIdViaJava(JNIEnv *env, jthread thread) {
  if (env == nullptr) {
    return -1;
  }
  jclass threadClass = env->FindClass("java/lang/Thread");
  jmethodID getId = env->GetMethodID(threadClass, "getId", "()J");
  jlong id = env->CallLongMethod(thread, getId);
  return id;
}

/** returns the jthread for a given Java thread id or null */
jthread getJThreadForPThread(JNIEnv *env, pthread_t threadId) {
  std::vector<jthread> threadVec;
  JvmtiDeallocator<jthread *> threads;
  jint thread_count = 0;
  jvmti->GetAllThreads(&thread_count, threads.get_addr());
  for (int i = 0; i < thread_count; i++) {
    jthread thread = threads.get()[i];
    ThreadState *state;
    jvmti->GetThreadLocalStorage(thread, (void **)&state);
    if (state == nullptr) {
      continue;
    }
    if (state->thread == threadId) {
      return thread;
    }
  }
  return nullptr;
}

bool hasThreadState(jthread thread) {
  ThreadState *state;
  jvmti->GetThreadLocalStorage(thread, (void **)&state);
  return state != nullptr;
}

/**
 * @brief Obtains the pthread_t for a given jthread and returns the current if this fails or the given thread is null.
 *
 * @param thread optional thread
 */
ThreadState getStateForJThread(JNIEnv* env, jthread thread) {
  if (thread == nullptr) {
    return {pthread_self(), env};
  }
  ThreadState *state;
  jvmti->GetThreadLocalStorage(thread, (void **)&state);
  if (state == nullptr) {
    jvmtiThreadInfo info;
    jvmti->GetThreadInfo(thread, &info);
    fprintf(stderr, "Thread %s has no state\n", info.name);
    return {pthread_self(), env};
  }
  return *state;
}

std::atomic<bool> shouldStop;

static void loop();

std::thread samplerThread;

void onAbort() {
  shouldStop = true;
  if (samplerThread.joinable()) {
    samplerThread.join();
  }
}

bool primedClasses = false;

void primeClasses();

void registerThread(JNIEnv *jni_env, jthread thread) {
  if (!primedClasses) {
    primeClasses();
  }
  threadIdMap.addThread(get_thread_id(),
                        obtainJavaThreadIdViaJava(jni_env, thread));

  jvmti->SetThreadLocalStorage(
      thread, new ThreadState({pthread_self(), jni_env}));
}

void OnThreadStart(jvmtiEnv *jvmti_env, JNIEnv *jni_env, jthread thread) {
  registerThread(jni_env, thread);
}

void OnThreadEnd(jvmtiEnv *jvmti_env, JNIEnv *jni_env, jthread thread) {
  threadIdMap.removeThread(get_thread_id());
}

// AsyncGetCallTrace needs class loading events to be turned on!
static void JNICALL OnClassLoad(jvmtiEnv *jvmti, JNIEnv *jni_env,
                                jthread thread, jclass klass) {}

static void JNICALL OnClassPrepare(jvmtiEnv *jvmti, JNIEnv *jni_env,
                                   jthread thread, jclass klass) {
  // We need to do this to "prime the pump" and get jmethodIDs primed.
  GetJMethodIDs(klass);
}

static void startSamplerThread();

void primeClasses() {
  jint class_count = 0;

  // Get any previously loaded classes that won't have gone through the
  // OnClassPrepare callback to prime the jmethods for AsyncGetCallTrace.
  // else the jmethods are all nullptr. This might still happen if ASGCT is
  // called at the very beginning, while this code is executed. But this is not
  // a problem in the typical use case.
  JvmtiDeallocator<jclass *> classes;
  jvmtiError err = jvmti->GetLoadedClasses(&class_count, classes.get_addr());
  if (err != JVMTI_ERROR_NONE) {
    return;
  }

  // Prime any class already loaded and try to get the jmethodIDs set up.
  jclass *classList = classes.get();
  for (int i = 0; i < class_count; ++i) {
    GetJMethodIDs(classList[i]);
  }

}

static void JNICALL OnVMInit(jvmtiEnv *_jvmti, JNIEnv *jni_env, jthread thread) {
}

static void signalHandler(int signum, siginfo_t *info, void *ucontext);

static void startSamplerThread() {
  samplerThread = std::thread(loop);
  installSignalHandler(SIGPROF, signalHandler);
}


static void JNICALL OnVMDeath(jvmtiEnv *jvmti_env, JNIEnv *jni_env) {
  onAbort();
}

extern "C" {

static jint Agent_Initialize(JavaVM *_jvm, char *options, void *reserved) {
  jvm = _jvm;
  jint res = jvm->GetEnv((void **)&jvmti, JVMTI_VERSION);
  if (res != JNI_OK || jvmti == nullptr) {
    fprintf(stderr, "Error: wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  jvmtiError err;
  jvmtiCapabilities caps;
  memset(&caps, 0, sizeof(caps));
  caps.can_get_line_numbers = 1;
  caps.can_get_source_file_name = 1;

  ensureSuccess(jvmti->AddCapabilities(&caps), "AddCapabilities");

  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassLoad = &OnClassLoad;
  callbacks.VMInit = &OnVMInit;
  callbacks.ClassPrepare = &OnClassPrepare;
  callbacks.VMDeath = &OnVMDeath;
  callbacks.ThreadStart = &OnThreadStart;
  callbacks.ThreadEnd = &OnThreadEnd;
  ensureSuccess(
      jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks)),
      "SetEventCallbacks");
  ensureSuccess(jvmti->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_CLASS_LOAD, nullptr),
                "class load");
  ensureSuccess(jvmti->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr),
                "class prepare");
  ensureSuccess(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                                JVMTI_EVENT_VM_INIT, nullptr),
                "vm init");
  ensureSuccess(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                                JVMTI_EVENT_VM_DEATH, nullptr),
                "vm death");
  ensureSuccess(jvmti->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, nullptr),
                "thread start");
  ensureSuccess(jvmti->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, nullptr),
                "thread end");
  initASGCT();
  startSamplerThread();
  return JNI_OK;
}

JNIEXPORT
jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT
jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
}


/** returns true if successful */
bool sendSignal(pthread_t thread) {
#if defined(__APPLE__) && defined(__MACH__)
  return pthread_kill(thread, SIGPROF) == 0;
#else
  union sigval sigval;
  return sigqueue(thread, SIGPROF, sigval) == 0;
#endif
}

bool sendSignal(JNIEnv *env, jthread thread) {
  return sendSignal(getStateForJThread(env, thread).thread);
}

/** idle wait till the atomic variable is as expected or the timeout is reached,
 * returns the value of the atomic variable */
bool waitOnAtomic(std::atomic<bool> &atomic, bool expected = true,
                  int timeout = 100) {
  auto start = std::chrono::system_clock::now();
  while (atomic.load() != expected && std::chrono::system_clock::now() - start <
                                          std::chrono::milliseconds(timeout)) {
  }
  return atomic;
}

bool checkJThread(jthread javaThread) {
  jint state;
  jvmti->GetThreadState(javaThread, &state);

  if (!((state & JVMTI_THREAD_STATE_ALIVE) == 1 &&
        (state | JVMTI_THREAD_STATE_RUNNABLE) == state) &&
      (state & JVMTI_THREAD_STATE_IN_NATIVE) == 0) {
    return false;
  }
  return true;
}

/*
 * Class:     tester_Tracer
 * Method:    runGST
 * Signature: (JI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runGST
  (JNIEnv *env, jclass, jobject thread, jint depth) {
  if (thread == nullptr) {
    jvmti->GetCurrentThread(&thread);
  }
  jvmtiFrameInfo gstFrames[MAX_DEPTH];
  jint gstCount = 0;
  jvmtiError err = jvmti->GetStackTrace(thread, 0, depth, gstFrames, &gstCount);
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stderr, "Error: GetStackTrace failed with error %d\n", err);
    return nullptr;
  }
  int app = countFirstTracerFrames(gstFrames, gstCount);
  return createTraceWithoutTracerFrames(env, (jvmtiFrameInfo*)gstFrames + app, gstCount - app);
}

/*
 * Class:     tester_Tracer
 * Method:    runASGCT
 * Signature: (I)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGCT
  (JNIEnv *env, jclass, jint depth) {
  ASGCT_CallFrame frames[MAX_DEPTH];
  ASGCT_CallTrace trace;
  trace.frames = frames;
  trace.env_id = env;
  trace.num_frames = depth;
  ucontext_t context;
  getcontext(&context);
  asgct(&trace, depth, &context);
  int app = countFirstTracerFrames(&trace);
  trace.num_frames -= app;
  trace.frames += app;
  return createTraceWithoutTracerFrames(env, &trace);
}

/*
 * Class:     tester_Tracer
 * Method:    runASGST
 * Signature: (II)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGST
  (JNIEnv *env, jclass, jint options, jint depth) {
  ASGST_CallFrame frames[MAX_DEPTH];
  ASGST_CallTrace trace;
  trace.frames = frames;
  ucontext_t context;
  getcontext(&context);
  AsyncGetStackTrace(&trace, depth, &context, options);
  int app = countFirstTracerFrames(&trace);
  trace.num_frames -= app;
  trace.frames += app;
  return createTraceWithoutTracerFrames(env, &trace);
}

long nanotime() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000 + ts.tv_nsec;
}

/** waits as long as condition holds with a timeout, returns false if timeout is hit*/
bool waitWhile(std::function<bool()> condition, long timeout_ns = -1) {
    long start = nanotime();
    while (condition()) {
        long diff = nanotime() - start;
        if (timeout_ns != -1 && diff > timeout_ns) {
            return false;
        }
    }
    return true;
}

enum class WalkMode { sameThread, separateThread, asgctSameThread, multiple, multipleSig, multipleSep };

/** options for the more complex multiple mode */
struct MultipleOptions {
  jboolean asgctSig;
  std::vector<jint> asgstSepThreadOptions;
  std::vector<jint> asgstSigOptions;

  bool hasASGSTSepThread() const { return asgstSepThreadOptions.size() > 0; }
  bool hasASGSTSig() const { return asgstSigOptions.size() > 0; }

  bool needsSeparateThread() const {
    return hasASGSTSepThread();
  }

  bool needsSignalHandler() const {
    return hasASGSTSig() || asgctSig;
  }
};

struct MultipleTraces {
  MultipleOptions *options;
  ASGCT_CallFrame asgctSigFrames[MAX_DEPTH];
  ASGCT_CallTrace asgctSigTrace;
  std::vector<std::array<ASGST_CallFrame, MAX_DEPTH>> asgstSepThreadFramess;
  std::vector<ASGST_CallTrace> asgstSepThreadTraces;
  std::vector<std::array<ASGST_CallFrame, MAX_DEPTH>> asgstSigFramess;
  std::vector<ASGST_CallTrace> asgstSigTraces;

  void init(JNIEnv *threadEnv, MultipleOptions *options) {
    this->options = options;
    asgctSigTrace.frames = asgctSigFrames;
    asgctSigTrace.num_frames = 0;
    asgctSigTrace.env_id = threadEnv;
    allocate(asgstSepThreadFramess, asgstSepThreadTraces, options->asgstSepThreadOptions.size());
    allocate(asgstSigFramess, asgstSigTraces, options->asgstSigOptions.size());
  }

private:
  void allocate(std::vector<std::array<ASGST_CallFrame, MAX_DEPTH>> &framess, std::vector<ASGST_CallTrace> &traces, size_t count) {
    framess.resize(count);
    traces.resize(count);
    for (size_t i = 0; i < count; i++) {
      traces[i].frames = framess[i].data();
      traces[i].num_frames = 0;
    }
  }

  void addToArray(JNIEnv *env, jobjectArray array, jint offset, std::vector<ASGST_CallTrace> &traces) {
    for (size_t i = 0; i < traces.size(); i++) {
      env->SetObjectArrayElement(array, offset + i, createTraceWithoutTracerFrames(env, &traces[i]));
    }
  }

public:

  jobjectArray toTraceArray(JNIEnv *env) {
    jclass traceClass = env->FindClass("tester/Trace");
    jobjectArray array = env->NewObjectArray(1 + asgstSepThreadTraces.size() + asgstSigTraces.size(), traceClass, nullptr);
    env->SetObjectArrayElement(array, 0, options->asgctSig ? createTraceWithoutTracerFrames(env, &asgctSigTrace) : nullptr);
    addToArray(env, array, 1, asgstSepThreadTraces);
    addToArray(env, array, 1 + asgstSepThreadTraces.size(), asgstSigTraces);
    return array;
  }

};

struct WalkSettings {
  WalkMode mode;
  // only valid in multiple or multipleSig mode, or null
  MultipleOptions *multipleOptions;
  jint depth;
  // only valid in non-multiple mode
  jint options;
  pthread_t thread;
};

jthread loopThread;
std::atomic<WalkSettings*> _walkSettings;
std::atomic<bool> triggerLoopIteration = {false};
std::atomic<void*> _ucontext;
ASGST_CallFrame _frames[MAX_DEPTH];
ASGST_CallTrace _trace;
ASGCT_CallFrame _asgct_frames[MAX_DEPTH];
ASGCT_CallTrace _asgct_trace;
std::atomic<bool> _finished;

// for multiple mode
MultipleTraces _multipleTraces;

// deals with ASGCT and ASGST in signal handler
static void signalHandlerPartOfMultipleTraces(void *ucontext, jint depth, MultipleOptions *options) {
  if (options->asgctSig) {
    asgct(&_multipleTraces.asgctSigTrace, depth, ucontext);
  }
  for (size_t i = 0; i < options->asgstSigOptions.size(); i++) {
    AsyncGetStackTrace(&_multipleTraces.asgstSigTraces[i], depth, ucontext, options->asgstSigOptions[i]);
  }
}

// see https://mostlynerdless.de/blog/2023/04/21/couldnt-we-just-use-asyncgetcalltrace-in-a-separate-thread/ for more explanations
static void signalHandler(int signum, siginfo_t *info, void *ucontext) {
  WalkSettings settings = *_walkSettings.load();
  switch (settings.mode) {
    case WalkMode::sameThread:
      AsyncGetStackTrace(&_trace, settings.depth, ucontext, settings.options);
      _ucontext = nullptr;
      _finished = true;
      break;
    case WalkMode::multipleSig:
      signalHandlerPartOfMultipleTraces(ucontext, settings.depth, settings.multipleOptions);
      _ucontext = nullptr;
      _finished = true;
      break;
    case WalkMode::multiple:
    case WalkMode::multipleSep:
    case WalkMode::separateThread:
      {
        void* expected = nullptr;
        if (settings.mode == WalkMode::multiple) {
          // we also need to run ASGST in the signal handler
          signalHandlerPartOfMultipleTraces(ucontext, settings.depth, settings.multipleOptions);
        }
        if (!_ucontext.compare_exchange_strong(expected, ucontext) || _walkSettings == nullptr) {
            // another signal handler invocation is already in progress
            return;
        }
        // wait for the stack to be walked, and block the thread from executing
        // we do not timeout here, as this leads to difficult bugs
        waitWhile([&](){ return _ucontext != nullptr;});
        break;
      }
    case WalkMode::asgctSameThread:
      asgct(&_asgct_trace, settings.depth, (ucontext_t*)ucontext);
      _ucontext = nullptr;
      _finished = true;
      break;
  }
}

static void loopPartOfMultipleTraces(void *ucontext, jint depth, MultipleOptions *options) {
  for (size_t i = 0; i < options->asgstSepThreadOptions.size(); i++) {
    AsyncGetStackTrace(&_multipleTraces.asgstSepThreadTraces[i], depth, ucontext, options->asgstSepThreadOptions[i]);
  }
}


void loop() {
  JNIEnv *env;
  jvm->AttachCurrentThreadAsDaemon((void**)&env, nullptr);
  jvmti->GetCurrentThread(&loopThread);
  registerThread(env, loopThread);
  while (!shouldStop) {

    while (!triggerLoopIteration) {
      if (shouldStop) {
        triggerLoopIteration = false;
        return;
      }
    }
    triggerLoopIteration = false;
    WalkSettings settings = *_walkSettings.load();

    if (sendSignal(settings.thread)) {
      // wait for the stack to be walked, and block the thread from executing
      // we do not timeout here, as this leads to difficult bugs
      waitWhile([&](){ return _ucontext == nullptr;});

      switch (settings.mode) {
        case WalkMode::multiple:
        case WalkMode::multipleSep:
          loopPartOfMultipleTraces((void*)_ucontext.load(), settings.depth, settings.multipleOptions);
          break;
        case WalkMode::separateThread:
          AsyncGetStackTrace(&_trace, settings.depth, (void*)_ucontext.load(), settings.options);
          break;
        default:
          break;
      }
    }
    _ucontext = nullptr;
    _walkSettings = nullptr;
    _finished = true;
  }
  jvm->DetachCurrentThread();
}

ASGCT_CallTrace* runASGCTInSignalHandler(JNIEnv *env, JNIEnv* threadEnv, pthread_t thread, jint depth) {
  _asgct_trace.frames = _asgct_frames;
  _asgct_trace.env_id = threadEnv;
  WalkSettings settings{WalkMode::asgctSameThread, nullptr, depth, 0, thread};
  _walkSettings = &settings;
  _finished = false;
  _ucontext = nullptr;
  if (!sendSignal(settings.thread)) {
    fprintf(stderr, "failed to send signal to thread\n");
    return nullptr;
  }
  waitWhile([&](){ return _finished == false;});
  return &_asgct_trace;
}

ASGST_CallTrace* runASGST(WalkSettings settings) {
  _trace.frames = _frames;
  _walkSettings = &settings;
  _finished = false;
  _ucontext = nullptr;
  if (settings.mode == WalkMode::sameThread) {
    if (!sendSignal(settings.thread)) {
      return nullptr;
    }
  } else if (settings.mode == WalkMode::separateThread) {
    triggerLoopIteration = true;
  } else {
    throw std::runtime_error("unknown walk mode");
  }
  waitWhile([&](){ return _finished == false;});
  return &_trace;
}

/*
 * Class:     tester_Tracer
 * Method:    runASGSTInSignalHandler
 * Signature: (IJI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGSTInSignalHandler
  (JNIEnv *env, jclass, jint options, jobject thread, jint depth) {
  ASGST_CallTrace* trace = runASGST({WalkMode::sameThread, nullptr, depth, options, getStateForJThread(env, thread).thread});
  return createTraceWithoutTracerFrames(env, trace);
}

/*
 * Class:     tester_Tracer
 * Method:    runASGSTInSeparateThread
 * Signature: (IJI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGSTInSeparateThread
  (JNIEnv *env, jclass, jint options, jobject thread, jint depth) {
  ASGST_CallTrace* trace = runASGST({WalkMode::separateThread, nullptr, depth, options, getStateForJThread(env, thread).thread});
  int app = countFirstTracerFrames(trace);
  trace->num_frames -= app;
  trace->frames += app;
  auto t = createTraceWithoutTracerFrames(env, trace);
  return t;
}

/*
 * Class:     tester_Tracer
 * Method:    runASGCTInSignalHandler
 * Signature: (Ljava/lang/Thread;I)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGCTInSignalHandler
  (JNIEnv *env, jclass, jobject thread, jint depth) {
  ThreadState state = getStateForJThread(env, thread);
  ASGCT_CallTrace* trace = runASGCTInSignalHandler(env, state.env, state.thread, depth);
  if (trace == nullptr) {
    return nullptr;
  }
  int app = countFirstTracerFrames(trace);
  trace->num_frames -= app;
  trace->frames += app;
  auto t = createTraceWithoutTracerFrames(env, trace);
  return t;
}

JNIEXPORT jobjectArray JNICALL Java_tester_Tracer_runMultiple
  (JNIEnv *env, jclass, jobject thread, jint depth, jboolean asgctSig,
   jintArray asgstSepThreadOptions, jintArray asgstSigOptions) {
  ThreadState state = getStateForJThread(env, thread);
  MultipleOptions opts{asgctSig,
      intArrayToVector(env, asgstSepThreadOptions), intArrayToVector(env, asgstSigOptions)};
  WalkSettings settings{WalkMode::multiple,
    &opts, depth, 0, state.thread};
  _multipleTraces.init(state.env, settings.multipleOptions);
  if (!settings.multipleOptions->needsSeparateThread() && settings.multipleOptions->needsSignalHandler()) {
    settings.mode = WalkMode::multipleSig;
    _finished = false;
    _ucontext = nullptr;
    _walkSettings = &settings;
    if (!sendSignal(settings.thread)) {
      return nullptr;
    }
  } else if (settings.multipleOptions->needsSeparateThread()) {
    if (!settings.multipleOptions->needsSignalHandler()) {
      settings.mode = WalkMode::multipleSep;
    } else {
      settings.mode = WalkMode::multiple;
    }
    _walkSettings = &settings;
    triggerLoopIteration = true;
    _finished = false;
    _ucontext = nullptr;
  }
  waitWhile([&](){ return _finished == false;});
  return _multipleTraces.toTraceArray(env);
}

jclass threadClass;

/*
 * Class:     tester_Tracer
 * Method:    getThreads
 * Signature: ()[Ljava/lang/Thread;
 */
JNIEXPORT jobjectArray JNICALL Java_tester_Tracer_getThreads
  (JNIEnv *env, jclass) {
  // obtain all Java threads
  jthread* threads;
  jint threads_count;
  jvmti->GetAllThreads(&threads_count, &threads);

  std::vector<jthread> threads_vec;
  for (int i = 0; i < threads_count; i++) {
    jthread thread = threads[i];
    // is java thread?
    jint state;
    jvmti->GetThreadState(thread, &state);
    if ((state & JVMTI_THREAD_STATE_ALIVE) == 0) {
      // skip dead threads
      continue;
    }
    if (loopThread == thread) {
      // skip loop thread
      continue;
    }
    if (hasThreadState(thread)) {
      threads_vec.push_back(thread);
    }
  }

  // store the threads in an array
  jclass thread = findClass(env, threadClass, "java/lang/Thread");
  jobjectArray result = env->NewObjectArray(threads_vec.size(), thread, nullptr);
  for (int i = 0; i < threads_vec.size(); i++) {
    env->SetObjectArrayElement(result, i, threads_vec.at(i));
  }
  return result;
}