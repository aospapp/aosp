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
#include "PostWorker.h"

#include <string.h>

#include <chrono>

#include "ColorBuffer.h"
#include "FrameBuffer.h"
#include "RenderThreadInfo.h"
#include "aemu/base/Tracing.h"
#include "gl/DisplayGl.h"
#include "host-common/GfxstreamFatalError.h"
#include "host-common/logging.h"
#include "host-common/misc.h"
#include "vulkan/DisplayVk.h"
#include "vulkan/VkCommonOperations.h"

#define POST_DEBUG 0
#if POST_DEBUG >= 1
#define DD(fmt, ...) \
    fprintf(stderr, "%s:%d| " fmt, __func__, __LINE__, ##__VA_ARGS__)
#else
#define DD(fmt, ...) (void)0
#endif

#define POST_ERROR(fmt, ...)                                                  \
    do {                                                                      \
        fprintf(stderr, "%s(%s:%d): " fmt "\n", __func__, __FILE__, __LINE__, \
                ##__VA_ARGS__);                                               \
        fflush(stderr);                                                       \
    } while (0)

static void sDefaultRunOnUiThread(UiUpdateFunc f, void* data, bool wait) {
    (void)f;
    (void)data;
    (void)wait;
}

namespace gfxstream {
namespace {

using emugl::ABORT_REASON_OTHER;
using emugl::FatalError;
using gl::DisplayGl;
using vk::DisplayVk;

hwc_transform_t getTransformFromRotation(int rotation) {
    switch (static_cast<int>(rotation / 90)) {
        case 1:
            return HWC_TRANSFORM_ROT_270;
        case 2:
            return HWC_TRANSFORM_ROT_180;
        case 3:
            return HWC_TRANSFORM_ROT_90;
        default:
            return HWC_TRANSFORM_NONE;
    }
}

}  // namespace

PostWorker::PostWorker(bool mainThreadPostingOnly, Compositor* compositor,
                       DisplayGl* displayGl, DisplayVk* displayVk)
    : mFb(FrameBuffer::getFB()),
      m_mainThreadPostingOnly(mainThreadPostingOnly),
      m_runOnUiThread(m_mainThreadPostingOnly ? emugl::get_emugl_window_operations().runOnUiThread
                                              : sDefaultRunOnUiThread),
      m_compositor(compositor),
      m_displayGl(displayGl),
      m_displayVk(displayVk) {}

DisplayGl::PostLayer PostWorker::postWithOverlay(ColorBuffer* cb) {
    float dpr = mFb->getDpr();
    int windowWidth = mFb->windowWidth();
    int windowHeight = mFb->windowHeight();
    float px = mFb->getPx();
    float py = mFb->getPy();
    int zRot = mFb->getZrot();
    hwc_transform_t rotation = (hwc_transform_t)0;

    // Find the x and y values at the origin when "fully scrolled."
    // Multiply by 2 because the texture goes from -1 to 1, not 0 to 1.
    // Multiply the windowing coordinates by DPR because they ignore
    // DPR, but the viewport includes DPR.
    float fx = 2.f * (m_viewportWidth - windowWidth * dpr) / (float)m_viewportWidth;
    float fy = 2.f * (m_viewportHeight - windowHeight * dpr) / (float)m_viewportHeight;

    // finally, compute translation values
    float dx = px * fx;
    float dy = py * fy;

    return DisplayGl::PostLayer{
        .colorBuffer = cb,
        .overlayOptions =
            DisplayGl::PostLayer::OverlayOptions{
                .rotation = static_cast<float>(zRot),
                .dx = dx,
                .dy = dy,
            },
    };
}

std::shared_future<void> PostWorker::postImpl(ColorBuffer* cb) {
    std::shared_future<void> completedFuture =
        std::async(std::launch::deferred, [] {}).share();
    completedFuture.wait();

    if (m_displayVk) {
        constexpr const int kMaxPostRetries = 2;
        for (int i = 0; i < kMaxPostRetries; i++) {
            const auto imageInfo = mFb->borrowColorBufferForDisplay(cb->getHndl());
            auto result = m_displayVk->post(imageInfo.get());
            if (result.success) {
                return result.postCompletedWaitable;
            }
        }

        ERR("Failed to post ColorBuffer after %d retries.", kMaxPostRetries);
        return completedFuture;
    }

    if (!m_displayGl) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
            << "PostWorker missing DisplayGl.";
    }

    DisplayGl::Post post = {};

    ComposeLayer postLayerOptions = {
        .composeMode = HWC2_COMPOSITION_DEVICE,
        .blendMode = HWC2_BLEND_MODE_NONE,
        .alpha = 1.0f,
        .transform = HWC_TRANSFORM_NONE,
    };

    const auto& multiDisplay = emugl::get_emugl_multi_display_operations();
    if (multiDisplay.isMultiDisplayEnabled()) {
        if (multiDisplay.isMultiDisplayWindow()) {
            int32_t previousDisplayId = -1;
            uint32_t currentDisplayId;
            uint32_t currentDisplayColorBufferHandle;
            while (multiDisplay.getNextMultiDisplay(previousDisplayId, &currentDisplayId,
                                                    /*x=*/nullptr,
                                                    /*y=*/nullptr,
                                                    /*w=*/nullptr,
                                                    /*h=*/nullptr,
                                                    /*dpi=*/nullptr,
                                                    /*flags=*/nullptr,
                                                    &currentDisplayColorBufferHandle)) {
                previousDisplayId = currentDisplayId;

                if (currentDisplayColorBufferHandle == 0) {
                    continue;
                }
                emugl::get_emugl_window_operations().paintMultiDisplayWindow(
                    currentDisplayId, currentDisplayColorBufferHandle);
            }
            post.layers.push_back(postWithOverlay(cb));
        } else {
            uint32_t combinedDisplayW = 0;
            uint32_t combinedDisplayH = 0;
            multiDisplay.getCombinedDisplaySize(&combinedDisplayW, &combinedDisplayH);

            post.frameWidth = combinedDisplayW;
            post.frameHeight = combinedDisplayH;

            int32_t previousDisplayId = -1;
            uint32_t currentDisplayId;
            int32_t currentDisplayOffsetX;
            int32_t currentDisplayOffsetY;
            uint32_t currentDisplayW;
            uint32_t currentDisplayH;
            uint32_t currentDisplayColorBufferHandle;
            while (multiDisplay.getNextMultiDisplay(previousDisplayId,
                                                &currentDisplayId,
                                                &currentDisplayOffsetX,
                                                &currentDisplayOffsetY,
                                                &currentDisplayW,
                                                &currentDisplayH,
                                                /*dpi=*/nullptr,
                                                /*flags=*/nullptr,
                                                &currentDisplayColorBufferHandle)) {
                previousDisplayId = currentDisplayId;

                if (currentDisplayW == 0 || currentDisplayH == 0 ||
                    (currentDisplayId != 0 && currentDisplayColorBufferHandle == 0)) {
                    continue;
                }

                ColorBuffer* currentCb =
                    currentDisplayId == 0
                        ? cb
                        : mFb->findColorBuffer(currentDisplayColorBufferHandle).get();
                if (!currentCb) {
                    continue;
                }

                postLayerOptions.displayFrame = {
                    .left = static_cast<int>(currentDisplayOffsetX),
                    .top = static_cast<int>(currentDisplayOffsetY),
                    .right = static_cast<int>(currentDisplayOffsetX + currentDisplayW),
                    .bottom = static_cast<int>(currentDisplayOffsetY + currentDisplayH),
                };
                postLayerOptions.crop = {
                    .left = 0.0f,
                    .top = static_cast<float>(currentCb->getHeight()),
                    .right = static_cast<float>(currentCb->getWidth()),
                    .bottom = 0.0f,
                };

                post.layers.push_back(DisplayGl::PostLayer{
                    .colorBuffer = currentCb,
                    .layerOptions = postLayerOptions,
                });
            }
        }
    } else if (emugl::get_emugl_window_operations().isFolded()) {
        const float dpr = mFb->getDpr();

        post.frameWidth = m_viewportWidth / dpr;
        post.frameHeight = m_viewportHeight / dpr;

        int displayOffsetX;
        int displayOffsetY;
        int displayW;
        int displayH;
        emugl::get_emugl_window_operations().getFoldedArea(&displayOffsetX,
                                                           &displayOffsetY,
                                                           &displayW,
                                                           &displayH);

        postLayerOptions.displayFrame = {
            .left = 0,
            .top = 0,
            .right = mFb->windowWidth(),
            .bottom = mFb->windowHeight(),
        };
        postLayerOptions.crop = {
            .left = static_cast<float>(displayOffsetX),
            .top = static_cast<float>(displayOffsetY + displayH),
            .right = static_cast<float>(displayOffsetX + displayW),
            .bottom = static_cast<float>(displayOffsetY),
        };
        postLayerOptions.transform = getTransformFromRotation(mFb->getZrot());

        post.layers.push_back(DisplayGl::PostLayer{
            .colorBuffer = cb,
            .layerOptions = postLayerOptions,
        });
    } else {
        post.layers.push_back(postWithOverlay(cb));
    }

    return m_displayGl->post(post);
}

// Called whenever the subwindow needs a refresh (FrameBuffer::setupSubWindow).
// This rebinds the subwindow context (to account for
// when the refresh is a display change, for instance)
// and resets the posting viewport.
void PostWorker::viewportImpl(int width, int height) {
    if (m_displayVk) {
        return;
    }

    const float dpr = mFb->getDpr();
    m_viewportWidth = width * dpr;
    m_viewportHeight = height * dpr;

    if (!m_displayGl) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
            << "PostWorker missing DisplayGl.";
    }
    m_displayGl->viewport(m_viewportWidth, m_viewportHeight);
}

// Called when the subwindow refreshes, but there is no
// last posted color buffer to show to the user. Instead of
// displaying whatever happens to be in the back buffer,
// clear() is useful for outputting consistent colors.
void PostWorker::clearImpl() {
    if (m_displayVk) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
            << "PostWorker with Vulkan doesn't support clear";
    }

    if (!m_displayGl) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
            << "PostWorker missing DisplayGl.";
    }
    m_displayGl->clear();
}

std::shared_future<void> PostWorker::composeImpl(const FlatComposeRequest& composeRequest) {
    std::shared_future<void> completedFuture =
        std::async(std::launch::deferred, [] {}).share();
    completedFuture.wait();

    if (!isComposeTargetReady(composeRequest.targetHandle)) {
        ERR("The last composition on the target buffer hasn't completed.");
    }

    Compositor::CompositionRequest compositorRequest = {};
    compositorRequest.target = mFb->borrowColorBufferForComposition(composeRequest.targetHandle,
                                                                    /*colorBufferIsTarget=*/true);
    if (!compositorRequest.target) {
        ERR("Compose target is null (cb=0x%x).", composeRequest.targetHandle);
        return completedFuture;
    }

    for (const ComposeLayer& guestLayer : composeRequest.layers) {
        // Skip the ColorBuffer whose id is 0.
        if (!guestLayer.cbHandle) {
            continue;
        }
        auto source = mFb->borrowColorBufferForComposition(guestLayer.cbHandle,
                                                           /*colorBufferIsTarget=*/false);
        if (!source) {
            continue;
        }

        auto& compositorLayer = compositorRequest.layers.emplace_back();
        compositorLayer.props = guestLayer;
        compositorLayer.source = std::move(source);
    }

    return m_compositor->compose(compositorRequest);
}

void PostWorker::screenshot(ColorBuffer* cb, int width, int height, GLenum format, GLenum type,
                            int rotation, void* pixels, Rect rect) {
    if (m_displayVk) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) <<
                            "Screenshot not supported with native Vulkan swapchain enabled.";
    }
    cb->readToBytesScaled(width, height, format, type, rotation, rect, pixels);
}

void PostWorker::block(std::promise<void> scheduledSignal, std::future<void> continueSignal) {
    // Do not block mainthread.
    if (m_mainThreadPostingOnly) {
        return;
    }
    // MSVC STL doesn't support not copyable std::packaged_task. As a workaround, we use the
    // copyable std::shared_ptr here.
    auto block = std::make_shared<Post::Block>(Post::Block{
        .scheduledSignal = std::move(scheduledSignal),
        .continueSignal = std::move(continueSignal),
    });
    runTask(std::packaged_task<void()>([block] {
        block->scheduledSignal.set_value();
        block->continueSignal.wait();
    }));
}

PostWorker::~PostWorker() {}

void PostWorker::post(ColorBuffer* cb, std::unique_ptr<Post::CompletionCallback> postCallback) {
    auto packagedPostCallback = std::shared_ptr<Post::CompletionCallback>(std::move(postCallback));
    runTask(
        std::packaged_task<void()>([cb, packagedPostCallback, this] {
            auto completedFuture = postImpl(cb);
            (*packagedPostCallback)(completedFuture);
        }));
}

void PostWorker::viewport(int width, int height) {
    runTask(std::packaged_task<void()>(
        [width, height, this] { viewportImpl(width, height); }));
}

void PostWorker::compose(std::unique_ptr<FlatComposeRequest> composeRequest,
                         std::unique_ptr<Post::CompletionCallback> composeCallback) {
    // std::shared_ptr(std::move(...)) is WA for MSFT STL implementation bug:
    // https://developercommunity.visualstudio.com/t/unable-to-move-stdpackaged-task-into-any-stl-conta/108672
    auto packagedComposeCallback =
        std::shared_ptr<Post::CompletionCallback>(std::move(composeCallback));
    auto packagedComposeRequest = std::shared_ptr<FlatComposeRequest>(std::move(composeRequest));
    runTask(
        std::packaged_task<void()>([packagedComposeCallback, packagedComposeRequest, this] {
        auto completedFuture = composeImpl(*packagedComposeRequest);
        m_composeTargetToComposeFuture.emplace(packagedComposeRequest->targetHandle,
                                               completedFuture);
        (*packagedComposeCallback)(completedFuture);
        }));
}

void PostWorker::clear() {
    runTask(std::packaged_task<void()>([this] { clearImpl(); }));
}

void PostWorker::runTask(std::packaged_task<void()> task) {
    using Task = std::packaged_task<void()>;
    auto taskPtr = std::make_unique<Task>(std::move(task));
    if (m_mainThreadPostingOnly) {
        m_runOnUiThread(
            [](void* data) {
                std::unique_ptr<Task> taskPtr(reinterpret_cast<Task*>(data));
                (*taskPtr)();
            },
            taskPtr.release(), false);
    } else {
        (*taskPtr)();
    }
}

bool PostWorker::isComposeTargetReady(uint32_t targetHandle) {
    // Even if the target ColorBuffer has already been destroyed, the compose future should have
    // been waited and set to the ready state.
    for (auto i = m_composeTargetToComposeFuture.begin();
         i != m_composeTargetToComposeFuture.end();) {
        auto& composeFuture = i->second;
        if (composeFuture.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
            i = m_composeTargetToComposeFuture.erase(i);
        } else {
            i++;
        }
    }
    if (m_composeTargetToComposeFuture.find(targetHandle) == m_composeTargetToComposeFuture.end()) {
        return true;
    }
    return false;
}

}  // namespace gfxstream
