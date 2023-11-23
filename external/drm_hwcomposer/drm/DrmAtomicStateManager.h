/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_DRM_ATOMIC_STATE_MANAGER_H_
#define ANDROID_DRM_ATOMIC_STATE_MANAGER_H_

#include <pthread.h>

#include <functional>
#include <memory>
#include <optional>
#include <sstream>
#include <tuple>

#include "compositor/DrmKmsPlan.h"
#include "compositor/LayerData.h"
#include "drm/DrmPlane.h"
#include "drm/ResourceManager.h"
#include "drm/VSyncWorker.h"

namespace android {

struct AtomicCommitArgs {
  /* inputs. All fields are optional, but at least one has to be specified */
  bool test_only = false;
  std::optional<DrmMode> display_mode;
  std::optional<bool> active;
  std::shared_ptr<DrmKmsPlan> composition;

  /* out */
  UniqueFd out_fence;

  /* helpers */
  auto HasInputs() -> bool {
    return display_mode || active || composition;
  }
};

class PresentTrackerThread {
 public:
  explicit PresentTrackerThread(DrmAtomicStateManager *st_man);

  ~PresentTrackerThread();

  void Stop() {
    /* Exit thread by signalling that object is no longer valid */
    st_man_ = nullptr;
    Notify();
    pt_.detach();
  }

  void Notify() {
    cv_.notify_all();
  }

 private:
  DrmAtomicStateManager *st_man_{};

  void PresentTrackerThreadFn();

  std::condition_variable cv_;
  std::thread pt_;
  std::mutex *mutex_;
};

class DrmAtomicStateManager {
  friend class PresentTrackerThread;

 public:
  explicit DrmAtomicStateManager(DrmDisplayPipeline *pipe)
      : pipe_(pipe),
        ptt_(std::make_unique<PresentTrackerThread>(this).release()){};

  DrmAtomicStateManager(const DrmAtomicStateManager &) = delete;
  ~DrmAtomicStateManager() {
    ptt_->Stop();
  }

  auto ExecuteAtomicCommit(AtomicCommitArgs &args) -> int;
  auto ActivateDisplayUsingDPMS() -> int;

 private:
  auto CommitFrame(AtomicCommitArgs &args) -> int;

  struct KmsState {
    /* Required to cleanup unused planes */
    std::vector<std::shared_ptr<BindingOwner<DrmPlane>>> used_planes;
    /* We have to hold a reference to framebuffer while displaying it ,
     * otherwise picture will blink */
    std::vector<std::shared_ptr<DrmFbIdHandle>> used_framebuffers;

    DrmModeUserPropertyBlobUnique mode_blob;

    int release_fence_pt_index{};

    /* To avoid setting the inactive state twice, which will fail the commit */
    bool crtc_active_state{};
  } active_frame_state_;

  auto NewFrameState() -> KmsState {
    auto *prev_frame_state = &active_frame_state_;
    return (KmsState){
        .used_planes = prev_frame_state->used_planes,
        .crtc_active_state = prev_frame_state->crtc_active_state,
    };
  }

  DrmDisplayPipeline *const pipe_;

  void CleanupPriorFrameResources();

  /* Present (swap) tracking */
  PresentTrackerThread *ptt_;
  KmsState staged_frame_state_;
  UniqueFd last_present_fence_;
  int frames_staged_{};
  int frames_tracked_{};
};

}  // namespace android

#endif  // ANDROID_DRM_DISPLAY_COMPOSITOR_H_
