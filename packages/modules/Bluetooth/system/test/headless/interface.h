

#pragma once

#include <base/strings/stringprintf.h>

#include <list>
#include <map>
#include <string>

#include "include/hardware/bluetooth.h"
#include "test/headless/log.h"
#include "types/raw_address.h"

struct callback_data_t {
  std::string Name() const { return std::string(name_); }
  uint64_t TimestampInMs() const {
    return static_cast<uint64_t>(timestamp_ms_);
  }

 protected:
  callback_data_t(const char* name)
      : name_(name), timestamp_ms_(GetTimestampMs()) {}
  virtual ~callback_data_t() = default;

 private:
  const char* name_;
  const long long timestamp_ms_;
};

struct callback_params_t : public callback_data_t {
 protected:
  callback_params_t(const char* name) : callback_data_t(name) {}
  virtual ~callback_params_t() = default;
  virtual std::string ToString() const = 0;
};

struct acl_state_changed_params_t : public callback_params_t {
  acl_state_changed_params_t(bt_status_t status, RawAddress remote_bd_addr,
                             bt_acl_state_t state, int transport_link_type,
                             bt_hci_error_code_t hci_reason,
                             bt_conn_direction_t direction, uint16_t acl_handle)
      : callback_params_t("acl_state_changed"),
        status(status),
        remote_bd_addr(remote_bd_addr),
        state(state),
        transport_link_type(transport_link_type),
        hci_reason(hci_reason),
        direction(direction),
        acl_handle(acl_handle) {}
  acl_state_changed_params_t(const acl_state_changed_params_t& params) =
      default;
  virtual ~acl_state_changed_params_t() {}

  bt_status_t status;
  RawAddress remote_bd_addr;
  bt_acl_state_t state;
  int transport_link_type;
  bt_hci_error_code_t hci_reason;
  bt_conn_direction_t direction;
  uint16_t acl_handle;

  std::string ToString() const override {
    return base::StringPrintf(
        "status:%s remote_bd_addr:%s state:%s transport:%s reason:%s "
        "direction:%d handle:%d",
        bt_status_text(status).c_str(), remote_bd_addr.ToString().c_str(),
        (state == BT_ACL_STATE_CONNECTED) ? "CONNECTED" : "DISCONNECTED",
        bt_transport_text(static_cast<const tBT_TRANSPORT>(transport_link_type))
            .c_str(),
        bt_status_text(static_cast<const bt_status_t>(hci_reason)).c_str(),
        direction, acl_handle);
  }
};

struct discovery_state_changed_params_t : public callback_params_t {
  discovery_state_changed_params_t(bt_discovery_state_t state)
      : callback_params_t("discovery_state_changed"), state(state) {}
  discovery_state_changed_params_t(
      const discovery_state_changed_params_t& params) = default;

  virtual ~discovery_state_changed_params_t() {}

  bt_discovery_state_t state;
  std::string ToString() const override {
    return base::StringPrintf(
        "state:%s", (state == BT_DISCOVERY_STOPPED) ? "STOPPED" : "STARTED");
  }
};

struct remote_device_properties_params_t : public callback_params_t {
  remote_device_properties_params_t(bt_status_t status, RawAddress bd_addr,
                                    int num_properties,
                                    bt_property_t* properties)
      : callback_params_t("remote_device_properties"),
        status(status),
        bd_addr(bd_addr),
        num_properties(num_properties),
        properties(properties) {}
  remote_device_properties_params_t(
      const remote_device_properties_params_t& params) = default;

  virtual ~remote_device_properties_params_t() {}
  bt_status_t status;
  RawAddress bd_addr;
  int num_properties;
  bt_property_t* properties;
  std::string ToString() const override {
    return base::StringPrintf(
        "status:%s bd_addr:%s num_properties:%d properties:%p",
        bt_status_text(status).c_str(), bd_addr.ToString().c_str(),
        num_properties, properties);
  }
};

using callback_function_t = void (*)(callback_data_t*);

void headless_add_callback(const std::string interface_name,
                           callback_function_t function);
void headless_remove_callback(const std::string interface_name,
                              callback_function_t function);
