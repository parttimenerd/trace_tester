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

#include "tester_util_NativeUtil.h"

static void call_runnable(JNIEnv *env, jobject runnable) {
  jclass runnableClass = env->GetObjectClass(runnable);
  jmethodID runMethod = env->GetMethodID(runnableClass, "run", "()V");
  env->CallVoidMethod(runnable, runMethod);
}

/*
 * Class:     tester_util_NativeUtil
 * Method:    call
 * Signature: (Ljava/lang/Runnable;)V
 */
[[gnu::always_inline]]
JNIEXPORT void JNICALL Java_tester_util_NativeUtil_call
  (JNIEnv *env, jclass, jobject runnable) {
  call_runnable(env, runnable);
}


[[gnu::noinline]] void call_runnable_wrapper(JNIEnv *env, jobject runnable) {
  call_runnable(env, runnable);
}

[[gnu::noinline]] void call_runnable_wrapper_wrapper(JNIEnv *env, jobject runnable) {
  call_runnable_wrapper(env, runnable);
}

/*
 * Class:     tester_util_NativeUtil
 * Method:    callWithC
 * Signature: (Ljava/lang/Runnable;)V
 */
JNIEXPORT void JNICALL Java_tester_util_NativeUtil_callWithC
  (JNIEnv *env, jclass, jobject runnable) {
  call_runnable_wrapper(env, runnable);
}

/*
 * Class:     tester_util_NativeUtil
 * Method:    callWithCC
 * Signature: (Ljava/lang/Runnable;)V
 */
JNIEXPORT void JNICALL Java_tester_util_NativeUtil_callWithCC
  (JNIEnv *env, jclass, jobject runnable) {
  call_runnable_wrapper_wrapper(env, runnable);
}