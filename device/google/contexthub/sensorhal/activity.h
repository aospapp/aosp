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

#ifndef ACTIVITY_H_

#define ACTIVITY_H_

#include <hardware/activity_recognition.h>
#include <media/stagefright/foundation/ABase.h>
#include <utils/KeyedVector.h>

#include "activityeventhandler.h"
#include "hubconnection.h"

namespace android {

class ActivityContext : public ActivityEventHandler {
  public:
    activity_recognition_device_t device;

    explicit ActivityContext(const struct hw_module_t *module);
    ~ActivityContext();

    bool getHubAlive();

    void registerActivityCallback(
            const activity_recognition_callback_procs_t *callback);

    bool isEnabled(uint32_t activity_handle, uint32_t event_type) const;

    int enableActivityEvent(uint32_t activity_handle,
        uint32_t event_type, int64_t max_batch_report_latency_ns);

    int disableActivityEvent(uint32_t activity_handle, uint32_t event_type);

    int flush();

    // ActivityEventHandler interface.
    virtual void OnActivityEvent(int activityRaw, uint64_t whenNs) override;
    virtual void OnFlush() override;
  private:
    android::sp<android::HubConnection> mHubConnection;

    android::Mutex mCallbackLock;
    const activity_recognition_callback_procs_t *mCallback;

    android::KeyedVector<uint64_t, int64_t> mMaxBatchReportLatencyNs;

    int mPrevActivity;

    bool mInitExitDone;

    int64_t calculateReportLatencyNs();

    DISALLOW_EVIL_CONSTRUCTORS(ActivityContext);
};

}  // namespace android

#endif  // ACTIVITY_H_

