/*
 * Copyright 2022 The Android Open Source Project
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
#include "hci/distance_measurement_manager.h"

#include <math.h>

#include <unordered_map>

#include "hci/acl_manager.h"
#include "hci/hci_layer.h"
#include "module.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace hci {

const ModuleFactory DistanceMeasurementManager::Factory =
    ModuleFactory([]() { return new DistanceMeasurementManager(); });
static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
static constexpr uint8_t kTxPowerNotAvailable = 0xfe;
static constexpr int8_t kRSSIDropOffAt1M = 41;

struct DistanceMeasurementManager::impl {
  ~impl() {}
  void start(os::Handler* handler, hci::HciLayer* hci_layer, hci::AclManager* acl_manager) {
    handler_ = handler;
    hci_layer_ = hci_layer;
    acl_manager_ = acl_manager;
    hci_layer_->RegisterLeEventHandler(
        hci::SubeventCode::TRANSMIT_POWER_REPORTING,
        handler_->BindOn(this, &impl::on_transmit_power_reporting));
  }

  void stop() {
    hci_layer_->UnregisterLeEventHandler(hci::SubeventCode::TRANSMIT_POWER_REPORTING);
  }

  void register_distance_measurement_callbacks(DistanceMeasurementCallbacks* callbacks) {
    distance_measurement_callbacks_ = callbacks;
  }

  void start_distance_measurement(
      const Address& address, uint16_t frequency, DistanceMeasurementMethod method) {
    LOG_INFO("Address:%s, method:%d", ADDRESS_TO_LOGGABLE_CSTR(address), method);
    uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);

    // Remove this check if we support any connection less method
    if (connection_handle == kIllegalConnectionHandle) {
      LOG_WARN("Can not find any LE connection");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_NO_LE_CONNECTION, method);
      return;
    }

    switch (method) {
      case METHOD_AUTO:
      case METHOD_RSSI: {
        if (rssi_trackers.find(address) == rssi_trackers.end()) {
          rssi_trackers[address].handle = connection_handle;
          rssi_trackers[address].frequency = frequency;
          rssi_trackers[address].remote_tx_power = kTxPowerNotAvailable;
          rssi_trackers[address].started = false;
          rssi_trackers[address].alarm = std::make_unique<os::Alarm>(handler_);
          hci_layer_->EnqueueCommand(
              LeReadRemoteTransmitPowerLevelBuilder::Create(
                  acl_manager_->HACK_GetLeHandle(address), 0x01),
              handler_->BindOnceOn(
                  this, &impl::on_read_remote_transmit_power_level_status, address));
        } else {
          rssi_trackers[address].frequency = frequency;
        }
      } break;
    }
  }

  void stop_distance_measurement(const Address& address, DistanceMeasurementMethod method) {
    LOG_INFO("Address:%s, method:%d", ADDRESS_TO_LOGGABLE_CSTR(address), method);
    switch (method) {
      case METHOD_AUTO:
      case METHOD_RSSI: {
        if (rssi_trackers.find(address) == rssi_trackers.end()) {
          LOG_WARN("Can't find rssi tracker for %s ", ADDRESS_TO_LOGGABLE_CSTR(address));
        } else {
          hci_layer_->EnqueueCommand(
              LeSetTransmitPowerReportingEnableBuilder::Create(
                  rssi_trackers[address].handle, 0x00, 0x00),
              handler_->BindOnceOn(
                  this, &impl::check_status<LeSetTransmitPowerReportingEnableCompleteView>));
          rssi_trackers[address].alarm->Cancel();
          rssi_trackers[address].alarm.reset();
          rssi_trackers.erase(address);
        }
      } break;
    }
  }

  void read_rssi_regularly(const Address& address, uint16_t frequency) {
    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      LOG_WARN("Can't find rssi tracker for %s ", ADDRESS_TO_LOGGABLE_CSTR(address));
      return;
    }
    uint16_t connection_handle = acl_manager_->HACK_GetLeHandle(address);
    if (connection_handle == kIllegalConnectionHandle) {
      LOG_WARN("Can't find connection for %s ", ADDRESS_TO_LOGGABLE_CSTR(address));
      if (rssi_trackers.find(address) != rssi_trackers.end()) {
        distance_measurement_callbacks_->OnDistanceMeasurementStopped(
            address, REASON_NO_LE_CONNECTION, METHOD_RSSI);
        rssi_trackers[address].alarm->Cancel();
        rssi_trackers[address].alarm.reset();
        rssi_trackers.erase(address);
      }
      return;
    }

    hci_layer_->EnqueueCommand(
        ReadRssiBuilder::Create(connection_handle),
        handler_->BindOnceOn(this, &impl::on_read_rssi_complete, address));

    rssi_trackers[address].alarm->Schedule(
        common::BindOnce(&impl::read_rssi_regularly, common::Unretained(this), address, frequency),
        std::chrono::milliseconds(rssi_trackers[address].frequency));
  }

  void on_read_remote_transmit_power_level_status(Address address, CommandStatusView view) {
    auto status_view = LeReadRemoteTransmitPowerLevelStatusView::Create(view);
    if (!status_view.IsValid()) {
      LOG_WARN("Invalid LeReadRemoteTransmitPowerLevelStatus event");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    } else if (status_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(status_view.GetStatus());
      LOG_WARN(
          "Received LeReadRemoteTransmitPowerLevelStatus with error code %s", error_code.c_str());
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    }
  }

  void on_transmit_power_reporting(LeMetaEventView event) {
    auto event_view = LeTransmitPowerReportingView::Create(event);
    if (!event_view.IsValid()) {
      LOG_WARN("Dropping invalid LeTransmitPowerReporting event");
      return;
    }

    if (event_view.GetReason() == ReportingReason::LOCAL_TRANSMIT_POWER_CHANGED) {
      LOG_WARN("Dropping local LeTransmitPowerReporting event");
      return;
    }

    Address address = Address::kEmpty;
    for (auto& rssi_tracker : rssi_trackers) {
      if (rssi_tracker.second.handle == event_view.GetConnectionHandle()) {
        address = rssi_tracker.first;
      }
    }

    if (address.IsEmpty()) {
      LOG_WARN("Can't find rssi tracker for connection %d", event_view.GetConnectionHandle());
      return;
    }

    auto status = event_view.GetStatus();
    if (status != ErrorCode::SUCCESS) {
      LOG_WARN(
          "Received LeTransmitPowerReporting with error code %s", ErrorCodeText(status).c_str());
    } else {
      rssi_trackers[address].remote_tx_power = event_view.GetTransmitPowerLevel();
    }

    if (event_view.GetReason() == ReportingReason::READ_COMMAND_COMPLETE &&
        !rssi_trackers[address].started) {
      if (status == ErrorCode::SUCCESS) {
        hci_layer_->EnqueueCommand(
            LeSetTransmitPowerReportingEnableBuilder::Create(
                event_view.GetConnectionHandle(), 0x00, 0x01),
            handler_->BindOnceOn(
                this, &impl::on_set_transmit_power_reporting_enable_complete, address));
      } else {
        LOG_WARN("Read remote transmit power level fail");
        distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
            address, REASON_INTERNAL_ERROR, METHOD_RSSI);
        rssi_trackers.erase(address);
      }
    }
  }

  void on_set_transmit_power_reporting_enable_complete(Address address, CommandCompleteView view) {
    auto complete_view = LeSetTransmitPowerReportingEnableCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      LOG_WARN("Invalid LeSetTransmitPowerReportingEnableComplete event");
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    } else if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      std::string error_code = ErrorCodeText(complete_view.GetStatus());
      LOG_WARN(
          "Received LeSetTransmitPowerReportingEnableComplete with error code %s",
          error_code.c_str());
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
      return;
    }

    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      LOG_WARN("Can't find rssi tracker for %s", ADDRESS_TO_LOGGABLE_CSTR(address));
      distance_measurement_callbacks_->OnDistanceMeasurementStartFail(
          address, REASON_INTERNAL_ERROR, METHOD_RSSI);
      rssi_trackers.erase(address);
    } else {
      LOG_INFO("Track rssi for address %s", ADDRESS_TO_LOGGABLE_CSTR(address));
      rssi_trackers[address].started = true;
      distance_measurement_callbacks_->OnDistanceMeasurementStarted(address, METHOD_RSSI);
      read_rssi_regularly(address, rssi_trackers[address].frequency);
    }
  }

  void on_read_rssi_complete(Address address, CommandCompleteView view) {
    auto complete_view = ReadRssiCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      LOG_WARN("Dropping invalid read RSSI complete event ");
      return;
    }
    if (rssi_trackers.find(address) == rssi_trackers.end()) {
      LOG_WARN("Can't find rssi tracker for %s", ADDRESS_TO_LOGGABLE_CSTR(address));
      return;
    }
    double remote_tx_power = (int8_t)rssi_trackers[address].remote_tx_power;
    int8_t rssi = complete_view.GetRssi();
    double pow_value = (remote_tx_power - rssi - kRSSIDropOffAt1M) / 20.0;
    double distance = pow(10.0, pow_value);
    distance_measurement_callbacks_->OnDistanceMeasurementResult(
        address,
        distance * 100,
        distance * 100,
        -1,
        -1,
        -1,
        -1,
        DistanceMeasurementMethod::METHOD_RSSI);
  }

  template <class View>
  void check_status(CommandCompleteView view) {
    auto status_view = View::Create(view);
    if (!status_view.IsValid()) {
      LOG_WARN("Get invalid command complete event");
    } else if (status_view.GetStatus() != ErrorCode::SUCCESS) {
      LOG_INFO(
          "Got a Command complete %s, status %s",
          OpCodeText(view.GetCommandOpCode()).c_str(),
          ErrorCodeText(status_view.GetStatus()).c_str());
    }
  }

  struct RSSITracker {
    uint16_t handle;
    uint16_t frequency;
    uint8_t remote_tx_power;
    bool started;
    std::unique_ptr<os::Alarm> alarm;
  };

  os::Handler* handler_;
  hci::HciLayer* hci_layer_;
  hci::AclManager* acl_manager_;
  std::unordered_map<Address, RSSITracker> rssi_trackers;
  DistanceMeasurementCallbacks* distance_measurement_callbacks_;
};

DistanceMeasurementManager::DistanceMeasurementManager() {
  pimpl_ = std::make_unique<impl>();
}

DistanceMeasurementManager::~DistanceMeasurementManager() = default;

void DistanceMeasurementManager::ListDependencies(ModuleList* list) const {
  list->add<hci::HciLayer>();
  list->add<hci::AclManager>();
}

void DistanceMeasurementManager::Start() {
  pimpl_->start(GetHandler(), GetDependency<hci::HciLayer>(), GetDependency<AclManager>());
}

void DistanceMeasurementManager::Stop() {
  pimpl_->stop();
}

std::string DistanceMeasurementManager::ToString() const {
  return "Distance Measurement Manager";
}

void DistanceMeasurementManager::RegisterDistanceMeasurementCallbacks(
    DistanceMeasurementCallbacks* callbacks) {
  CallOn(pimpl_.get(), &impl::register_distance_measurement_callbacks, callbacks);
}

void DistanceMeasurementManager::StartDistanceMeasurement(
    const Address& address, uint16_t frequency, DistanceMeasurementMethod method) {
  CallOn(pimpl_.get(), &impl::start_distance_measurement, address, frequency, method);
}

void DistanceMeasurementManager::StopDistanceMeasurement(
    const Address& address, DistanceMeasurementMethod method) {
  CallOn(pimpl_.get(), &impl::stop_distance_measurement, address, method);
}

}  // namespace hci
}  // namespace bluetooth
