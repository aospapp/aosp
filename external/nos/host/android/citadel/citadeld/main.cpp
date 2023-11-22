/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <future>
#include <iomanip>
#include <limits>
#include <mutex>
#include <thread>

#include <android-base/logging.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <app_nugget.h>
#include <citadel_events.h>
#include <nos/NuggetClient.h>
#include <nos/device.h>

#include <android/hardware/citadel/BnCitadeld.h>

#include <android/vendor/powerstats/BnPixelPowerStatsCallback.h>
#include <android/vendor/powerstats/BnPixelPowerStatsProvider.h>
#include <android/vendor/powerstats/StateResidencyData.h>

using ::android::defaultServiceManager;
using ::android::IPCThreadState;
using ::android::IServiceManager;
using ::android::OK;
using ::android::ProcessState;
using ::android::sp;
using ::android::status_t;
using ::android::wp;
using ::android::binder::Status;

using ::nos::NuggetClient;

using ::android::hardware::citadel::BnCitadeld;
using ::android::hardware::citadel::ICitadeld;

using android::IBinder;
using android::vendor::powerstats::BnPixelPowerStatsCallback;
using android::vendor::powerstats::IPixelPowerStatsProvider;
using android::vendor::powerstats::StateResidencyData;

namespace {

using namespace std::chrono_literals;

// This attaches a timer to a function call. Call .schedule() to start the
// timer, and the function will be called (once) after the time has elapsed. If
// you call .schedule() again before that happens, it just restarts the timer.
// There's no way to cancel the function call after it's scheduled; you can only
// postpone it.
class DeferredCallback {
  public:
    DeferredCallback(std::chrono::milliseconds delay, std::function<void()> fn)
        : _armed(false),
          _delay(delay),
          _func(fn),
          _waiter_thread(std::bind(&DeferredCallback::waiter_task, this)) {}
    ~DeferredCallback() {}

    // [re]start the timer for the delayed call
    void schedule() {
        std::unique_lock<std::mutex> _lock(_cv_mutex);
        _armed = true;
        _cv.notify_one();
    }

  private:
    void waiter_task(void) {
        std::unique_lock<std::mutex> _lock(_cv_mutex);
        while (true) {
            if (!_armed) {
                _cv.wait(_lock);
            }
            auto timeout = std::chrono::steady_clock::now() + _delay;
            if (_cv.wait_until(_lock, timeout) == std::cv_status::timeout) {
                _func();
                _armed = false;
            }
        }
    }

    bool _armed;
    const std::chrono::milliseconds _delay;
    const std::function<void()> _func;
    std::thread _waiter_thread;
    std::mutex _cv_mutex;
    std::condition_variable _cv;
};

// This provides a Binder interface for the powerstats service to fetch our
// power stats info from. This is a secondary function of citadeld. Failures
// here must not block or delay AP/Citadel communication.
class StatsDelegate : public BnPixelPowerStatsCallback,
                      public IBinder::DeathRecipient {
  public:
    StatsDelegate(std::function<Status(std::vector<StateResidencyData>*)> fn)
        : func_(fn) {}

    // methods from BnPixelPowerStatsCallback
    virtual Status getStats(std::vector<StateResidencyData>* stats) override {
        return func_(stats);
    }

    // methods from IBinder::DeathRecipient
    virtual IBinder* onAsBinder() override { return this; }
    virtual void binderDied(const wp<IBinder>& who) override {
        LOG(INFO) << "powerstats service died";
        const sp<IBinder>& service = who.promote();
        if (service != nullptr) {
            service->unlinkToDeath(this);
        }
        sp<IBinder> powerstats_service = WaitForPowerStatsService();
        registerWithPowerStats(powerstats_service);
    }

    // post-creation init (Binder calls inside constructor are troublesome)
    void registerWithPowerStats(sp<IBinder>& powerstats_service) {
        sp<IPixelPowerStatsProvider> powerstats_provider =
                android::interface_cast<IPixelPowerStatsProvider>(
                        powerstats_service);

        LOG(INFO) << "signing up for a notification if powerstats dies";
        auto ret = asBinder(powerstats_provider)
                           ->linkToDeath(this, 0u /* cookie */);
        if (ret != android::OK) {
            LOG(ERROR) << "linkToDeath() returned " << ret
                       << " - we will NOT be notified on powerstats death";
        }

        LOG(INFO) << "registering our callback with powerstats service";
        Status status = powerstats_provider->registerCallback("Citadel", this);
        if (!status.isOk()) {
            LOG(ERROR) << "failed to register callback: " << status.toString8();
        }
    }

    // static helper function
    static sp<IBinder> WaitForPowerStatsService() {
        LOG(INFO) << "waiting for powerstats service to appear";
        sp<IBinder> svc;
        while (true) {
            svc = defaultServiceManager()->checkService(
                    android::String16("power.stats-vendor"));
            if (svc != nullptr) {
                LOG(INFO) << "A wild powerstats service has appeared!";
                return svc;
            }
            sleep(1);
        }
    }

    // Creates a new StatsDelegate only after a powerstats service becomes
    // available for it to register with.
    static sp<StatsDelegate> MakeOne(
            std::function<Status(std::vector<StateResidencyData>*)> fn) {
        sp<IBinder> powerstats_service =
                StatsDelegate::WaitForPowerStatsService();
        sp<StatsDelegate> sd = new StatsDelegate(fn);
        sd->registerWithPowerStats(powerstats_service);
        return sd;
    }

  private:
    const std::function<Status(std::vector<StateResidencyData>*)> func_;
};

class CitadelProxy : public BnCitadeld {
  public:
    CitadelProxy(NuggetClient& client)
        : _client{client},
          _event_thread(std::bind(&CitadelProxy::dispatchEvents, this)),
          _stats_collection(500ms, std::bind(&CitadelProxy::cacheStats, this)) {
    }
    ~CitadelProxy() override = default;

    // methods from BnCitadeld

    Status callApp(const int32_t _appId, const int32_t _arg,
                   const std::vector<uint8_t>& request,
                   std::vector<uint8_t>* const response,
                   int32_t* const _aidl_return) override {
        // AIDL doesn't support integers less than 32-bit so validate it before
        // casting
        if (_appId < 0 || _appId > kMaxAppId) {
            LOG(ERROR) << "App ID " << _appId << " is outside the app ID range";
            return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT);
        }
        if (_arg < 0 || _arg > std::numeric_limits<uint16_t>::max()) {
            LOG(ERROR) << "Argument " << _arg
                       << " is outside the unsigned 16-bit range";
            return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT);
        }

        const uint8_t appId = static_cast<uint32_t>(_appId);
        const uint16_t arg = static_cast<uint16_t>(_arg);
        uint32_t* const appStatus = reinterpret_cast<uint32_t*>(_aidl_return);
        *appStatus = lockedCallApp(appId, arg, request, response);

        _stats_collection.schedule();

        return Status::ok();
    }

    Status reset(bool* const _aidl_return) override {
        // This doesn't use the transport API to talk to any app so doesn't need
        // to hold any app locks.
        const nos_device& device = *_client.Device();
        *_aidl_return = (device.ops.reset(device.ctx) == 0);
        return Status::ok();
    }

    Status getCachedStats(std::vector<uint8_t>* const response) override {
        std::unique_lock<std::mutex> lock(_stats_mutex);
        response->resize(sizeof(_stats));
        memcpy(response->data(), &_stats, sizeof(_stats));
        return Status::ok();
    }

    // Interaction with the powerstats service is handled by the StatsDelegate
    // class, but its getStats() method calls this to access our cached stats.
    Status onGetStats(std::vector<StateResidencyData>* stats) {
        std::unique_lock<std::mutex> lock(_stats_mutex);

        StateResidencyData data1;
        data1.state = "Last-Reset";
        data1.totalTimeInStateMs = _stats.time_since_hard_reset / 1000;
        data1.totalStateEntryCount = _stats.hard_reset_count;
        data1.lastEntryTimestampMs = 0;
        stats->emplace_back(data1);

        StateResidencyData data2;
        data2.state = "Active";
        data2.totalTimeInStateMs = _stats.time_spent_awake / 1000;
        data2.totalStateEntryCount = _stats.wake_count;
        data2.lastEntryTimestampMs = _stats.time_at_last_wake / 1000;
        stats->emplace_back(data2);

        StateResidencyData data3;
        data3.state = "Deep-Sleep";
        data3.totalTimeInStateMs = _stats.time_spent_in_deep_sleep / 1000;
        data3.totalStateEntryCount = _stats.deep_sleep_count;
        data3.lastEntryTimestampMs = _stats.time_at_last_deep_sleep / 1000;
        stats->emplace_back(data3);

        return Status::ok();
    }

private:
    static constexpr auto kMaxAppId = std::numeric_limits<uint8_t>::max();

    NuggetClient& _client;
    std::thread _event_thread;
    std::mutex _appLocks[kMaxAppId + 1];
    struct nugget_app_low_power_stats _stats;
    DeferredCallback _stats_collection;
    std::mutex _stats_mutex;

    // Make the call to the app while holding the lock for that app
    uint32_t lockedCallApp(uint32_t appId, uint16_t arg,
                           const std::vector<uint8_t>& request,
                           std::vector<uint8_t>* response) {
        std::unique_lock<std::mutex> lock(_appLocks[appId]);
        return _client.CallApp(appId, arg, request, response);
    }

    void cacheStats(void) {
        std::vector<uint8_t> buffer;

        buffer.reserve(sizeof(_stats));
        uint32_t rv = lockedCallApp(APP_ID_NUGGET,
                                    NUGGET_PARAM_GET_LOW_POWER_STATS, buffer,
                                    &buffer);
        if (rv == APP_SUCCESS) {
            std::unique_lock<std::mutex> lock(_stats_mutex);
            memcpy(&_stats, buffer.data(),
                   std::min(sizeof(_stats), buffer.size()));
        }
    }

    [[noreturn]] void dispatchEvents(void) {
        LOG(INFO) << "Event dispatcher startup.";

        const nos_device& device = *_client.Device();

        while (true) {

            const int wait_rv = device.ops.wait_for_interrupt(device.ctx, -1);
            if (wait_rv <= 0) {
                LOG(WARNING) << "device.ops.wait_for_interrupt: " << wait_rv;
                continue;
            }

            // CTDL_AP_IRQ is asserted, fetch all the event_records from Citadel
            while (true) {
                struct event_record evt;
                std::vector<uint8_t> buffer;
                buffer.reserve(sizeof(evt));
                const uint32_t rv = lockedCallApp(APP_ID_NUGGET,
                                                  NUGGET_PARAM_GET_EVENT_RECORD,
                                                  buffer, &buffer);
                if (rv != APP_SUCCESS) {
                    LOG(WARNING) << "failed to fetch event_record: " << rv;
                    break;
                }

                if (buffer.size() == 0) {
                    // Success but no data means we've fetched them all
                    break;
                }

                // TODO(b/34946126): Do something more than just log it
                memcpy(&evt, buffer.data(), sizeof(evt));
                const uint64_t secs = evt.uptime_usecs / 1000000UL;
                const uint64_t usecs = evt.uptime_usecs - (secs * 1000000UL);
                LOG(INFO) << std::setfill('0') << std::internal
                          << "event_record " << evt.reset_count << "/"
                          << secs << "." << std::setw(6) << usecs
                          << " " << evt.id
                          << std::hex
                          << " 0x" << std::setw(8) << evt.u.raw.w[0]
                          << " 0x" << std::setw(8) << evt.u.raw.w[1]
                          << " 0x" << std::setw(8) << evt.u.raw.w[2];
            }

            // TODO: Add a more intelligent back-off (and other action?) here
            //
            // When Citadel indicates that it has event_records for us, we fetch
            // one at a time without delay until we've gotten them all (and then
            // wait a bit to give it time to deassert CTDL_AP_IRQ).
            //
            // OTOH, if Citadel is just constantly asserting CTDL_AP_IRQ but
            // doesn't actually have any events for us, then a) that's probably
            // a bug, and b) we shouldn't spin madly here just querying it over
            // and over.
            sleep(1);
        }
    }
};

} // namespace

int main() {
    LOG(INFO) << "Starting citadeld";

    // Connect to Citadel
    NuggetClient citadel;
    citadel.Open();
    if (!citadel.IsOpen()) {
        LOG(FATAL) << "Failed to open Citadel client";
    }

    // Citadel HALs will communicate with this daemon via /dev/vndbinder as this
    // is vendor code
    ProcessState::initWithDriver("/dev/vndbinder");

    sp<CitadelProxy> proxy = new CitadelProxy(citadel);
    const status_t status = defaultServiceManager()->addService(ICitadeld::descriptor, proxy);
    if (status != OK) {
        LOG(FATAL) << "Failed to register citadeld as a service (status " << status << ")";
        return 1;
    }

    // We'll create a StatsDelegate object to talk to the powerstats service,
    // but it will need a function to access the stats we've cached in the
    // CitadelProxy object.
    std::function<Status(std::vector<StateResidencyData>*)> fn =
            std::bind(&CitadelProxy::onGetStats, proxy, std::placeholders::_1);

    // Use a separate thread to wait for the powerstats service to appear, so
    // the Citadel proxy can start working ASAP.
    std::future<sp<StatsDelegate>> sd =
            std::async(std::launch::async, StatsDelegate::MakeOne, fn);

    // Start handling binder requests with multiple threads
    ProcessState::self()->startThreadPool();
    IPCThreadState::self()->joinThreadPool();

    return 0;
}
