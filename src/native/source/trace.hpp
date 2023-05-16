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

#include "helper.hpp"
#include "jni.h"
#include "jvmti.h"
#include <profile.h>
#include <unordered_map>
#include <iostream>

// helps to create Java Trace objects

static jclass methodIdClass = nullptr;
static jmethodID methodIdClassConstructor = nullptr;
static jclass frameBaseClass = nullptr;
static jclass javaFrameClass = nullptr;
static jmethodID createASGCTJavaFrameMethod = nullptr;
static jmethodID createASGCTNativeFrameMethod = nullptr;
static jmethodID createGSTNativeFrameMethod = nullptr;
static jmethodID createGSTJavaFrameMethod = nullptr;
static jclass javaTraceClass = nullptr;
static jmethodID javaTraceClassConstructor = nullptr;
static jmethodID javaTraceClassErrorConstructor = nullptr;
static jmethodID javaFrameClassConstructor = nullptr;
static jclass nonJavaFrameClass = nullptr;
static jmethodID nonJavaFrameClassConstructor = nullptr;

// create a Java MethodId object
jobject createMethodId(JNIEnv *env, jmethodID methodId) {
  jclass clazz = findClass(env, methodIdClass, "tester/Frame$MethodId");

  jmethodID constructor = findMethod(env, methodIdClassConstructor, clazz, "<init>", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  JvmtiDeallocator<char*> name;
  JvmtiDeallocator<char*> signature;
  JvmtiDeallocator<char*> className;
  ensureSuccess(jvmti->GetMethodName(methodId, name.get_addr(), signature.get_addr(), nullptr), "method name");
  jclass declaringClass;
  ensureSuccess(jvmti->GetMethodDeclaringClass(methodId, &declaringClass), "declaring class");
  ensureSuccess(jvmti->GetClassSignature(declaringClass, className.get_addr(), nullptr), "class signature");

  // create the method object
  return env->NewObject(clazz, constructor, (jlong) methodId, env->NewStringUTF(className.get()),  env->NewStringUTF(name.get()), env->NewStringUTF(signature.get()));
}

jobject createASGCTJavaFrame(JNIEnv *env, jmethodID method_id, int bci) {
  jclass clazz = findClass(env, javaFrameClass, "tester/Frame$JavaFrame");
  jmethodID constructor = findMethod(env, createASGCTJavaFrameMethod, clazz, "createASGCTJavaFrame", "(Ltester/Frame$MethodId;I)Ltester/Frame$JavaFrame;", true);
  jobject methodId = createMethodId(env, method_id);
  return env->CallStaticObjectMethod(clazz, constructor, methodId, bci);
}

jobject createASGCTNativeFrame(JNIEnv *env, jmethodID method_id) {
  jclass clazz = findClass(env, javaFrameClass, "tester/Frame$JavaFrame");
  jmethodID constructor = findMethod(env, createASGCTNativeFrameMethod, clazz, "createASGCTNativeFrame", "(Ltester/Frame$MethodId;)Ltester/Frame$JavaFrame;", true);
  jobject methodId = createMethodId(env, method_id);
  return env->CallStaticObjectMethod(clazz, constructor, methodId);
}

jobject createGSTJavaFrame(JNIEnv *env, jmethodID method_id, int bci) {
  jclass clazz = findClass(env, javaFrameClass, "tester/Frame$JavaFrame");
  jmethodID constructor = findMethod(env, createGSTJavaFrameMethod, clazz, "createGSTJavaFrame", "(Ltester/Frame$MethodId;I)Ltester/Frame$JavaFrame;", true);
  jobject methodId = createMethodId(env, method_id);
  return env->CallStaticObjectMethod(clazz, constructor, methodId, bci);
}

jobject createGSTNativeFrame(JNIEnv *env, jmethodID method_id) {
  jclass clazz = findClass(env, javaFrameClass, "tester/Frame$JavaFrame");
  jmethodID constructor = findMethod(env, createGSTNativeFrameMethod, clazz, "createGSTNativeFrame", "(Ltester/Frame$MethodId;)Ltester/Frame$JavaFrame;", true);
  jobject methodId = createMethodId(env, method_id);
  return env->CallStaticObjectMethod(clazz, constructor, methodId);
}

jobject createJavaFrame(JNIEnv *env, ASGCT_CallFrame *frame) {
  if (frame->lineno < 0) {
    return createASGCTNativeFrame(env, frame->method_id);
  }
  return createASGCTJavaFrame(env, frame->method_id, frame->lineno);
}

int countDiscardedFirstFrames(std::function<jmethodID(int)> getMethodId, int length) {
  int last = -1;
  for (int i = 0; i < length; i++) {
    jmethodID methodId = getMethodId(i);
    if (methodId != nullptr && isTracerMethod(methodId)) {
        last = i;
    }
  }
  return last + 1;
}

int countFirstTracerFrames(ASGCT_CallTrace *trace) {
  return countDiscardedFirstFrames([&](int i) { return trace->frames[i].method_id; }, trace->num_frames);
}

jobject createTrace(JNIEnv *env, ASGCT_CallTrace *trace) {
  try {
    jclass clazz = findClass(env, javaTraceClass, "tester/Trace");
    if (trace->num_frames < 0) {
      jmethodID constructor = findMethod(env, javaTraceClassErrorConstructor, clazz, "<init>", "(II)V");
      return env->NewObject(clazz, constructor, 0 /* JAVA_TRACE */, trace->num_frames);
    }
    try {
      jmethodID constructor = findMethod(env, javaTraceClassConstructor, clazz, "<init>", "(I[Ltester/Frame;)V");
      jobjectArray frames = env->NewObjectArray(trace->num_frames, findClass(env, javaFrameClass, "tester/Frame$JavaFrame"), nullptr);
      for (int i = 0; i < trace->num_frames; i++) {
        env->SetObjectArrayElement(frames, i, createJavaFrame(env, &trace->frames[i]));
      }
      return env->NewObject(clazz, constructor, ASGST_JAVA_TRACE, frames);
    } catch (const std::runtime_error &e) {
      std::cerr << "Exception in createTrace: " << e.what() << std::endl;
      jmethodID constructor = findMethod(env, javaTraceClassErrorConstructor, clazz, "<init>", "(II)V");
      return env->NewObject(clazz, constructor, 0 /* JAVA_TRACE */, -100);
    }
  } catch (std::runtime_error &e) {
    std::cerr << "Exception in createTrace: " << e.what() << std::endl;
    return nullptr;
  }
}

jobject createJavaFrame(JNIEnv *env, jvmtiFrameInfo *frame) {
  if (frame->location < 0) {
    return createGSTNativeFrame(env, frame->method);
  }
  return createGSTJavaFrame(env, frame->method, frame->location);
}

int countFirstTracerFrames(jvmtiFrameInfo *frame, int length) {
  return countDiscardedFirstFrames([&](int i) { return frame[i].method; }, length);
}

jobject createTrace(JNIEnv *env, jvmtiFrameInfo *frame, int length) {
  try {
    jclass clazz = findClass(env, javaTraceClass, "tester/Trace");
    jmethodID constructor = findMethod(env, javaTraceClassConstructor, clazz, "<init>", "(I[Ltester/Frame;)V");
    jobjectArray frames = env->NewObjectArray(length, findClass(env, javaFrameClass, "tester/Frame$JavaFrame"), nullptr);
    for (int i = 0; i < length; i++) {
      env->SetObjectArrayElement(frames, i, createJavaFrame(env, frame + i));
    }
    return env->NewObject(clazz, constructor, ASGST_JAVA_TRACE, frames);
  } catch (std::runtime_error &e) {
    std::cerr << "Exception in createTrace: " << e.what() << std::endl;
    return nullptr;
  }
}

jobject createJavaFrame(JNIEnv *env, ASGST_JavaFrame *frame) {
  jclass frameClass = findClass(env, javaFrameClass, "tester/Frame$JavaFrame");
  jmethodID frameConstructor = findMethod(env, javaFrameClassConstructor, frameClass, "<init>", "(IIILtester/Frame$MethodId;)V");
  jobject methodId = createMethodId(env, frame->method_id);
  return env->NewObject(frameClass, frameConstructor, frame->type, frame->comp_level, frame->bci, methodId);
}

jobject createNonJavaFrame(JNIEnv *env, ASGST_NonJavaFrame *frame) {
  jclass frameClass = findClass(env, nonJavaFrameClass, "tester/Frame$NonJavaFrame");
  jmethodID frameConstructor = findMethod(env, nonJavaFrameClassConstructor, frameClass, "<init>", "(J)V");
  return env->NewObject(frameClass, frameConstructor, frame->pc);
}

jobject createFrame(JNIEnv *env, ASGST_CallFrame *frame) {
  switch (frame->type) {
    case ASGST_FRAME_JAVA:
    case ASGST_FRAME_NATIVE:
    case ASGST_FRAME_JAVA_INLINED:
      return createJavaFrame(env, (ASGST_JavaFrame*) frame);
    case ASGST_FRAME_CPP:
      return createNonJavaFrame(env, (ASGST_NonJavaFrame*) frame);
    default:
      fprintf(stderr, "Error: unknown frame type %d\n", frame->type);
      exit(1);
  }
}

bool isJavaFrame(ASGST_CallFrame *frame) {
  return frame->type == ASGST_FRAME_JAVA || frame->type == ASGST_FRAME_JAVA_INLINED || frame->type == ASGST_FRAME_NATIVE;
}

bool isTracerFrame(ASGST_CallFrame *frame) {
  return isJavaFrame(frame) && isTracerMethod(((ASGST_JavaFrame*) frame)->method_id);
}

int countFirstTracerFrames(ASGST_CallTrace *trace) {
  return countDiscardedFirstFrames([&](int i) {
      ASGST_CallFrame frame = trace->frames[i];
      if (isJavaFrame(&frame)) {
        return ((ASGST_JavaFrame*) &frame)->method_id;
      }
      return (jmethodID)nullptr;
    }, trace->num_frames);
}

jobject createTrace(JNIEnv *env, ASGST_CallTrace *trace) {
  try {
    jclass clazz = findClass(env, javaTraceClass, "tester/Trace");
    if (trace->num_frames < 0) {
      jmethodID constructor = findMethod(env, javaTraceClassErrorConstructor, clazz, "<init>", "(II)V");
      return env->NewObject(clazz, constructor, trace->kind, trace->num_frames);
    }
    jmethodID constructor = findMethod(env, javaTraceClassConstructor, clazz, "<init>", "(I[Ltester/Frame;)V");
    jobjectArray frames = env->NewObjectArray(trace->num_frames, findClass(env, frameBaseClass, "tester/Frame"), nullptr);
    for (int i = 0; i < trace->num_frames; i++) {
      auto f = createFrame(env, &trace->frames[i]);
      env->SetObjectArrayElement(frames, i, f);
    }
    return env->NewObject(clazz, constructor, trace->kind, frames);
  } catch (std::runtime_error &e) {
    std::cerr << "Exception in createTrace: " << e.what() << std::endl;
    return nullptr;
  }
}

