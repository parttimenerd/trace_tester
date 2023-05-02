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

#pragma once

#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE 600
#endif

#include <array>
#include <assert.h>
#include <chrono>
#include <signal.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/ucontext.h>
#include <ucontext.h>
#include <utility>
#include "jni.h"
#include "jvmti.h"
#include "profile.h"
#include <mutex>
#include <dlfcn.h>

#if defined(__APPLE__) && defined(__MACH__)
#include <mach/mach.h>
#include <mach/task.h>
#include <mach/mach_vm.h>
#endif

#ifdef DEBUG
// more space for debug info
const int METHOD_HEADER_SIZE = 0x200;
const int METHOD_PRE_HEADER_SIZE = 0x20;
#else
const int METHOD_HEADER_SIZE = 0x100;
const int METHOD_PRE_HEADER_SIZE = 0x10;
#endif

static jvmtiEnv* jvmti;

typedef void (*SigAction)(int, siginfo_t*, void*);
typedef void (*SigHandler)(int);
typedef void (*TimerCallback)(void*);


template <class T>
class JvmtiDeallocator {
 public:
  JvmtiDeallocator() {
    elem_ = nullptr;
  }

  ~JvmtiDeallocator() {
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(elem_));
  }

  T* get_addr() {
    return &elem_;
  }

  T get() {
    return elem_;
  }

 private:
  T elem_;
};

void ensureSuccess(jvmtiError err, const char *msg) {
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stderr, "Error in %s: %d", msg, err);
    exit(1);
  }
}

static void GetJMethodIDs(jclass klass) {
  jint method_count = 0;
  JvmtiDeallocator<jmethodID*> methods;
  jvmtiError err = jvmti->GetClassMethods(klass, &method_count, methods.get_addr());

  // If ever the GetClassMethods fails, just ignore it, it was worth a try.
  if (err != JVMTI_ERROR_NONE && err != JVMTI_ERROR_CLASS_NOT_PREPARED) {
    fprintf(stderr, "GetJMethodIDs: Error in GetClassMethods: %d\n", err);
  }
}

// assumes that getcontext is called in the beginning of the function
template <typename T> bool doesFrameBelongToMethod(ASGST_CallFrame frame, T* method, const char* msg_prefix) {
  if (frame.type != ASGST_FRAME_CPP) {
    fprintf(stderr, "%s: Expected CPP frame, got %d\n", msg_prefix, frame.type);
    return false;
  }
  ASGST_NonJavaFrame non_java_frame = frame.non_java_frame;
  size_t pc = (size_t)non_java_frame.pc;
  size_t expected_pc_start = (size_t)method - METHOD_PRE_HEADER_SIZE;
  size_t expected_pc_end = (size_t)method + METHOD_HEADER_SIZE;
  if (pc < expected_pc_start || pc > expected_pc_end) {
    fprintf(stderr, "%s: Expected PC in range [%p, %p], got %p\n", msg_prefix,
      (void*)expected_pc_start, (void*)expected_pc_end, (void*)pc);
    return false;
  }
  return true;
}

bool doesFrameBelongToJavaMethod(ASGST_CallFrame frame, uint8_t type, const char* expected_name, const char* msg_prefix) {
  if (frame.type != type) {
    fprintf(stderr, "%s: Expected type %d but got %d\n", msg_prefix, type, frame.type);
    return false;
  }
  ASGST_JavaFrame java_frame = frame.java_frame;
  JvmtiDeallocator<char*> name;
  jvmtiError err = jvmti->GetMethodName(java_frame.method_id, name.get_addr(), nullptr, nullptr);
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stderr, "%s: Error in GetMethodName: %d\n", msg_prefix, err);
    return false;
  }
  if (strcmp(expected_name, name.get()) != 0) {
    fprintf(stderr, "%s: Expected method name %s but got %s\n", msg_prefix, expected_name, name.get());
    return false;
  }
  return true;
}

bool isStubFrame(ASGST_CallFrame frame, const char* msg_prefix) {
  if (frame.type != ASGST_FRAME_STUB) {
    fprintf(stderr, "%s: Expected STUB frame, got %d", msg_prefix, frame.type);
    return false;
  }
  return true;
}

bool isCppFrame(ASGST_CallFrame frame, const char* msg_prefix) {
  if (frame.type != ASGST_FRAME_CPP) {
    fprintf(stderr, "%s: Expected CPP frame, got %d", msg_prefix, frame.type);
    return false;
  }
  return true;
}

void printMethod(FILE* stream, jmethodID method) {
  JvmtiDeallocator<char*> name;
  JvmtiDeallocator<char*> signature;
  jvmtiError err = jvmti->GetMethodName(method, name.get_addr(), signature.get_addr(), nullptr);
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stream, "Error in GetMethodName: %d", err);
    return;
  }
  jclass klass;
  JvmtiDeallocator<char*> className;
  jvmti->GetMethodDeclaringClass(method, &klass);
  jvmti->GetClassSignature(klass, className.get_addr(), nullptr);
  fprintf(stream, "%s.%s%s", className.get(), name.get(), signature.get());
}

void printJavaFrame(FILE *stream, ASGST_JavaFrame frame) {
  switch (frame.type) {
    case ASGST_FRAME_JAVA:
    fprintf(stream, "Java");
    break;
    case ASGST_FRAME_JAVA_INLINED:
    fprintf(stream, "Java inlined");
    break;
    case ASGST_FRAME_NATIVE:
    fprintf(stream, "Native");
    break;
  }
  if (frame.type != ASGST_FRAME_NATIVE) {
    if (frame.comp_level == 0) {
      fprintf(stderr, " interpreted");
    } else {
      fprintf(stderr, " compiled");
    }
  }
  fprintf(stream, " frame, method = ");
  printMethod(stream, frame.method_id);
  fprintf(stream, ", bci = %d", frame.bci);
}

template <size_t N = 0> const char* lookForMethod(void* pc, std::array<std::pair<const char*, void*>, N> methods = {}) {
  std::array<std::pair<const char*, size_t>, N> distances;
  int i = 0;
  for (auto method : methods) {
    if (pc >= method.second && pc < (void*)((size_t)method.second + METHOD_HEADER_SIZE)) {
      distances[i] = std::make_pair(method.first, (size_t)pc - (size_t)method.second);
    }
    i++;
  }
  if (distances.empty()) {
    return nullptr;
  }
  auto min_pair = distances[0];
  for (auto pair : distances) {
    if (pair.second < min_pair.second) {
      min_pair = pair;
    }
  }
  return min_pair.first;
}

template <size_t N = 0> void printNonJavaFrame(FILE* stream, ASGST_NonJavaFrame frame, std::array<std::pair<const char*, void*>, N> methods = {}) {
  if (frame.type == ASGST_FRAME_CPP) {
    fprintf(stream, "CPP frame, pc = %p", frame.pc);
  } else if (frame.type == ASGST_FRAME_STUB) {
    fprintf(stream, "Stub frame, pc = %p", frame.pc);
  } else {
    fprintf(stream, "Unknown frame type: %d", frame.type);
  }

  const char* method_name = lookForMethod(frame.pc, methods);
  if (method_name != nullptr) {
    fprintf(stream, " (%s)", method_name);
  } else {
    fprintf(stream, " (%p)", frame.pc);
    #ifdef _GNU_SOURCE
    Dl_info info;
    if (dladdr(frame.pc, &info) != 0 && info.dli_sname != nullptr) {
      fprintf(stream, " (%s)", info.dli_sname);
    }
    #endif
  }
}

template <size_t N = 0> void printFrame(FILE* stream, ASGST_CallFrame frame, std::array<std::pair<const char*, void*>, N> methods = {}) {
  switch (frame.type) {
    case ASGST_FRAME_JAVA:
    case ASGST_FRAME_JAVA_INLINED:
    case ASGST_FRAME_NATIVE:
      printJavaFrame(stream, frame.java_frame);
      break;
    case ASGST_FRAME_CPP:
    case ASGST_FRAME_STUB:
      printNonJavaFrame(stream, frame.non_java_frame, methods);
      break;
    default:
        fprintf(stream, "Unknown frame type: %d", frame.type);
  }
}

template <size_t N = 0> void printFrames(FILE* stream, ASGST_CallFrame *frames, int length, std::array<std::pair<const char*, void*>, N> methods = {}) {
  for (int i = 0; i < length; i++) {
    fprintf(stream, "Frame %d: ", i);
    printFrame(stream, frames[i], methods);
    fprintf(stream, "\n");
  }
}

template <size_t N = 0> void printTrace(FILE* stream, ASGST_CallTrace trace, std::array<std::pair<const char*, void*>, N> methods = {}) {
  fprintf(stream, "Trace length: %d\n", trace.num_frames);
  fprintf(stream, "Kind: %d\n", trace.kind);
  if (trace.num_frames > 0) {
    printFrames(stream, trace.frames, trace.num_frames, methods);
  }
}

bool areFramesCPPFrames(ASGST_CallFrame *frames, int start, int inclEnd, const char* msg_prefix) {
  for (int i = start; i <= inclEnd; i++) {
    if (frames[i].type != ASGST_FRAME_CPP) {
      fprintf(stderr, "%s: Expected CPP frame at index %d, got %d", msg_prefix, i, frames[i].type);
      return false;
    }
  }
  return true;
}

long getSecondsSinceEpoch(){
    return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

typedef struct {
    jint lineno;                      // line number in the source file
    jmethodID method_id;              // method executed in this frame
} ASGCT_CallFrame;

typedef struct {
    JNIEnv *env_id;                   // Env where trace was recorded
    jint num_frames;                  // number of frames in this trace
    ASGCT_CallFrame *frames;          // frames
} ASGCT_CallTrace;

typedef void (*ASGCTType)(ASGCT_CallTrace *, jint, void *);
typedef void (*ASGSTType)(ASGST_CallTrace *, jint, void *, uint32_t);

static ASGCTType asgct = nullptr;
static ASGSTType asgst = nullptr;


bool isASGCTNativeFrame(ASGCT_CallFrame frame) {
  return frame.lineno == -3;
}

void printASGCTFrame(FILE* stream, ASGCT_CallFrame frame) {
  JvmtiDeallocator<char*> name;
  jvmtiError err = jvmti->GetMethodName(frame.method_id, name.get_addr(), nullptr, nullptr);
  if (err != JVMTI_ERROR_NONE) {
    fprintf(stream, "=== asgst sampler failed: Error in GetMethodName: %d", err);
    return;
  }
  if (isASGCTNativeFrame(frame)) {
    fprintf(stream, "Native frame ");
    printMethod(stream, frame.method_id);
  } else {
    fprintf(stream, "Java frame   ");
    printMethod(stream, frame.method_id);
    fprintf(stream, ": %d", frame.lineno);
  }
}

void printASGCTFrames(FILE* stream, ASGCT_CallFrame *frames, int length) {
  for (int i = 0; i < length; i++) {
    fprintf(stream, "Frame %d: ", i);
    printASGCTFrame(stream, frames[i]);
    fprintf(stream, "\n");
  }
}

void printASGCTTrace(FILE* stream, ASGCT_CallTrace trace) {
  fprintf(stream, "Trace length: %d\n", trace.num_frames);
  if (trace.num_frames > 0) {
    printASGCTFrames(stream, trace.frames, trace.num_frames);
  }
}

void printTraces(ASGST_CallTrace* trace, ASGCT_CallTrace* asgct_trace) {
  fprintf(stderr, "=== asgst trace ===\n");
  printTrace(stderr, *trace);
  fprintf(stderr, "=== asgct trace ===\n");
  printASGCTTrace(stderr, *asgct_trace);
}

void initASGCT() {
  void *mptr = dlsym(RTLD_DEFAULT, "AsyncGetCallTrace");
  if (mptr == nullptr) {
    fprintf(stderr, "Error: could not find AsyncGetCallTrace!\n");
    exit(0);
  }
  asgct = reinterpret_cast<ASGCTType>(mptr);
 /* *mptr = dlsym(RTLD_DEFAULT, "AsyncGetStackTrace");
  if (mptr == nullptr) {
    fprintf(stderr, "Error: could not find AsyncGetStackTrace!\n");
    exit(0);
  }
  asgst = reinterpret_cast<ASGSTType>(mptr);*/
}

/** obtain all os threads */
std::vector<long> obtainThreads() {
  std::vector<long> result;
#if defined(__APPLE__) && defined(__MACH__)
  // TODO: check if this is correct
  mach_msg_type_number_t count = 0;
  thread_array_t thread_list;
  kern_return_t kr = task_threads(mach_task_self(), &thread_list, &count);
  if (kr != KERN_SUCCESS) {
    fprintf(stderr, "Error in obtaining threads\n");
    exit(1);
  }

  for (int i = 0; i < count; i++) {
    auto t = thread_list[i];
    result.push_back((long)t);
  }

  vm_deallocate(mach_task_self(), (vm_address_t)thread_list,
                count * sizeof(thread_act_t));
#else
  // source https://stackoverflow.com/a/29546902/19040822
  DIR *proc_dir;
  {
    char dirname[100];
    snprintf(dirname, sizeof dirname, "/proc/%d/task", getpid());
    proc_dir = opendir(dirname);
  }

  if (proc_dir) {
    /* /proc available, iterate through tasks... */
    struct dirent *entry;
    while ((entry = readdir(proc_dir)) != nullptr) {
      if (entry->d_name[0] == '.')
        continue;

      result.push_back((pthread_t)atoi(entry->d_name));
    }

    closedir(proc_dir);
  } else {
    fprintf(stderr, "Error in obtaining threads\n");
    exit(1);
  }
#endif
  return result;
}

typedef void (*SigAction)(int, siginfo_t *, void *);
typedef void (*SigHandler)(int);
typedef void (*TimerCallback)(void *);

static SigAction installSignalHandler(int signo, SigAction action,
                                      SigHandler handler = nullptr) {
  struct sigaction sa;
  struct sigaction oldsa;
  sigemptyset(&sa.sa_mask);

  if (handler != nullptr) {
    sa.sa_handler = handler;
    sa.sa_flags = 0;
  } else {
    sa.sa_sigaction = action;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
  }

  sigaction(signo, &sa, &oldsa);
  return oldsa.sa_sigaction;
}

long get_thread_id() {
#if defined(__APPLE__) && defined(__MACH__)
  mach_port_t port = mach_thread_self();
  mach_port_deallocate(mach_task_self(), port);
  return (long)port;
#else
  return (pthread_t)syscall(SYS_gettid);
#endif
}

typedef jlong javaThreadId_t;

class ThreadIdMap {

  std::recursive_mutex
    threadToJavaIdMutex; // hold this mutex while working with threadToJavaId
  
  std::unordered_map<long, javaThreadId_t> threadToJavaId;
  std::unordered_map<javaThreadId_t, long> javaIdToThread;

public:
  javaThreadId_t getJavaThreadId(long thread) {
    std::lock_guard<std::recursive_mutex> lock(threadToJavaIdMutex);
    auto it = threadToJavaId.find(thread);
    if (it == threadToJavaId.end()) {
      return -1;
    }
    return it->second;
  }

  long getThread(javaThreadId_t id) {
    std::lock_guard<std::recursive_mutex> lock(threadToJavaIdMutex);
    auto it = javaIdToThread.find(id);
    if (it == javaIdToThread.end()) {
      return 0;
    }
    return it->second;
  }

  void addThread(long thread, javaThreadId_t id) {
    std::lock_guard<std::recursive_mutex> lock(threadToJavaIdMutex);
    threadToJavaId[thread] = id;
    javaIdToThread[id] = thread;
  }

  void removeThread(long thread) {
    std::lock_guard<std::recursive_mutex> lock(threadToJavaIdMutex);
    auto it = threadToJavaId.find(thread);
    if (it == threadToJavaId.end()) {
      return;
    }
    javaIdToThread.erase(it->second);
    threadToJavaId.erase(it);
  }

  std::vector<javaThreadId_t> getAllJavaThreadIds() {
    std::lock_guard<std::recursive_mutex> lock(threadToJavaIdMutex);
    std::vector<javaThreadId_t> result;
    for (auto it = javaIdToThread.begin(); it != javaIdToThread.end(); it++) {
      result.push_back(it->first);
    }
    return result;
  }

};

jclass findClass(JNIEnv *env, jclass &cache, const char *name) {
  if (cache == nullptr) {
    jclass clazz = env->FindClass(name);
    if (clazz == nullptr) {
      fprintf(stderr, "Error: could not find class %s\n", name);
      exit(1);
    }
    cache = (jclass) env->NewGlobalRef(clazz);
  }
  return cache;
}

jmethodID findMethod(JNIEnv *env, jmethodID &cache, jclass clazz, const char *name, const char *signature) {
    fprintf(stderr, "find method %s\n", name);

  if (cache == nullptr) {
    fprintf(stderr, "Error: cache method %s\n", name);
    cache = env->GetMethodID(clazz, name, signature);
    if (cache == nullptr) {
      fprintf(stderr, "Error: could not find method %s\n", name);
      exit(1);
    }
  }
  return cache;
}
