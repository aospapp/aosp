//
// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

// clang-format off
#include "native_bridge_support/vdso/interceptable_functions.h"

DEFINE_INTERCEPTABLE_STUB_FUNCTION(JNI_CreateJavaVM);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JNI_GetCreatedJavaVMs);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JNI_GetDefaultJavaVMInitArgs);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JniInvocationCreate);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JniInvocationDestroy);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JniInvocationGetLibrary);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(JniInvocationInit);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants12UninitializeEv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants14GetStringClassEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants17GetNioAccessClassEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants17GetNioBufferClassEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants17GetReferenceClassEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants21GetReferenceGetMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants22GetFileDescriptorClassEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants22GetNioBufferLimitFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants23GetNioBufferArrayMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants24GetNioBufferAddressFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants25GetNioBufferPositionFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants27GetFileDescriptorInitMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants29GetFileDescriptorOwnerIdFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants29GetNioBufferArrayOffsetMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants30GetNioAccessGetBaseArrayMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants32EnsureClassReferencesInitializedEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants32GetFileDescriptorDescriptorFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants33GetNioBufferElementSizeShiftFieldEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN12JniConstants36GetNioAccessGetBaseArrayOffsetMethodEP7_JNIEnv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN13JniInvocation10GetLibraryEPKcPcPFbvEPFiS2_E);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl10FindSymbolEPPvPKc);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl10GetLibraryEPKcPcPFbvEPFiS2_E);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl16GetJniInvocationEv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl16JNI_CreateJavaVMEPP7_JavaVMPP7_JNIEnvPv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl21JNI_GetCreatedJavaVMsEPP7_JavaVMiPi);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl28JNI_GetDefaultJavaVMInitArgsEPv);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImpl4InitEPKc);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImplC2Ev);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(_ZN17JniInvocationImplD2Ev);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniCreateFileDescriptor);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniCreateString);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetFDFromFileDescriptor);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetNioBufferBaseArray);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetNioBufferBaseArrayOffset);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetNioBufferFields);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetNioBufferPointer);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetOwnerIdFromFileDescriptor);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniGetReferent);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniLogException);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniRegisterNativeMethods);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniSetFileDescriptorOfFD);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniStrError);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniThrowException);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniThrowExceptionFmt);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniThrowIOException);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniThrowNullPointerException);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniThrowRuntimeException);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(jniUninitializeConstants);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(newStringArray);
DEFINE_INTERCEPTABLE_STUB_FUNCTION(toStringArray);

static void __attribute__((constructor(0))) init_stub_library() {
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JNI_CreateJavaVM);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JNI_GetCreatedJavaVMs);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JNI_GetDefaultJavaVMInitArgs);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JniInvocationCreate);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JniInvocationDestroy);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JniInvocationGetLibrary);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", JniInvocationInit);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants12UninitializeEv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants14GetStringClassEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants17GetNioAccessClassEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants17GetNioBufferClassEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants17GetReferenceClassEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants21GetReferenceGetMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants22GetFileDescriptorClassEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants22GetNioBufferLimitFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants23GetNioBufferArrayMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants24GetNioBufferAddressFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants25GetNioBufferPositionFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants27GetFileDescriptorInitMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants29GetFileDescriptorOwnerIdFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants29GetNioBufferArrayOffsetMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants30GetNioAccessGetBaseArrayMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants32EnsureClassReferencesInitializedEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants32GetFileDescriptorDescriptorFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants33GetNioBufferElementSizeShiftFieldEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN12JniConstants36GetNioAccessGetBaseArrayOffsetMethodEP7_JNIEnv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN13JniInvocation10GetLibraryEPKcPcPFbvEPFiS2_E);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl10FindSymbolEPPvPKc);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl10GetLibraryEPKcPcPFbvEPFiS2_E);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl16GetJniInvocationEv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl16JNI_CreateJavaVMEPP7_JavaVMPP7_JNIEnvPv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl21JNI_GetCreatedJavaVMsEPP7_JavaVMiPi);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl28JNI_GetDefaultJavaVMInitArgsEPv);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImpl4InitEPKc);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImplC2Ev);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", _ZN17JniInvocationImplD2Ev);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniCreateFileDescriptor);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniCreateString);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetFDFromFileDescriptor);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetNioBufferBaseArray);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetNioBufferBaseArrayOffset);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetNioBufferFields);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetNioBufferPointer);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetOwnerIdFromFileDescriptor);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniGetReferent);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniLogException);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniRegisterNativeMethods);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniSetFileDescriptorOfFD);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniStrError);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniThrowException);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniThrowExceptionFmt);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniThrowIOException);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniThrowNullPointerException);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniThrowRuntimeException);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", jniUninitializeConstants);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", newStringArray);
  INIT_INTERCEPTABLE_STUB_FUNCTION("libnativehelper.so", toStringArray);
}
// clang-format on
