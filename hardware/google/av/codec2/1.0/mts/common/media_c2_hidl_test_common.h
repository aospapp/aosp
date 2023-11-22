/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef MEDIA_C2_HIDL_TEST_COMMON_H
#define MEDIA_C2_HIDL_TEST_COMMON_H

#include <getopt.h>
#include <media/stagefright/foundation/ALooper.h>
#include <utils/Condition.h>
#include <utils/Mutex.h>

#include <hardware/google/media/c2/1.0/IComponent.h>
#include <hardware/google/media/c2/1.0/IComponentListener.h>
#include <hardware/google/media/c2/1.0/IComponentStore.h>
#include <hardware/google/media/c2/1.0/types.h>

using ::hardware::google::media::c2::V1_0::WorkBundle;
using ::hardware::google::media::c2::V1_0::IComponentStore;
using ::hardware::google::media::c2::V1_0::IComponentListener;
using ::hardware::google::media::c2::V1_0::SettingResult;
using ::hardware::google::media::c2::V1_0::Status;
using ::hardware::google::media::c2::V1_0::Work;

using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;

#include <VtsHalHidlTargetTestEnvBase.h>

/*
 * Handle Callback functions onWorkDone(), onTripped(),
 * onError()
 */
struct CodecListener : public IComponentListener {
   public:
    CodecListener(std::function<void(Work)> fn = nullptr) : callBack(fn) {}
    Return<void> onWorkDone(const WorkBundle& workBundle) override {
        android::Mutex::Autolock autoLock(workLock);
        for (auto& item : workBundle.works) {
            processedWork.push_back(std::move(item));
        }
        workCondition.signal();
        return Void();
    }
    Return<void> onTripped(
        const hidl_vec<SettingResult>& settingResults) override {
        (void)settingResults;
        /* TODO */
        /*
        android::Mutex::Autolock autoLock(workLock);
        for (hidl_vec<SettingResult>::const_iterator it =
                 settingResults.begin();
             it != settingResults.end(); ++it) {
            processedWork.push_back(*it);
        }
        workCondition.signal();
        */
        return Void();
    }
    Return<void> onError(Status status, uint32_t errorCode) override {
        /* TODO */
        (void)status;
        (void)errorCode;
        return Void();
    }
    Return<void> onFramesRendered(
        const hidl_vec<RenderedFrame>& renderedFrames) override {
        /* TODO */
        (void) renderedFrames;
        return Void();
    }

    Status dequeueWork(Work* work) {
        (void)work;
        android::Mutex::Autolock autoLock(workLock);
        android::List<Work>::iterator it = processedWork.begin();
        while (it != processedWork.end()) {
            if (callBack) callBack(*it);
            it = processedWork.erase(it);
        }
        return Status::OK;
    }
    ::android::List<Work> processedWork;
    android::Mutex workLock;
    android::Condition workCondition;
    std::function<void(Work)> callBack;
};

// A class for test environment setup
class ComponentTestEnvironment : public ::testing::VtsHalHidlTargetTestEnvBase {
   private:
    typedef ::testing::VtsHalHidlTargetTestEnvBase Super;

   public:
    virtual void registerTestServices() override {
        registerTestService<IComponentStore>();
    }

    ComponentTestEnvironment() : res("/sdcard/media/") {}

    void setComponent(const char* _component) { component = _component; }

    void setInstance(const char* _instance) { instance = _instance; }

    void setRes(const char* _res) { res = _res; }

    const hidl_string getInstance() {
        ALOGV("Calling get instance");
        return Super::getServiceName<IComponentStore>(instance);
    }

    const hidl_string getComponent() const { return component; }

    const hidl_string getRes() const { return res; }

    int initFromOptions(int argc, char** argv) {
        static struct option options[] = {
            {"instance", required_argument, 0, 'I'},
            {"component", required_argument, 0, 'C'},
            {"res", required_argument, 0, 'P'},
            {0, 0, 0, 0}};

        while (true) {
            int index = 0;
            int c = getopt_long(argc, argv, "I:C:P:", options, &index);
            if (c == -1) {
                break;
            }

            switch (c) {
                case 'I':
                    setInstance(optarg);
                    break;
                case 'C':
                    setComponent(optarg);
                    break;
                case 'P':
                    setRes(optarg);
                    break;
                case '?':
                    break;
            }
        }

        if (optind < argc) {
            fprintf(stderr,
                    "unrecognized option: %s\n\n"
                    "usage: %s <gtest options> <test options>\n\n"
                    "test options are:\n\n"
                    "-I, --instance: software for C2 components, else default\n"
                    "-C, --component: C2 component to test\n"
                    "-P, --res: Resource files directory location\n",
                    argv[optind ?: 1], argv[0]);
            return 2;
        }
        return 0;
    }

   private:
    hidl_string instance;
    hidl_string component;
    hidl_string res;
};

#endif  // MEDIA_C2_HIDL_TEST_COMMON_H
