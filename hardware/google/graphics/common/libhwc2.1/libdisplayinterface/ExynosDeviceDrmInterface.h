/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef _EXYNOSDEVICEDRMINTERFACE_H
#define _EXYNOSDEVICEDRMINTERFACE_H

#include "resourcemanager.h"
#include "ExynosDeviceInterface.h"

using namespace android;

class ExynosDevice;
class ExynosDeviceDrmInterface : public ExynosDeviceInterface {
    public:
        ExynosDeviceDrmInterface(ExynosDevice *exynosDevice);
        virtual ~ExynosDeviceDrmInterface();
        virtual void init(ExynosDevice *exynosDevice) override;
        virtual int32_t initDisplayInterface(
                std::unique_ptr<ExynosDisplayInterface> &dispInterface) override;
        virtual void updateRestrictions() override;
    protected:
        class ExynosDrmEventHandler: public DrmEventHandler, public DrmTUIEventHandler {
            public:
                void HandleEvent(uint64_t timestamp_us) override;
                void HandleTUIEvent() override;
                void init(ExynosDevice *exynosDevice, DrmDevice *drmDevice);
            private:
                ExynosDevice *mExynosDevice;
		DrmDevice *mDrmDevice;
        };
        ResourceManager mDrmResourceManager;
        DrmDevice *mDrmDevice;
        ExynosDrmEventHandler mExynosDrmEventHandler;
};

#endif //_EXYNOSDEVICEDRMINTERFACE_H
