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

//#define LOG_NDEBUG 0
#define LOG_TAG "Codec2-Component"
#include <log/log.h>

#include <C2PlatformSupport.h>
#include <codec2/hidl/1.0/Component.h>
#include <codec2/hidl/1.0/ComponentStore.h>
#include <codec2/hidl/1.0/types.h>

#include <hidl/HidlBinderSupport.h>

#include <C2BqBufferPriv.h>
#include <C2PlatformSupport.h>

namespace hardware {
namespace google {
namespace media {
namespace c2 {
namespace V1_0 {
namespace utils {

using namespace ::android;

namespace /* unnamed */ {

// Implementation of ConfigurableC2Intf based on C2ComponentInterface
struct CompIntf : public ConfigurableC2Intf {
    CompIntf(const std::shared_ptr<C2ComponentInterface>& intf) :
        ConfigurableC2Intf(intf->getName()),
        mIntf(intf) {
    }

    virtual c2_status_t config(
            const std::vector<C2Param*>& params,
            c2_blocking_t mayBlock,
            std::vector<std::unique_ptr<C2SettingResult>>* const failures
            ) override {
        ALOGV("config");
        return mIntf->config_vb(params, mayBlock, failures);
    }

    virtual c2_status_t query(
            const std::vector<C2Param::Index>& indices,
            c2_blocking_t mayBlock,
            std::vector<std::unique_ptr<C2Param>>* const params
            ) const override {
        ALOGV("query");
        return mIntf->query_vb({}, indices, mayBlock, params);
    }

    virtual c2_status_t querySupportedParams(
            std::vector<std::shared_ptr<C2ParamDescriptor>>* const params
            ) const override {
        ALOGV("querySupportedParams");
        return mIntf->querySupportedParams_nb(params);
    }

    virtual c2_status_t querySupportedValues(
            std::vector<C2FieldSupportedValuesQuery>& fields,
            c2_blocking_t mayBlock) const override {
        ALOGV("querySupportedValues");
        return mIntf->querySupportedValues_vb(fields, mayBlock);
    }

protected:
    std::shared_ptr<C2ComponentInterface> mIntf;
};

} // unnamed namespace

// ComponentInterface
ComponentInterface::ComponentInterface(
        const std::shared_ptr<C2ComponentInterface>& intf,
        const sp<ComponentStore>& store) :
    Configurable(new CachedConfigurable(std::make_unique<CompIntf>(intf))),
    mInterface(intf) {
    mInit = init(store.get());
}

c2_status_t ComponentInterface::status() const {
    return mInit;
}

// ComponentListener wrapper
struct Component::Listener : public C2Component::Listener {

    Listener(const sp<Component>& component) :
        mComponent(component),
        mListener(component->mListener) {
    }

    virtual void onError_nb(
            std::weak_ptr<C2Component> /* c2component */,
            uint32_t errorCode) override {
        ALOGV("onError");
        sp<IComponentListener> listener = mListener.promote();
        if (listener) {
            Return<void> transStatus = listener->onError(Status::OK, errorCode);
            if (!transStatus.isOk()) {
                ALOGE("onError -- transaction failed.");
            }
        }
    }

    virtual void onTripped_nb(
            std::weak_ptr<C2Component> /* c2component */,
            std::vector<std::shared_ptr<C2SettingResult>> c2settingResult
            ) override {
        ALOGV("onTripped");
        sp<IComponentListener> listener = mListener.promote();
        if (listener) {
            hidl_vec<SettingResult> settingResults(c2settingResult.size());
            size_t ix = 0;
            for (const std::shared_ptr<C2SettingResult> &c2result :
                    c2settingResult) {
                if (c2result) {
                    if (objcpy(&settingResults[ix++], *c2result) !=
                            Status::OK) {
                        break;
                    }
                }
            }
            settingResults.resize(ix);
            Return<void> transStatus = listener->onTripped(settingResults);
            if (!transStatus.isOk()) {
                ALOGE("onTripped -- transaction failed.");
            }
        }
    }

    virtual void onWorkDone_nb(
            std::weak_ptr<C2Component> /* c2component */,
            std::list<std::unique_ptr<C2Work>> c2workItems) override {
        ALOGV("onWorkDone");
        sp<IComponentListener> listener = mListener.promote();
        if (listener) {
            WorkBundle workBundle;

            sp<Component> strongComponent = mComponent.promote();
            if (objcpy(&workBundle, c2workItems, strongComponent ?
                    &strongComponent->mBufferPoolSender : nullptr)
                    != Status::OK) {
                ALOGE("onWorkDone() received corrupted work items.");
                return;
            }
            Return<void> transStatus = listener->onWorkDone(workBundle);
            if (!transStatus.isOk()) {
                ALOGE("onWorkDone -- transaction failed.");
                return;
            }
            yieldBufferQueueBlocks(c2workItems, true);
        }
    }

protected:
    wp<Component> mComponent;
    wp<IComponentListener> mListener;
};

// Component
Component::Component(
        const std::shared_ptr<C2Component>& component,
        const sp<IComponentListener>& listener,
        const sp<ComponentStore>& store,
        const sp<::android::hardware::media::bufferpool::V1_0::
        IClientManager>& clientPoolManager) :
    Configurable(new CachedConfigurable(
            std::make_unique<CompIntf>(component->intf()))),
    mComponent(component),
    mInterface(component->intf()),
    mListener(listener),
    mStore(store),
    mBufferPoolSender(clientPoolManager) {
    // Retrieve supported parameters from store
    // TODO: We could cache this per component/interface type
    mInit = init(store.get());
}

c2_status_t Component::status() const {
    return mInit;
}

// Methods from ::android::hardware::media::c2::V1_0::IComponent
Return<Status> Component::queue(const WorkBundle& workBundle) {
    ALOGV("queue -- converting input");
    std::list<std::unique_ptr<C2Work>> c2works;

    // TODO: Connect with bufferpool API for buffer transfers
    if (objcpy(&c2works, workBundle) != C2_OK) {
        ALOGV("queue -- corrupted");
        return Status::CORRUPTED;
    }
    ALOGV("queue -- calling");
    return static_cast<Status>(mComponent->queue_nb(&c2works));
}

Return<void> Component::flush(flush_cb _hidl_cb) {
    std::list<std::unique_ptr<C2Work>> c2flushedWorks;
    ALOGV("flush -- calling");
    c2_status_t c2res = mComponent->flush_sm(
            C2Component::FLUSH_COMPONENT,
            &c2flushedWorks);
    WorkBundle flushedWorkBundle;

    Status res = static_cast<Status>(c2res);
    if (c2res == C2_OK) {
        ALOGV("flush -- converting output");
        res = objcpy(&flushedWorkBundle, c2flushedWorks, &mBufferPoolSender);
    }
    _hidl_cb(res, flushedWorkBundle);
    yieldBufferQueueBlocks(c2flushedWorks, true);
    return Void();
}

Return<Status> Component::drain(bool withEos) {
    ALOGV("drain");
    return static_cast<Status>(mComponent->drain_nb(withEos ?
            C2Component::DRAIN_COMPONENT_WITH_EOS :
            C2Component::DRAIN_COMPONENT_NO_EOS));
}

Return<Status> Component::setOutputSurface(
        uint64_t blockPoolId,
        const sp<HGraphicBufferProducer>& surface) {
    std::shared_ptr<C2BlockPool> pool;
    GetCodec2BlockPool(blockPoolId, mComponent, &pool);
    if (pool && pool->getAllocatorId() == C2PlatformAllocatorStore::BUFFERQUEUE) {
        std::shared_ptr<C2BufferQueueBlockPool> bqPool =
                std::static_pointer_cast<C2BufferQueueBlockPool>(pool);
        C2BufferQueueBlockPool::OnRenderCallback cb =
            [this](uint64_t producer, int32_t slot, int64_t nsecs) {
                // TODO: batch this
                hidl_vec<IComponentListener::RenderedFrame> rendered;
                rendered.resize(1);
                rendered[0] = { producer, slot, nsecs };
                mListener->onFramesRendered(rendered);
        };
        if (bqPool) {
            bqPool->setRenderCallback(cb);
            bqPool->configureProducer(surface);
        }
    }
    return Status::OK;
}

Return<Status> Component::connectToOmxInputSurface(
        const sp<HGraphicBufferProducer>& producer,
        const sp<::android::hardware::media::omx::V1_0::
        IGraphicBufferSource>& source) {
    // TODO implement
    (void)producer;
    (void)source;
    return Status::OMITTED;
}

Return<Status> Component::disconnectFromInputSurface() {
    // TODO implement
    return Status::OK;
}

namespace /* unnamed */ {

struct BlockPoolIntf : public ConfigurableC2Intf {
    BlockPoolIntf(const std::shared_ptr<C2BlockPool>& pool) :
        ConfigurableC2Intf("C2BlockPool:" + std::to_string(pool->getLocalId())),
        mPool(pool) {
    }

    virtual c2_status_t config(
            const std::vector<C2Param*>& params,
            c2_blocking_t mayBlock,
            std::vector<std::unique_ptr<C2SettingResult>>* const failures
            ) override {
        (void)params;
        (void)mayBlock;
        (void)failures;
        return C2_OK;
    }

    virtual c2_status_t query(
            const std::vector<C2Param::Index>& indices,
            c2_blocking_t mayBlock,
            std::vector<std::unique_ptr<C2Param>>* const params
            ) const override {
        (void)indices;
        (void)mayBlock;
        (void)params;
        return C2_OK;
    }

    virtual c2_status_t querySupportedParams(
            std::vector<std::shared_ptr<C2ParamDescriptor>>* const params
            ) const override {
        (void)params;
        return C2_OK;
    }

    virtual c2_status_t querySupportedValues(
            std::vector<C2FieldSupportedValuesQuery>& fields,
            c2_blocking_t mayBlock) const override {
        (void)fields;
        (void)mayBlock;
        return C2_OK;
    }

protected:
    std::shared_ptr<C2BlockPool> mPool;
};

} // unnamed namespace

Return<void> Component::createBlockPool(
        uint32_t allocatorId,
        createBlockPool_cb _hidl_cb) {
    std::shared_ptr<C2BlockPool> blockPool;
    c2_status_t status = CreateCodec2BlockPool(
            static_cast<C2PlatformAllocatorStore::id_t>(allocatorId),
            mComponent,
            &blockPool);
    if (status != C2_OK) {
        blockPool = nullptr;
    }
    if (blockPool) {
        mBlockPoolsMutex.lock();
        mBlockPools.emplace(blockPool->getLocalId(), blockPool);
        mBlockPoolsMutex.unlock();
    } else if (status == C2_OK) {
        status = C2_CORRUPTED;
    }

    _hidl_cb(static_cast<Status>(status),
            blockPool ? blockPool->getLocalId() : 0,
            new CachedConfigurable(
            std::make_unique<BlockPoolIntf>(blockPool)));
    return Void();
}

Return<Status> Component::destroyBlockPool(uint64_t blockPoolId) {
    std::lock_guard<std::mutex> lock(mBlockPoolsMutex);
    return mBlockPools.erase(blockPoolId) == 1 ?
            Status::OK : Status::CORRUPTED;
}

Return<Status> Component::start() {
    ALOGV("start");
    return static_cast<Status>(mComponent->start());
}

Return<Status> Component::stop() {
    ALOGV("stop");
    return static_cast<Status>(mComponent->stop());
}

Return<Status> Component::reset() {
    ALOGV("reset");
    Status status = static_cast<Status>(mComponent->reset());
    {
        std::lock_guard<std::mutex> lock(mBlockPoolsMutex);
        mBlockPools.clear();
    }
    return status;
}

Return<Status> Component::release() {
    ALOGV("release");
    Status status = static_cast<Status>(mComponent->release());
    {
        std::lock_guard<std::mutex> lock(mBlockPoolsMutex);
        mBlockPools.clear();
    }
    return status;
}

void Component::setLocalId(const Component::LocalId& localId) {
    mLocalId = localId;
}

void Component::initListener(const sp<Component>& self) {
    std::shared_ptr<C2Component::Listener> c2listener =
            std::make_shared<Listener>(self);
    c2_status_t res = mComponent->setListener_vb(c2listener, C2_DONT_BLOCK);
    if (res != C2_OK) {
        mInit = res;
    }
}

Component::~Component() {
    mStore->reportComponentDeath(mLocalId);
}

Component::InterfaceKey::InterfaceKey(const sp<IComponent>& component) {
    isRemote = component->isRemote();
    if (isRemote) {
        remote = ::android::hardware::toBinder(component);
    } else {
        local = component;
    }
}

}  // namespace utils
}  // namespace V1_0
}  // namespace c2
}  // namespace media
}  // namespace google
}  // namespace hardware
