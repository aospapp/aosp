/*
 * Copyright 2020 The Android Open Source Project
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

#include "MockAIBinderDeathRegistrationWrapper.h"
#include "MockCarWatchdogServiceForSystem.h"
#include "MockHidlServiceManager.h"
#include "MockVhalClient.h"
#include "MockWatchdogServiceHelper.h"
#include "WatchdogProcessService.h"
#include "WatchdogServiceHelper.h"

#include <android/binder_interface_utils.h>
#include <android/hidl/manager/1.0/IServiceManager.h>
#include <gmock/gmock.h>

#include <thread>  // NOLINT(build/c++11)

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::ICarWatchdogClientDefault;
using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitorDefault;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;
using ::aidl::android::hardware::automotive::vehicle::VehicleProperty;
using ::android::IBinder;
using ::android::Looper;
using ::android::sp;
using ::android::base::Error;
using ::android::frameworks::automotive::vhal::ClientStatusError;
using ::android::frameworks::automotive::vhal::ErrorCode;
using ::android::frameworks::automotive::vhal::IHalPropConfig;
using ::android::frameworks::automotive::vhal::IVhalClient;
using ::android::frameworks::automotive::vhal::VhalClientError;
using ::android::frameworks::automotive::vhal::VhalClientResult;
using ::android::hidl::base::V1_0::DebugInfo;
using ::android::hidl::manager::V1_0::IServiceManager;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::ndk::SpAIBinder;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Invoke;
using ::testing::Matcher;
using ::testing::Return;

namespace {

constexpr std::chrono::milliseconds kMaxWaitForLooperExecutionMillis = 5s;
constexpr std::chrono::nanoseconds kTestVhalPidCachingRetryDelayNs = 20ms;
constexpr char kTestLooperThreadName[] = "WdProcSvcTest";
constexpr const int32_t kTestAidlVhalPid = 564269;
constexpr const int32_t kTestPidStartTime = 12356;
constexpr const int32_t kMaxVhalPidCachingAttempts = 2;

enum TestMessage {
    NOTIFY_ALL,
    ON_AIDL_VHAL_PID,
};

ProcessIdentifier constructProcessIdentifier(int32_t pid, int64_t startTimeMillis) {
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = pid;
    processIdentifier.startTimeMillis = startTimeMillis;
    return processIdentifier;
}

MATCHER_P(ProcessIdentifierEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("pid", &ProcessIdentifier::pid, Eq(expected.pid)),
                                    Field("startTimeMillis", &ProcessIdentifier::startTimeMillis,
                                          Eq(expected.startTimeMillis))),
                              arg, result_listener);
}

}  // namespace

namespace internal {

class WatchdogProcessServicePeer final {
public:
    explicit WatchdogProcessServicePeer(const sp<WatchdogProcessService>& watchdogProcessService) :
          mWatchdogProcessService(watchdogProcessService) {}

    void expectVhalProcessIdentifier(const Matcher<const ProcessIdentifier&> matcher) {
        Mutex::Autolock lock(mWatchdogProcessService->mMutex);
        EXPECT_TRUE(mWatchdogProcessService->mVhalProcessIdentifier.has_value());
        EXPECT_THAT(mWatchdogProcessService->mVhalProcessIdentifier.value(), matcher);
    }

    void expectNoVhalProcessIdentifier() {
        EXPECT_FALSE(mWatchdogProcessService->mVhalProcessIdentifier.has_value());
    }

private:
    sp<WatchdogProcessService> mWatchdogProcessService;
};

}  // namespace internal

class WatchdogProcessServiceTest : public ::testing::Test {
public:
    WatchdogProcessServiceTest() :
          mMockVhalClient(nullptr),
          mMockHidlServiceManager(nullptr),
          kTryCreateVhalClientFunc([this]() { return mMockVhalClient; }),
          kTryGetHidlServiceManagerFunc([this]() { return mMockHidlServiceManager; }),
          kGetStartTimeForPidFunc([](pid_t) { return kTestPidStartTime; }) {}

protected:
    void SetUp() override {
        mMessageHandler = sp<MessageHandlerImpl>::make(this);
        mMockVehicle = SharedRefBase::make<MockVehicle>();
        mMockVhalClient = std::make_shared<MockVhalClient>(mMockVehicle);
        mMockHidlServiceManager = sp<MockHidlServiceManager>::make();
        mMockDeathRegistrationWrapper = sp<MockAIBinderDeathRegistrationWrapper>::make();
        mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT};
        mNotSupportedVehicleProperties = {VehicleProperty::WATCHDOG_ALIVE,
                                          VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
        startService();
    }

    void TearDown() override {
        terminateService();
        mMockDeathRegistrationWrapper.clear();
        mMockHidlServiceManager.clear();
        mMockVhalClient.reset();
        mMockVehicle.reset();
        mMessageHandler.clear();
    }

    void startService() {
        prepareLooper();
        mWatchdogProcessService =
                sp<WatchdogProcessService>::make(kTryCreateVhalClientFunc,
                                                 kTryGetHidlServiceManagerFunc,
                                                 kGetStartTimeForPidFunc,
                                                 kTestVhalPidCachingRetryDelayNs, mHandlerLooper,
                                                 mMockDeathRegistrationWrapper);
        mWatchdogProcessServicePeer =
                std::make_unique<internal::WatchdogProcessServicePeer>(mWatchdogProcessService);

        expectGetPropConfigs(mSupportedVehicleProperties, mNotSupportedVehicleProperties);

        mWatchdogProcessService->start();
        // Sync with the looper before proceeding to ensure that all startup looper messages are
        // processed before testing the service.
        syncLooper();
    }

    void terminateService() {
        wakeAndJoinLooper();
        mWatchdogProcessServicePeer.reset();
        mWatchdogProcessService->terminate();
        mWatchdogProcessService.clear();
        mHandlerLooper.clear();
    }

    void expectLinkToDeath(AIBinder* aiBinder, ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    linkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectUnlinkToDeath(AIBinder* aiBinder, ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectNoUnlinkToDeath(AIBinder* aiBinder) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .Times(0);
    }

    void expectGetPropConfigs(const std::vector<VehicleProperty>& supportedProperties,
                              const std::vector<VehicleProperty>& notSupportedProperties) {
        for (const auto& propId : supportedProperties) {
            EXPECT_CALL(*mMockVhalClient,
                        getPropConfigs(std::vector<int32_t>{static_cast<int32_t>(propId)}))
                    .WillOnce([]() { return std::vector<std::unique_ptr<IHalPropConfig>>(); });
        }
        for (const auto& propId : notSupportedProperties) {
            EXPECT_CALL(*mMockVhalClient,
                        getPropConfigs(std::vector<int32_t>{static_cast<int32_t>(propId)}))
                    .WillOnce(
                            []() -> VhalClientResult<std::vector<std::unique_ptr<IHalPropConfig>>> {
                                return Error<VhalClientError>(ErrorCode::NOT_AVAILABLE_FROM_VHAL)
                                        << "Not supported";
                            });
        }
    }

    // Expect the requestAidlVhalPid call from the implementation on registering CarWatchdogService
    // and mimic CarWatchdogService response by posting the onAidlVhalPidFetched call on the looper.
    void expectRequestAidlVhalPidAndRespond(
            const sp<MockWatchdogServiceHelper>& mockServiceHelper) {
        EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).WillOnce([&]() {
            mHandlerLooper->sendMessageDelayed(kTestVhalPidCachingRetryDelayNs.count() / 2,
                                               mMessageHandler,
                                               Message(TestMessage::ON_AIDL_VHAL_PID));
            return ScopedAStatus::ok();
        });
    }

    void syncLooper(std::chrono::nanoseconds delay = 0ns) {
        mHandlerLooper->sendMessageDelayed(delay.count(), mMessageHandler,
                                           Message(TestMessage::NOTIFY_ALL));
        waitForLooperNotification(delay);
    }

    void waitForLooperNotification(std::chrono::nanoseconds delay = 0ns) {
        std::unique_lock lock(mMutex);
        mLooperCondition.wait_for(lock,
                                  kMaxWaitForLooperExecutionMillis +
                                          std::chrono::duration_cast<std::chrono::milliseconds>(
                                                  delay));
    }

    void waitUntilVhalPidCachingAttemptsExhausted() {
        syncLooper((kMaxVhalPidCachingAttempts + 1) * kTestVhalPidCachingRetryDelayNs);
    }

    sp<WatchdogProcessService> mWatchdogProcessService;
    std::unique_ptr<internal::WatchdogProcessServicePeer> mWatchdogProcessServicePeer;
    std::shared_ptr<MockVhalClient> mMockVhalClient;
    std::shared_ptr<MockVehicle> mMockVehicle;
    sp<MockHidlServiceManager> mMockHidlServiceManager;
    sp<MockAIBinderDeathRegistrationWrapper> mMockDeathRegistrationWrapper;
    std::vector<VehicleProperty> mSupportedVehicleProperties;
    std::vector<VehicleProperty> mNotSupportedVehicleProperties;

private:
    class MessageHandlerImpl : public android::MessageHandler {
    public:
        explicit MessageHandlerImpl(WatchdogProcessServiceTest* test) : mTest(test) {}

        void handleMessage(const Message& message) override {
            switch (message.what) {
                case static_cast<int>(TestMessage::NOTIFY_ALL):
                    break;
                case static_cast<int>(TestMessage::ON_AIDL_VHAL_PID):
                    mTest->mWatchdogProcessService->onAidlVhalPidFetched(kTestAidlVhalPid);
                    break;
                default:
                    ALOGE("Unknown TestMessage: %d", message.what);
                    return;
            }
            std::unique_lock lock(mTest->mMutex);
            mTest->mLooperCondition.notify_all();
        }

    private:
        WatchdogProcessServiceTest* mTest;
    };

    // Looper runs on the calling thread when it is polled for messages with the poll* calls.
    // The poll* calls are blocking, so they must be executed on a separate thread.
    void prepareLooper() {
        mHandlerLooper = Looper::prepare(/*opts=*/0);
        mHandlerLooperThread = std::thread([this]() {
            Looper::setForThread(mHandlerLooper);
            if (int result = pthread_setname_np(pthread_self(), kTestLooperThreadName);
                result != 0) {
                ALOGE("Failed to set test looper thread name: %s", strerror(result));
            }
            mShouldTerminateLooper.store(false);
            while (!mShouldTerminateLooper.load()) {
                mHandlerLooper->pollAll(/*timeoutMillis=*/-1);
            }
        });
    }

    void wakeAndJoinLooper() {
        // Sync with the looper to make sure all messages for the current time slot are processed
        // before terminating the looper. This will help satisfy any pending EXPECT_CALLs.
        syncLooper();
        mShouldTerminateLooper.store(true);
        mHandlerLooper->wake();
        if (mHandlerLooperThread.joinable()) {
            mHandlerLooperThread.join();
        }
    }

    const std::function<std::shared_ptr<IVhalClient>()> kTryCreateVhalClientFunc;
    const std::function<android::sp<android::hidl::manager::V1_0::IServiceManager>()>
            kTryGetHidlServiceManagerFunc;
    const std::function<int64_t(pid_t)> kGetStartTimeForPidFunc;

    sp<Looper> mHandlerLooper;
    sp<MessageHandlerImpl> mMessageHandler;
    std::thread mHandlerLooperThread;
    mutable std::mutex mMutex;
    std::condition_variable mLooperCondition GUARDED_BY(mMutex);
    std::atomic<bool> mShouldTerminateLooper;
};

TEST_F(WatchdogProcessServiceTest, TestTerminate) {
    std::vector<int32_t> propIds = {static_cast<int32_t>(VehicleProperty::VHAL_HEARTBEAT)};
    EXPECT_CALL(*mMockVhalClient, removeOnBinderDiedCallback(_)).Times(1);
    EXPECT_CALL(*mMockVehicle, unsubscribe(_, propIds))
            .WillOnce(Return(ByMove(std::move(ScopedAStatus::ok()))));
    mWatchdogProcessService->terminate();
    // TODO(b/217405065): Verify looper removes all MSG_VHAL_HEALTH_CHECK messages.
}

// TODO(b/217405065): Add test to verify the handleVhalDeath method.

TEST_F(WatchdogProcessServiceTest, TestRegisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    status = mWatchdogProcessService->unregisterClient(client);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterClient(client).isOk())
            << "Unregistering an unregistered client should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestErrorOnRegisterClientWithDeadBinder) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));

    ASSERT_FALSE(
            mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL).isOk())
            << "When linkToDeath fails, registerClient should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleClientBinderDeath) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessService->handleBinderDeath(static_cast<void*>(aiBinder));

    expectNoUnlinkToDeath(aiBinder);

    ASSERT_FALSE(mWatchdogProcessService->unregisterClient(client).isOk())
            << "Unregistering a dead client should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestRegisterCarWatchdogService) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid())
            .WillOnce(Return(ByMove(std::move(ScopedAStatus::ok()))));

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // The implementation posts message on the looper to cache VHAL pid when registering
    // the car watchdog service. So, sync with the looper to ensure the above requestAidlVhalPid
    // EXPECT_CALL is satisfied.
    syncLooper();

    // No new request to fetch AIDL VHAL pid should be sent on duplicate registration.
    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).Times(0);

    status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest,
       TestErrorOnRegisterCarWatchdogServiceWithNullWatchdogServiceHelper) {
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    ASSERT_FALSE(mWatchdogProcessService->registerCarWatchdogService(binder, nullptr).isOk())
            << "Registering car watchdog service should fail when watchdog service helper is null";
}

TEST_F(WatchdogProcessServiceTest, TestRegisterMonitor) {
    std::shared_ptr<ICarWatchdogMonitor> monitorOne =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    expectLinkToDeath(monitorOne->asBinder().get(), std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitorOne);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerMonitor(monitorOne);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    std::shared_ptr<ICarWatchdogMonitor> monitorTwo =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    status = mWatchdogProcessService->registerMonitor(monitorTwo);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestErrorOnRegisterMonitorWithDeadBinder) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    expectLinkToDeath(monitor->asBinder().get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));

    ASSERT_FALSE(mWatchdogProcessService->registerMonitor(monitor).isOk())
            << "When linkToDeath fails, registerMonitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterMonitor) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    status = mWatchdogProcessService->unregisterMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering an unregistered monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleMonitorBinderDeath) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessService->handleBinderDeath(static_cast<void*>(aiBinder));

    expectNoUnlinkToDeath(aiBinder);

    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering a dead monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellClientAlive) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), std::move(ScopedAStatus::ok()));

    mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(mWatchdogProcessService->tellClientAlive(client, 1234).isOk())
            << "tellClientAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellCarWatchdogServiceAlive) {
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();

    std::vector<ProcessIdentifier> processIdentifiers;
    processIdentifiers.push_back(
            constructProcessIdentifier(/* pid= */ 111, /* startTimeMillis= */ 0));
    processIdentifiers.push_back(
            constructProcessIdentifier(/* pid= */ 222, /* startTimeMillis= */ 0));
    ASSERT_FALSE(mWatchdogProcessService
                         ->tellCarWatchdogServiceAlive(mockService, processIdentifiers, 1234)
                         .isOk())
            << "tellCarWatchdogServiceAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellDumpFinished) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    ASSERT_FALSE(mWatchdogProcessService
                         ->tellDumpFinished(monitor,
                                            constructProcessIdentifier(/* pid= */ 1234,
                                                                       /* startTimeMillis= */ 0))
                         .isOk())
            << "Unregistered monitor cannot call tellDumpFinished";

    expectLinkToDeath(monitor->asBinder().get(), std::move(ScopedAStatus::ok()));

    mWatchdogProcessService->registerMonitor(monitor);
    auto status = mWatchdogProcessService
                          ->tellDumpFinished(monitor,
                                             constructProcessIdentifier(/* pid= */ 1234,
                                                                        /* startTimeMillis= */ 0));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestCacheAidlVhalPidFromCarWatchdogService) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    expectRequestAidlVhalPidAndRespond(mockServiceHelper);

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // On processing the TestMessage::ON_AIDL_VHAL_PID, the looper notifies all waiting threads.
    // Wait for the notification to ensure the VHAL pid caching is satisfied.
    waitForLooperNotification();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectVhalProcessIdentifier(
            ProcessIdentifierEq(constructProcessIdentifier(kTestAidlVhalPid, kTestPidStartTime))));
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheAidlVhalPidWithNoCarWatchdogServiceResponse) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid())
            .Times(kMaxVhalPidCachingAttempts)
            .WillRepeatedly([&]() {
                // No action taken by CarWatchdogService.
                return ScopedAStatus::ok();
            });

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // Because CarWatchdogService doesn't respond with the AIDL VHAL pid, wait until all caching
    // attempts are exhausted to ensure the expected number of caching attempts are satisfied.
    waitUntilVhalPidCachingAttemptsExhausted();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestNoCacheAidlVhalPidWithUnsupportedVhalHeartBeatProperty) {
    // The supported vehicle property list is fetched as soon as VHAL is connected, which happens
    // during the start of the service. So, restart the service for the new VHAL settings to take
    // effect.
    terminateService();

    mSupportedVehicleProperties.clear();
    mNotSupportedVehicleProperties.push_back(VehicleProperty::VHAL_HEARTBEAT);

    startService();

    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).Times(0);

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // VHAL process identifier caching happens on the looper thread. Sync with the looper before
    // proceeding.
    syncLooper();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestCacheHidlVhalPidFromHidlServiceManager) {
    // VHAL PID caching logic is determined as soon as VHAL is connected, which happens during
    // the start of the service. So, restart the service for the new VHAL settings to take effect.
    terminateService();

    using InstanceDebugInfo = IServiceManager::InstanceDebugInfo;
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillOnce(Return(false));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_))
            .WillOnce(Invoke([](IServiceManager::debugDump_cb cb) {
                cb({InstanceDebugInfo{"android.hardware.automotive.evs@1.0::IEvsCamera",
                                      "vehicle_hal_insts",
                                      8058,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT},
                    InstanceDebugInfo{"android.hardware.automotive.vehicle@2.0::IVehicle",
                                      "vehicle_hal_insts",
                                      static_cast<int>(IServiceManager::PidConstant::NO_PID),
                                      {},
                                      DebugInfo::Architecture::IS_64BIT},
                    InstanceDebugInfo{"android.hardware.automotive.vehicle@2.0::IVehicle",
                                      "vehicle_hal_insts",
                                      2034,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT}});
                return android::hardware::Void();
            }));

    startService();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectVhalProcessIdentifier(
            ProcessIdentifierEq(constructProcessIdentifier(2034, kTestPidStartTime))));
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheHidlVhalPidWithNoHidlVhalService) {
    // VHAL PID caching logic is determined as soon as VHAL is connected, which happens during
    // the start of the service. So, restart the service for the new VHAL settings to take effect.
    terminateService();

    using InstanceDebugInfo = IServiceManager::InstanceDebugInfo;
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillRepeatedly(Return(false));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_))
            .Times(kMaxVhalPidCachingAttempts)
            .WillRepeatedly(Invoke([](IServiceManager::debugDump_cb cb) {
                cb({InstanceDebugInfo{"android.hardware.automotive.evs@1.0::IEvsCamera",
                                      "vehicle_hal_insts",
                                      8058,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT}});
                return android::hardware::Void();
            }));

    startService();

    // Because HIDL service manager doesn't have the HIDL VHAL pid, wait until all caching
    // attempts are exhausted to ensure the expected number of caching attempts are satisfied.
    waitUntilVhalPidCachingAttemptsExhausted();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestNoCacheHidlVhalPidWithUnsupportedVhalHeartBeatProperty) {
    // The supported vehicle property list is fetched as soon as VHAL is connected, which happens
    // during the start of the service. So, restart the service for the new VHAL settings to take
    // effect.
    terminateService();

    mSupportedVehicleProperties.clear();
    mNotSupportedVehicleProperties.push_back(VehicleProperty::VHAL_HEARTBEAT);

    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_)).Times(0);

    startService();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
