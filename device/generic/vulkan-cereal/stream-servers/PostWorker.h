/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include <functional>
#include <future>
#include <optional>
#include <unordered_map>
#include <vector>

#include "Compositor.h"
#include "ContextHelper.h"
#include "Hwc2.h"
#include "PostCommands.h"
#include "aemu/base/Compiler.h"
#include "aemu/base/synchronization/Lock.h"
#include "aemu/base/synchronization/MessageChannel.h"
#include "gl/DisplayGl.h"
#include "host-common/window_agent.h"

namespace gfxstream {
namespace gl {
class DisplayGl;
}  // namespace gl
}  // namespace gfxstream

namespace gfxstream {
namespace vk {
class DisplayVk;
}  // namespace vk
}  // namespace gfxstream

namespace gfxstream {
class ColorBuffer;
class FrameBuffer;
struct RenderThreadInfo;

class PostWorker {
   public:
    PostWorker(bool mainThreadPostingOnly, Compositor* compositor, gl::DisplayGl* displayGl,
               vk::DisplayVk* displayVk);
    ~PostWorker();

    // post: posts the next color buffer.
    // Assumes framebuffer lock is held.
    void post(ColorBuffer* cb, std::unique_ptr<Post::CompletionCallback> postCallback);

    // viewport: (re)initializes viewport dimensions.
    // Assumes framebuffer lock is held.
    // This is called whenever the subwindow needs a refresh
    // (FrameBuffer::setupSubWindow).
    void viewport(int width, int height);

    // compose: compse the layers into final framebuffer. The callback will be
    // called when the CPU side job completes. The passed in future in the
    // callback will be completed when the GPU opereation completes.
    void compose(std::unique_ptr<FlatComposeRequest> composeRequest,
                 std::unique_ptr<Post::CompletionCallback> composeCallback);

    // clear: blanks out emulator display when refreshing the subwindow
    // if there is no last posted color buffer to show yet.
    void clear();

    void screenshot(ColorBuffer* cb, int screenwidth, int screenheight, GLenum format, GLenum type,
                    int skinRotation, void* pixels, Rect rect);

    // The block task will set the scheduledSignal promise when the task is scheduled, and wait
    // until continueSignal is ready before completes.
    void block(std::promise<void> scheduledSignal, std::future<void> continueSignal);

   private:
    // Impl versions of the above, so we can run it from separate threads
    std::shared_future<void> postImpl(ColorBuffer* cb);
    gl::DisplayGl::PostLayer postWithOverlay(ColorBuffer* cb);
    void viewportImpl(int width, int height);
    std::shared_future<void> composeImpl(const FlatComposeRequest& composeRequest);
    void clearImpl();

    // If m_mainThreadPostingOnly is true, schedule the task to UI thread by
    // using m_runOnUiThread. Otherwise, execute the task on the current thread.
    void runTask(std::packaged_task<void()>);

   private:
    using UiThreadRunner = std::function<void(UiUpdateFunc, void*, bool)>;

    FrameBuffer* mFb;

    Compositor* m_compositor = nullptr;

    int m_viewportWidth = 0;
    int m_viewportHeight = 0;

    bool m_mainThreadPostingOnly = false;
    UiThreadRunner m_runOnUiThread = 0;

    // TODO(b/233939967): conslidate DisplayGl and DisplayVk into
    // `Display* const m_display`.
    gl::DisplayGl* const m_displayGl;
    // The implementation for Vulkan native swapchain. Only initialized when
    // useVulkan is set when calling FrameBuffer::initialize(). PostWorker
    // doesn't take the ownership of this DisplayVk object.
    vk::DisplayVk* const m_displayVk;
    std::unordered_map<uint32_t, std::shared_future<void>> m_composeTargetToComposeFuture;

    bool isComposeTargetReady(uint32_t targetHandle);

    DISALLOW_COPY_AND_ASSIGN(PostWorker);
};

}  // namespace gfxstream
