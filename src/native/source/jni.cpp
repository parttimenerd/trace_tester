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

std::mutex _asgst_sep_m;
std::condition_variable _asgst_sep_cv;

struct ThreadState {
  pthread_t thread;
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

std::atomic<bool> shouldStop;

static void loop();

std::thread samplerThread;

void printAGInfo();

void printAGInfoIfNeeded();

void onAbort() {
  shouldStop = true;
  _asgst_sep_cv.notify_all();
  if (samplerThread.joinable()) {
    samplerThread.join();
  }
}

void OnThreadStart(jvmtiEnv *jvmti_env, JNIEnv *jni_env, jthread thread) {
  threadIdMap.addThread(get_thread_id(),
                        obtainJavaThreadIdViaJava(jni_env, thread));

  jvmti_env->SetThreadLocalStorage(
      thread, new ThreadState({(pthread_t)get_thread_id()}));
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
  primeClasses();
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
  (JNIEnv *env, jclass, jlong threadId, jint depth) {
    // TODO: don't ignore thread
  jthread thread;
  jvmti->GetCurrentThread(&thread);
  jvmtiFrameInfo gstFrames[MAX_DEPTH];
  jint gstCount = 0;
  jvmtiError err = jvmti->GetStackTrace(thread, 0, depth, gstFrames, &gstCount);
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stderr, "Error: GetStackTrace failed with error %d\n", err);
    return nullptr;
  }
  int app = countFirstTracerFrames(gstFrames, gstCount);
  return createTrace(env, (jvmtiFrameInfo*)gstFrames + app, gstCount - app);
}

/*
 * Class:     tester_Tracer
 * Method:    runASGCT
 * Signature: (JI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGCT
  (JNIEnv *env, jclass, jlong threadId, jint depth) {
// TODO: don't ignore thread
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
  return createTrace(env, &trace);
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
  return createTrace(env, &trace);
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

enum class WalkMode { sameThread, separateThread };

struct WalkSettings {
  WalkMode mode;
  jint depth;
  jint options;
  pthread_t thread;
};

std::atomic<WalkSettings*> _walkSettings;
std::atomic<void*> _ucontext;
ASGST_CallFrame _frames[MAX_DEPTH];
ASGST_CallTrace _trace;
std::atomic<bool> _finished;

// see https://mostlynerdless.de/blog/2023/04/21/couldnt-we-just-use-asyncgetcalltrace-in-a-separate-thread/ for more explanations
static void signalHandler(int signum, siginfo_t *info, void *ucontext) {
  void* expected = nullptr;
  if (!_ucontext.compare_exchange_strong(expected, ucontext) || _walkSettings == nullptr) {
      // another signal handler invocation is already in progress
      return;
  }
  WalkSettings settings = *_walkSettings.load();
  if (settings.mode == WalkMode::sameThread) {
    AsyncGetStackTrace(&_trace, settings.depth, (ucontext_t*)ucontext, settings.options);
    _ucontext = nullptr;
    _finished = true;
  } else {
    // wait for the stack to be walked, and block the thread from executing
    // we do not timeout here, as this leads to difficult bugs
    waitWhile([&](){ return _ucontext != nullptr;});
  }
}

void loop() {
  JNIEnv *env;
  jvm->AttachCurrentThreadAsDaemon((void**)&env, nullptr);
  while (!shouldStop) {
    std::unique_lock<std::mutex> lk(_asgst_sep_m);
    _asgst_sep_cv.wait(lk);
    if (shouldStop) {
      return;
    }
    WalkSettings settings = *_walkSettings.load();
    sendSignal(settings.thread);
    // wait for the stack to be walked, and block the thread from executing
    // we do not timeout here, as this leads to difficult bugs
    waitWhile([&](){ return _ucontext == nullptr;});
    AsyncGetStackTrace(&_trace, settings.depth, (void*)_ucontext.load(), ASGST_INCLUDE_C_FRAMES);
    _ucontext = nullptr;
    _finished = true;
    lk.unlock();
    _asgst_sep_cv.notify_all();
  }
  jvm->DetachCurrentThread();
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
    waitWhile([&](){ return _finished == false;});
  } else if (settings.mode == WalkMode::separateThread) {
    _asgst_sep_cv.notify_one();
    std::unique_lock<std::mutex> lk{_asgst_sep_m};
    _asgst_sep_cv.wait(lk, [&](){ return _finished == true;});
  } else {
    throw std::runtime_error("unknown walk mode");
  }
  return &_trace;
}

/*
 * Class:     tester_Tracer
 * Method:    runASGSTInSignalHandler
 * Signature: (IJI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGSTInSignalHandler
  (JNIEnv *env, jclass, jint options, jlong, jint depth) {
  ASGST_CallTrace* trace = runASGST({WalkMode::sameThread, depth, options, pthread_self()});
  int app = countFirstTracerFrames(trace);
  trace->num_frames -= app;
  trace->frames += app;
  return createTrace(env, trace);
}

/*
 * Class:     tester_Tracer
 * Method:    runASGSTInSeparateThread
 * Signature: (IJI)Ltester/Trace;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_runASGSTInSeparateThread
  (JNIEnv *env, jclass, jint options, jlong, jint depth) {
  ASGST_CallTrace* trace = runASGST({WalkMode::separateThread, depth, options, pthread_self()});
  int app = countFirstTracerFrames(trace);
  trace->num_frames -= app;
  trace->frames += app;
  auto t = createTrace(env, trace);
  return t;
}

static jclass arrayListClass = nullptr;
static jmethodID arrayListConstructor = nullptr;
static jmethodID arrayListAdd = nullptr;
static jclass javaThreadIdClass = nullptr;
static jmethodID javaThreadIdConstructor = nullptr;
static jclass longClass = nullptr;
static jmethodID longConstructor = nullptr;


/*
 * Class:     tester_Tracer
 * Method:    getJavaThreadIds
 * Signature: ()Ljava/util/List;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_getJavaThreadIds
  (JNIEnv *env, jclass) {
  jclass listClass = findClass(env, arrayListClass, "java/util/ArrayList");
  jmethodID listConstructor = findMethod(env, arrayListConstructor, listClass, "<init>", "()V");
  jmethodID listAdd = findMethod(env, arrayListAdd, listClass, "add", "(Ljava/lang/Object;)Z");
  jclass javaThreadIdClass = findClass(env, javaThreadIdClass, "tester/Tracer$JavaThreadId");
  jmethodID javaThreadIdConstructor = findMethod(env, javaThreadIdConstructor, javaThreadIdClass, "<init>", "(J)V");
  jobject list = env->NewObject(listClass, listConstructor);
  auto ids = threadIdMap.getAllJavaThreadIds();
  for (auto id : ids) {
    jobject javaThreadId = env->NewObject(javaThreadIdClass, javaThreadIdConstructor, id);
    env->CallBooleanMethod(list, listAdd, javaThreadId);
  }
  return list;
}

/*
 * Class:     tester_Tracer
 * Method:    getOSThreadIds
 * Signature: ()Ljava/util/List;
 */
JNIEXPORT jobject JNICALL Java_tester_Tracer_getOSThreadIds
  (JNIEnv *env, jclass) {
  jclass listClass = findClass(env, arrayListClass, "java/util/ArrayList");
  jmethodID listConstructor = findMethod(env, arrayListConstructor, listClass, "<init>", "()V");
  jmethodID listAdd = findMethod(env, arrayListAdd, listClass, "add", "(Ljava/lang/Object;)Z");
  jobject list = env->NewObject(listClass, listConstructor);
  jclass longClass = findClass(env, longClass, "java/lang/Long");
  jmethodID longConstructor = findMethod(env, longConstructor, longClass, "<init>", "(J)V");
  auto ids = obtainThreads();
  for (auto id : ids) {
    env->CallBooleanMethod(list, listAdd, env->NewObject(longClass, longConstructor, id));
  }
  return list;
}

/*
 * Class:     tester_Tracer
 * Method:    getOSThreadId
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_tester_Tracer_getOSThreadId
  (JNIEnv *env, jclass, jlong javaThreadId) {
  return (long)threadIdMap.getThread(javaThreadId);
}

/*
 * Class:     tester_Tracer
 * Method:    getCurrentOSThreadId
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_tester_Tracer_getCurrentOSThreadId
  (JNIEnv *env, jclass) {
  return (long)pthread_self();
}