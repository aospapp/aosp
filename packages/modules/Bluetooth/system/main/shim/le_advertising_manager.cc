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

#define LOG_TAG "bt_shim_advertiser"

#include "le_advertising_manager.h"

#include <base/logging.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>

#include <vector>

#include "btif/include/btif_common.h"
#include "gd/common/init_flags.h"
#include "gd/hci/acl_manager.h"
#include "gd/hci/controller.h"
#include "gd/hci/le_advertising_manager.h"
#include "gd/packet/packet_view.h"
#include "gd/storage/storage_module.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "stack/include/ble_advertiser.h"
#include "stack/include/btm_api.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/btu.h"  // do_in_main_thread
#include "types/raw_address.h"

using bluetooth::hci::Address;
using bluetooth::hci::AddressType;
using bluetooth::hci::AdvertiserAddressType;
using bluetooth::hci::ErrorCode;
using bluetooth::hci::GapData;
using bluetooth::hci::OwnAddressType;
using std::vector;

namespace {
constexpr char kBtmLogTag[] = "ADV";
}

class BleAdvertiserInterfaceImpl : public BleAdvertiserInterface,
                                   public bluetooth::hci::AdvertisingCallback {
 public:
  ~BleAdvertiserInterfaceImpl() override{};

  void Init() {
    // Register callback
    bluetooth::shim::GetAdvertising()->RegisterAdvertisingCallback(this);
  }

  void RegisterAdvertiser(IdStatusCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";

    bluetooth::shim::GetAdvertising()->RegisterAdvertiser(
        bluetooth::shim::GetGdShimHandler()->BindOnce(
            [](IdStatusCallback cb, uint8_t id, uint8_t status) {
              do_in_main_thread(
                  FROM_HERE,
                  base::BindOnce([](IdStatusCallback cb, uint8_t id,
                                    uint8_t status) { cb.Run(id, status); },
                                 cb, id, status));
            },
            cb));
  }

  void Unregister(uint8_t advertiser_id) override {
    LOG(INFO) << __func__ << " in shim layer";
    bluetooth::shim::GetAdvertising()->RemoveAdvertiser(advertiser_id);
    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert stopped",
                   base::StringPrintf("advert_id:%d", advertiser_id));
  }

  void GetOwnAddress(uint8_t advertiser_id, GetAddressCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";
    address_callbacks_[advertiser_id] = jni_thread_wrapper(FROM_HERE, cb);
    bluetooth::shim::GetAdvertising()->GetOwnAddress(advertiser_id);
  }

  void SetParameters(uint8_t advertiser_id, AdvertiseParameters params,
                     ParametersCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";
    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    bluetooth::shim::GetAdvertising()->SetParameters(advertiser_id, config);
  }

  void SetData(int advertiser_id, bool set_scan_rsp, vector<uint8_t> data,
               StatusCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";

    size_t offset = 0;
    std::vector<GapData> advertising_data = {};

    while (offset < data.size()) {
      GapData gap_data;
      uint8_t len = data[offset];
      auto begin = data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      advertising_data.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    bluetooth::shim::GetAdvertising()->SetData(advertiser_id, set_scan_rsp,
                                               advertising_data);
  }

  void Enable(uint8_t advertiser_id, bool enable, StatusCallback cb,
              uint16_t duration, uint8_t maxExtAdvEvents,
              StatusCallback timeout_cb) override {
    LOG(INFO) << __func__ << " in shim layer";
    bluetooth::shim::GetAdvertising()->EnableAdvertiser(
        advertiser_id, enable, duration, maxExtAdvEvents);
  }

  // nobody use this function
  void StartAdvertising(uint8_t advertiser_id, StatusCallback cb,
                        AdvertiseParameters params,
                        std::vector<uint8_t> advertise_data,
                        std::vector<uint8_t> scan_response_data, int timeout_s,
                        MultiAdvCb timeout_cb) override {
    LOG(INFO) << __func__ << " in shim layer";

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);

    size_t offset = 0;
    while (offset < advertise_data.size()) {
      GapData gap_data;
      uint8_t len = advertise_data[offset];
      auto begin = advertise_data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      config.advertisement.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    offset = 0;
    while (offset < scan_response_data.size()) {
      GapData gap_data;
      uint8_t len = scan_response_data[offset];
      auto begin = scan_response_data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      config.scan_response.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    bluetooth::shim::GetAdvertising()->StartAdvertising(
        advertiser_id, config, timeout_s * 100, cb, timeout_cb, scan_callback,
        set_terminated_callback, bluetooth::shim::GetGdShimHandler());
  }

  void StartAdvertisingSet(int reg_id, IdTxPowerStatusCallback register_cb,
                           AdvertiseParameters params,
                           std::vector<uint8_t> advertise_data,
                           std::vector<uint8_t> scan_response_data,
                           PeriodicAdvertisingParameters periodic_params,
                           std::vector<uint8_t> periodic_data,
                           uint16_t duration, uint8_t maxExtAdvEvents,
                           IdStatusCallback timeout_cb) {
    LOG(INFO) << __func__ << " in shim layer";

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    parse_periodic_advertising_parameter(config.periodic_advertising_parameters,
                                         periodic_params);

    size_t offset = 0;
    while (offset < advertise_data.size()) {
      GapData gap_data;
      uint8_t len = advertise_data[offset];
      auto begin = advertise_data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      config.advertisement.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    offset = 0;
    while (offset < scan_response_data.size()) {
      GapData gap_data;
      uint8_t len = scan_response_data[offset];
      auto begin = scan_response_data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      config.scan_response.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    offset = 0;
    while (offset < periodic_data.size()) {
      GapData gap_data;
      uint8_t len = periodic_data[offset];
      auto begin = periodic_data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      config.periodic_data.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    bluetooth::shim::GetAdvertising()->ExtendedCreateAdvertiser(
        reg_id, config, scan_callback, set_terminated_callback, duration,
        maxExtAdvEvents, bluetooth::shim::GetGdShimHandler());

    LOG(INFO) << "create advertising set, reg_id:" << reg_id;

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert started",
                   base::StringPrintf("reg_id:%d", reg_id));

    return;
  }

  void SetPeriodicAdvertisingParameters(
      int advertiser_id, PeriodicAdvertisingParameters periodic_params,
      StatusCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";
    bluetooth::hci::PeriodicAdvertisingParameters parameters;
    parameters.max_interval = periodic_params.max_interval;
    parameters.min_interval = periodic_params.min_interval;
    parameters.properties = periodic_params.periodic_advertising_properties;
    bluetooth::shim::GetAdvertising()->SetPeriodicParameters(advertiser_id,
                                                             parameters);
  }

  void SetPeriodicAdvertisingData(int advertiser_id, std::vector<uint8_t> data,
                                  StatusCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";

    size_t offset = 0;
    std::vector<GapData> advertising_data = {};

    while (offset < data.size()) {
      GapData gap_data;
      uint8_t len = data[offset];
      auto begin = data.begin() + offset;
      auto end = begin + len + 1;  // 1 byte for len
      auto data_copy = std::make_shared<std::vector<uint8_t>>(begin, end);
      bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian> packet(
          data_copy);
      GapData::Parse(&gap_data, packet.begin());
      advertising_data.push_back(gap_data);
      offset += len + 1;  // 1 byte for len
    }

    bluetooth::shim::GetAdvertising()->SetPeriodicData(advertiser_id,
                                                       advertising_data);
  }

  void SetPeriodicAdvertisingEnable(int advertiser_id, bool enable,
                                    bool include_adi,
                                    StatusCallback cb) override {
    LOG(INFO) << __func__ << " in shim layer";
    bluetooth::shim::GetAdvertising()->EnablePeriodicAdvertising(
        advertiser_id, enable, include_adi);
  }

  void RegisterCallbacks(AdvertisingCallbacks* callbacks) {
    advertising_callbacks_ = callbacks;
  }

  void on_scan(Address address, AddressType address_type) {
    LOG(INFO) << __func__ << " in shim layer";
  }

  void on_set_terminated(ErrorCode error_code, uint8_t, uint8_t) {
    LOG(INFO) << __func__ << " in shim layer";
  }

  const bluetooth::common::Callback<void(Address, AddressType)> scan_callback =
      bluetooth::common::Bind(&BleAdvertiserInterfaceImpl::on_scan,
                              bluetooth::common::Unretained(this));

  const bluetooth::common::Callback<void(ErrorCode, uint8_t, uint8_t)>
      set_terminated_callback = bluetooth::common::Bind(
          &BleAdvertiserInterfaceImpl::on_set_terminated,
          bluetooth::common::Unretained(this));

  // AdvertisingCallback
  void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id,
                               int8_t tx_power,
                               AdvertisingStatus status) override {
    do_in_jni_thread(
        FROM_HERE, base::Bind(&AdvertisingCallbacks::OnAdvertisingSetStarted,
                              base::Unretained(advertising_callbacks_), reg_id,
                              advertiser_id, tx_power, status));
  }

  void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                            uint8_t status) {
    do_in_jni_thread(FROM_HERE,
                     base::Bind(&AdvertisingCallbacks::OnAdvertisingEnabled,
                                base::Unretained(advertising_callbacks_),
                                advertiser_id, enable, status));
  }

  void OnAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(FROM_HERE,
                     base::Bind(&AdvertisingCallbacks::OnAdvertisingDataSet,
                                base::Unretained(advertising_callbacks_),
                                advertiser_id, status));
  }
  void OnScanResponseDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(FROM_HERE,
                     base::Bind(&AdvertisingCallbacks::OnScanResponseDataSet,
                                base::Unretained(advertising_callbacks_),
                                advertiser_id, status));
  }

  void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t tx_power,
                                      uint8_t status) {
    do_in_jni_thread(
        FROM_HERE,
        base::Bind(&AdvertisingCallbacks::OnAdvertisingParametersUpdated,
                   base::Unretained(advertising_callbacks_), advertiser_id,
                   tx_power, status));
  }

  void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id,
                                              uint8_t status) {
    do_in_jni_thread(
        FROM_HERE,
        base::Bind(
            &AdvertisingCallbacks::OnPeriodicAdvertisingParametersUpdated,
            base::Unretained(advertising_callbacks_), advertiser_id, status));
  }

  void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(
        FROM_HERE,
        base::Bind(&AdvertisingCallbacks::OnPeriodicAdvertisingDataSet,
                   base::Unretained(advertising_callbacks_), advertiser_id,
                   status));
  }

  void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                    uint8_t status) {
    do_in_jni_thread(
        FROM_HERE,
        base::Bind(&AdvertisingCallbacks::OnPeriodicAdvertisingEnabled,
                   base::Unretained(advertising_callbacks_), advertiser_id,
                   enable, status));
  }

  void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type,
                        bluetooth::hci::Address address) {
    RawAddress raw_address = bluetooth::ToRawAddress(address);
    if (address_callbacks_.find(advertiser_id) != address_callbacks_.end()) {
      address_callbacks_[advertiser_id].Run(address_type, raw_address);
      address_callbacks_.erase(advertiser_id);
      return;
    }
    do_in_jni_thread(FROM_HERE,
                     base::Bind(&AdvertisingCallbacks::OnOwnAddressRead,
                                base::Unretained(advertising_callbacks_),
                                advertiser_id, address_type, raw_address));
  }

  AdvertisingCallbacks* advertising_callbacks_;

 private:
  void parse_parameter(bluetooth::hci::AdvertisingConfig& config,
                       AdvertiseParameters params) {
    config.connectable = params.advertising_event_properties & 0x01;
    config.scannable = params.advertising_event_properties & 0x02;
    config.discoverable = params.advertising_event_properties & 0x04;
    config.legacy_pdus = params.advertising_event_properties & 0x10;
    config.anonymous = params.advertising_event_properties & 0x20;
    config.include_tx_power = params.advertising_event_properties & 0x40;
    config.interval_min = params.min_interval;
    config.interval_max = params.max_interval;
    config.channel_map = params.channel_map;
    config.tx_power = params.tx_power;
    config.use_le_coded_phy = params.primary_advertising_phy == 0x03;
    config.secondary_advertising_phy =
        static_cast<bluetooth::hci::SecondaryPhyType>(
            params.secondary_advertising_phy);
    config.enable_scan_request_notifications =
        static_cast<bluetooth::hci::Enable>(
            params.scan_request_notification_enable);
    // Matching the ADDRESS_TYPE_* enums from Java
    switch (params.own_address_type) {
      case -1:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 0:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::PUBLIC;
        break;
      case 1:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 2:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::NONRESOLVABLE_RANDOM;
        break;
      default:
        LOG_ERROR("Received unexpected address type: %d",
                  params.own_address_type);
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
    }
  }

  void parse_periodic_advertising_parameter(
      bluetooth::hci::PeriodicAdvertisingParameters& config,
      PeriodicAdvertisingParameters periodic_params) {
    config.max_interval = periodic_params.max_interval;
    config.min_interval = periodic_params.min_interval;
    config.properties = periodic_params.periodic_advertising_properties;
    config.enable = periodic_params.enable;
    config.include_adi = periodic_params.include_adi;
  }

  std::map<uint8_t, GetAddressCallback> address_callbacks_;
};

BleAdvertiserInterfaceImpl* bt_le_advertiser_instance = nullptr;

BleAdvertiserInterface* bluetooth::shim::get_ble_advertiser_instance() {
  if (bt_le_advertiser_instance == nullptr) {
    bt_le_advertiser_instance = new BleAdvertiserInterfaceImpl();
  }
  return bt_le_advertiser_instance;
};

void bluetooth::shim::init_advertising_manager() {
  static_cast<BleAdvertiserInterfaceImpl*>(
      bluetooth::shim::get_ble_advertiser_instance())
      ->Init();
}
