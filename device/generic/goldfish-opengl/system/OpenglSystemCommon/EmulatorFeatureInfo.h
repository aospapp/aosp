// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#ifndef __COMMON_EMULATOR_FEATURE_INFO_H
#define __COMMON_EMULATOR_FEATURE_INFO_H

// SyncImpl determines the presence of host/guest OpenGL fence sync
// capabilities. It corresponds exactly to EGL_ANDROID_native_fence_sync
// capability, but for the emulator, we need to make sure that
// OpenGL pipe protocols match, so we use a special extension name
// here.
// SYNC_IMPL_NONE means that the native fence sync capability is
// not present, and we will end up using the equivalent of glFinish
// in order to preserve buffer swapping order.
// SYNC_IMPL_NATIVE_SYNC means that we do have native fence sync
// capability, and we will use a fence fd to synchronize buffer swaps.
enum SyncImpl {
    SYNC_IMPL_NONE = 0,
    SYNC_IMPL_NATIVE_SYNC_V2 = 1,
    SYNC_IMPL_NATIVE_SYNC_V3 = 2,
};

// Interface:
// Use the highest of v2 or v3 that show up, making us
// SYNC_IMPL_NATIVE_SYNC_V2 or SYNC_IMPL_NATIVE_SYNC_V3.
static const char kRCNativeSyncV2[] = "ANDROID_EMU_native_sync_v2";
static const char kRCNativeSyncV3[] = "ANDROID_EMU_native_sync_v3";

// DMA for OpenGL
enum DmaImpl {
    DMA_IMPL_NONE = 0,
    DMA_IMPL_v1 = 1,
};

static const char kDmaExtStr_v1[] = "ANDROID_EMU_dma_v1";

// OpenGL ES max supported version
enum GLESMaxVersion {
    GLES_MAX_VERSION_2 = 0,
    GLES_MAX_VERSION_3_0 = 1,
    GLES_MAX_VERSION_3_1 = 2,
    GLES_MAX_VERSION_3_2 = 3,
};

static const char kGLESMaxVersion_2[] = "ANDROID_EMU_gles_max_version_2";
static const char kGLESMaxVersion_3_0[] = "ANDROID_EMU_gles_max_version_3_0";
static const char kGLESMaxVersion_3_1[] = "ANDROID_EMU_gles_max_version_3_1";
static const char kGLESMaxVersion_3_2[] = "ANDROID_EMU_gles_max_version_3_2";

enum HostComposition {
    HOST_COMPOSITION_NONE = 0,
    HOST_COMPOSITION_V1,
};

static const char kHostCompositionV1[] = "ANDROID_EMU_host_composition_v1";

// No querying errors from host extension
static const char kGLESNoHostError[] = "ANDROID_EMU_gles_no_host_error";

// Host to guest memory mapping
static const char kGLDirectMem[] = "ANDROID_EMU_direct_mem";

// Vulkan host support
// To be delivered/enabled when at least the following is working/available:
// - HOST_COHERENT memory mapping
// - Full gralloc interop: External memory, AHB
static const char kVulkan[] = "ANDROID_EMU_vulkan";

// Deferred Vulkan commands
static const char kDeferredVulkanCommands[] = "ANDROID_EMU_deferred_vulkan_commands";

// Struct describing available emulator features
struct EmulatorFeatureInfo {

    EmulatorFeatureInfo() :
        syncImpl(SYNC_IMPL_NONE),
        dmaImpl(DMA_IMPL_NONE),
        hostComposition(HOST_COMPOSITION_NONE),
        glesMaxVersion(GLES_MAX_VERSION_2),
        hasDirectMem(false),
        hasVulkan(false),
        hasDeferredVulkanCommands(false) { }

    SyncImpl syncImpl;
    DmaImpl dmaImpl;
    HostComposition hostComposition;
    GLESMaxVersion glesMaxVersion;
    bool hasDirectMem;
    bool hasVulkan;
    bool hasDeferredVulkanCommands;
};

#endif // __COMMON_EMULATOR_FEATURE_INFO_H
