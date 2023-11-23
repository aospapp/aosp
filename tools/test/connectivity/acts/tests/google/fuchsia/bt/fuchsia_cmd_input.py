#!/usr/bin/env python3
#
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
"""
Python script for wrappers to various libraries.

Class CmdInput inherts from the cmd library.

Functions that start with "do_" have a method
signature that doesn't match the actual command
line command and that is intended. This is so the
"help" command knows what to display (in this case
the documentation of the command itself).

For example:
Looking at the function "do_tool_set_target_device_name"
has the inputs self and line which is expected of this type
of method signature. When the "help" command is done on the
method name you get the function documentation as such:

(Cmd) help tool_set_target_device_name

        Description: Reset the target device name.
        Input(s):
            device_name: Required. The advertising name to connect to.
        Usage: tool_set_target_device_name new_target_device name
          Examples:
            tool_set_target_device_name le_watch

This is all to say this documentation pattern is expected.

"""

import acts.test_utils.bt.gatt_test_database as gatt_test_database

import cmd
import pprint
import time
"""Various Global Strings"""
CMD_LOG = "CMD {} result: {}"
FAILURE = "CMD {} threw exception: {}"
BASIC_ADV_NAME = "fs_test"


class CmdInput(cmd.Cmd):
    ble_advertise_interval = 1000
    target_device_name = ""
    le_ids = []
    unique_mac_addr_id = None

    def setup_vars(self, fuchsia_devices, target_device_name, log):
        self.pri_dut = fuchsia_devices[0]
        if len(fuchsia_devices) > 1:
            self.sec_dut = fuchsia_devices[1]
        self.target_device_name = target_device_name
        self.log = log

    def emptyline(self):
        pass

    def do_EOF(self, line):
        "End Script"
        return True

    """ Useful Helper functions and cmd line tooling """

    def _find_unique_id(self):
        scan_time_ms = 100000
        scan_filter = {"name_substring": self.target_device_name}
        scan_count = 1
        self.unique_mac_addr_id = None
        self.pri_dut.gattc_lib.bleStartBleScan(scan_filter)
        for i in range(100):
            time.sleep(.5)
            scan_res = self.pri_dut.gattc_lib.bleGetDiscoveredDevices()[
                'result']
            for device in scan_res:
                name, did, connectable = device["name"], device["id"], device[
                    "connectable"]
                if (name):
                    self.log.info(
                        "Discovered device with name, id: {}, {}".format(
                            name, did))
                if (self.target_device_name in name):
                    self.unique_mac_addr_id = did
                    self.log.info(
                        "Successfully found device: name, id: {}, {}".format(
                            name, did))
                    break
            if self.unique_mac_addr_id:
                break
        self.pri_dut.gattc_lib.bleStopBleScan()

    def do_tool_refesh_unique_id(self, line):
        """
        Description: Refresh command line tool mac unique id.
        Usage:
          Examples:
            tool_refresh_unique_id
        """
        try:
            self._find_unique_id()
        except Exception as err:
            self.log.error(
                "Failed to scan or find scan result: {}".format(err))

    def do_tool_set_target_device_name(self, line):
        """
        Description: Reset the target device name.
        Input(s):
            device_name: Required. The advertising name to connect to.
        Usage: tool_set_target_device_name new_target_device name
          Examples:
            tool_set_target_device_name le_watch
        """
        self.log.info("Setting target_device_name to: {}".format(line))
        self.target_device_name = line

    """Begin BLE advertise wrappers"""

    def do_ble_start_generic_connectable_advertisement(self, line):
        """
        Description: Start a connectable LE advertisement
        Usage: ble_start_generic_connectable_advertisement
        """
        cmd = "Start a connectable LE advertisement"
        try:
            adv_data = {"name": BASIC_ADV_NAME}
            self.pri_dut.ble_lib.bleStartBleAdvertising(
                adv_data, self.ble_advertise_interval)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_ble_start_generic_nonconnectable_advertisement(self, line):
        """
        Description: Start a non-connectable LE advertisement
        Usage: ble_start_generic_nonconnectable_advertisement
        """
        cmd = "Start a nonconnectable LE advertisement"
        try:
            adv_data = {"name": BASIC_ADV_NAME}
            self.pri_dut.ble_lib.bleStartBleAdvertising(
                adv_data, self.ble_advertise_interval, False)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_ble_stop_advertisement(self, line):
        """
        Description: Stop a BLE advertisement.
        Usage: ble_stop_advertisement
        """
        cmd = "Stop a connectable LE advertisement"
        try:
            self.pri_dut.ble_lib.bleStopBleAdvertising()
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    """End BLE advertise wrappers"""
    """Begin GATT client wrappers"""

    def complete_gattc_connect_by_id(self, text, line, begidx, endidx):
        if not text:
            completions = list(self.le_ids)[:]
        else:
            completions = [s for s in self.le_ids if s.startswith(text)]
        return completions

    def do_gattc_connect_by_id(self, line):
        """
        Description: Connect to a LE peripheral.
        Input(s):
            device_id: Required. The unique device ID from Fuchsia
                discovered devices.
        Usage:
          Examples:
            gattc_connect device_id
        """
        cmd = "Connect to a LE peripheral by input ID."
        try:

            connection_status = self.pri_dut.gattc_lib.bleConnectToPeripheral(
                line)
            self.log.info("Connection status: {}".format(
                pprint.pformat(connection_status)))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_connect(self, line):
        """
        Description: Connect to a LE peripheral.
        Optional input: device_name
        Input(s):
            device_name: Optional. The peripheral ID to connect to.
        Usage:
          Examples:
            gattc_connect
            gattc_connect eddystone_123
        """
        cmd = "Connect to a LE peripheral."
        try:
            if len(line) > 0:
                self.target_device_name = line
                self.unique_mac_addr_id = None
            if not self.unique_mac_addr_id:
                try:
                    self._find_unique_id()
                except Exception as err:
                    self.log.info("Failed to scan or find device.")
                    return
            connection_status = self.pri_dut.gattc_lib.bleConnectToPeripheral(
                self.unique_mac_addr_id)
            self.log.info("Connection status: {}".format(
                pprint.pformat(connection_status)))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_connect_disconnect_iterations(self, line):
        """
        Description: Connect then disconnect to a LE peripheral multiple times.
        Input(s):
            iterations: Required. The number of iterations to run.
        Usage:
          Examples:
            gattc_connect_disconnect_iterations 10
        """
        cmd = "Connect to a LE peripheral."
        try:
            if not self.unique_mac_addr_id:
                try:
                    self._find_unique_id()
                except Exception as err:
                    self.log.info("Failed to scan or find device.")
                    return
            for i in range(int(line)):
                self.log.info("Running iteration {}".format(i + 1))
                connection_status = self.pri_dut.gattc_lib.bleConnectToPeripheral(
                    self.unique_mac_addr_id)
                self.log.info("Connection status: {}".format(
                    pprint.pformat(connection_status)))
                time.sleep(4)
                disc_status = self.pri_dut.gattc_lib.bleDisconnectPeripheral(
                    self.unique_mac_addr_id)
                self.log.info("Disconnect status: {}".format(disc_status))
                time.sleep(3)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_disconnect(self, line):
        """
        Description: Disconnect from LE peripheral.
        Assumptions: Already connected to a peripheral.
        Usage:
          Examples:
            gattc_disconnect
        """
        cmd = "Disconenct from LE peripheral."
        try:
            disconnect_status = self.pri_dut.gattc_lib.bleDisconnectPeripheral(
                self.unique_mac_addr_id)
            self.log.info("Disconnect status: {}".format(disconnect_status))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_list_services(self, line):
        """
        Description: List services from LE peripheral.
        Assumptions: Already connected to a peripheral.
        Usage:
          Examples:
            gattc_list_services
        """
        cmd = "List services from LE peripheral."
        try:
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            self.log.info("Discovered Services: \n{}".format(
                pprint.pformat(services)))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_connect_to_service(self, line):
        """
        Description: Connect to Peripheral GATT server service.
        Assumptions: Already connected to peripheral.
        Input(s):
            service_id: Required. The service id reference on the GATT server.
        Usage:
          Examples:
            gattc_connect_to_service service_id
        """
        cmd = "GATT client connect to GATT server service."
        try:
            self.pri_dut.gattc_lib.connectToService(self.unique_mac_addr_id,
                                                    int(line))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_discover_characteristics(self, line):
        """
        Description: Discover characteristics from a connected service.
        Assumptions: Already connected to a GATT server service.
        Usage:
          Examples:
            gattc_discover_characteristics
        """
        cmd = "Discover and list characteristics from a GATT server."
        try:
            chars = self.pri_dut.gattc_lib.discoverCharacteristics()
            self.log.info("Discovered chars:\n{}".format(
                pprint.pformat(chars)))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_notify_all_chars(self, line):
        """
        Description: Enable all notifications on all Characteristics on
            a GATT server.
        Assumptions: Basic GATT connection made.
        Usage:
          Examples:
            gattc_notify_all_chars
        """
        cmd = "Read all characteristics from the GATT service."
        try:
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading chars in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    # quick char filter for apple-4 test... remove later
                    print("found uuid {}".format(char_uuid))
                    try:
                        self.pri_dut.gattc_lib.enableNotifyCharacteristic(
                            char_id)
                    except Exception as err:
                        print("error enabling notification")
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_all_chars(self, line):
        """
        Description: Read all Characteristic values from a GATT server across
            all services.
        Assumptions: Basic GATT connection made.
        Usage:
          Examples:
            gattc_read_all_chars
        """
        cmd = "Read all characteristics from the GATT service."
        try:
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading chars in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    try:
                        read_val =  \
                            self.pri_dut.gattc_lib.readCharacteristicById(
                                char_id)
                        print("  Characteristic uuid / Value: {} / {}".format(
                            char_uuid, read_val['result']))
                        str_value = ""
                        for val in read_val['result']:
                            str_value += chr(val)
                        print("    str val: {}".format(str_value))
                    except Exception as err:
                        print(err)
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_all_desc(self, line):
        """
        Description: Read all Descriptors values from a GATT server across
            all services.
        Assumptions: Basic GATT connection made.
        Usage:
          Examples:
            gattc_read_all_chars
        """
        cmd = "Read all descriptors from the GATT service."
        try:
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading descs in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    descriptors = char['descriptors']
                    print("  Reading descs in char uuid: {}".format(char_uuid))
                    for desc in descriptors:
                        desc_id = desc["id"]
                        desc_uuid = desc["uuid_type"]
                    try:
                        read_val = self.pri_dut.gattc_lib.readDescriptorById(
                            desc_id)
                        print("    Descriptor uuid / Value: {} / {}".format(
                            desc_uuid, read_val['result']))
                    except Exception as err:
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_all_desc(self, line):
        """
        Description: Write a value to all Descriptors on the GATT server.
        Assumptions: Basic GATT connection made.
        Input(s):
            offset: Required. The offset to start writing to.
            size: Required. The size of bytes to write (value will be generated).
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_all_desc 0 100
            gattc_write_all_desc 10 2
        """
        cmd = "Read all descriptors from the GATT service."
        try:
            args = line.split()
            if len(args) != 2:
                self.log.info("2 Arguments required: [Offset] [Size]")
                return
            offset = int(args[0])
            size = args[1]
            write_value = []
            for i in range(int(size)):
                write_value.append(i % 256)
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Writing descs in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    descriptors = char['descriptors']
                    print("  Reading descs in char uuid: {}".format(char_uuid))
                    for desc in descriptors:
                        desc_id = desc["id"]
                        desc_uuid = desc["uuid_type"]
                    try:
                        write_val = self.pri_dut.gattc_lib.writeDescriptorById(
                            desc_id, offset, write_value)
                        print("    Descriptor uuid / Result: {} / {}".format(
                            desc_uuid, write_val['result']))
                    except Exception as err:
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_all_long_desc(self, line):
        """
        Description: Read all long Characteristic Descriptors
        Assumptions: Basic GATT connection made.
        Input(s):
            offset: Required. The offset to start reading from.
            max_bytes: Required. The max size of bytes to return.
        Usage:
          Examples:
            gattc_read_all_long_desc 0 100
            gattc_read_all_long_desc 10 20
        """
        cmd = "Read all long descriptors from the GATT service."
        try:
            args = line.split()
            if len(args) != 2:
                self.log.info("2 Arguments required: [Offset] [Size]")
                return
            offset = int(args[0])
            max_bytes = int(args[1])
            services = self.pri_dut.ble_lib.bleListServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading descs in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    descriptors = char['descriptors']
                    print("  Reading descs in char uuid: {}".format(char_uuid))
                    for desc in descriptors:
                        desc_id = desc["id"]
                        desc_uuid = desc["uuid_type"]
                    try:
                        read_val = self.pri_dut.gattc_lib.readLongDescriptorById(
                            desc_id, offset, max_bytes)
                        print("    Descriptor uuid / Result: {} / {}".format(
                            desc_uuid, read_val['result']))
                    except Exception as err:
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_all_long_char(self, line):
        """
        Description: Read all long Characteristic
        Assumptions: Basic GATT connection made.
        Input(s):
            offset: Required. The offset to start reading from.
            max_bytes: Required. The max size of bytes to return.
        Usage:
          Examples:
            gattc_read_all_long_char 0 100
            gattc_read_all_long_char 10 20
        """
        cmd = "Read all long Characteristics from the GATT service."
        try:
            args = line.split()
            if len(args) != 2:
                self.log.info("2 Arguments required: [Offset] [Size]")
                return
            offset = int(args[0])
            max_bytes = int(args[1])
            services = self.pri_dut.ble_lib.bleListServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading chars in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    try:
                        read_val = self.pri_dut.gattc_lib.readLongCharacteristicById(
                            char_id, offset, max_bytes)
                        print("    Char uuid / Result: {} / {}".format(
                            char_uuid, read_val['result']))
                    except Exception as err:
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_all_chars(self, line):
        """
        Description: Write all characteristic values from a GATT server across
            all services.
        Assumptions: Basic GATT connection made.
        Input(s):
            offset: Required. The offset to start writing on.
            size: The write value size (value will be generated)
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_all_chars 0 10
            gattc_write_all_chars 10 1
        """
        cmd = "Read all characteristics from the GATT service."
        try:
            args = line.split()
            if len(args) != 2:
                self.log.info("2 Arguments required: [Offset] [Size]")
                return
            offset = int(args[0])
            size = int(args[1])
            write_value = []
            for i in range(size):
                write_value.append(i % 256)
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Writing chars in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    try:
                        write_result = self.pri_dut.gattc_lib.writeCharById(
                            char_id, offset, write_value)
                        print("  Characteristic uuid write result: {} / {}".
                              format(char_uuid, write_result['result']))
                    except Exception as err:
                        print("error writing char {}".format(err))
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_all_chars_without_response(self, line):
        """
        Description: Write all characteristic values from a GATT server across
            all services.
        Assumptions: Basic GATT connection made.
        Input(s):
            size: The write value size (value will be generated).
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_all_chars_without_response 100
        """
        cmd = "Read all characteristics from the GATT service."
        try:
            args = line.split()
            if len(args) != 1:
                self.log.info("1 Arguments required: [Size]")
                return
            size = int(args[0])
            write_value = []
            for i in range(size):
                write_value.append(i % 256)
            services = self.pri_dut.gattc_lib.listServices(
                self.unique_mac_addr_id)
            for service in services['result']:
                service_id = service['id']
                service_uuid = service['uuid_type']
                self.pri_dut.gattc_lib.connectToService(
                    self.unique_mac_addr_id, service_id)
                chars = self.pri_dut.gattc_lib.discoverCharacteristics()
                print("Reading chars in service uuid: {}".format(service_uuid))

                for char in chars['result']:
                    char_id = char['id']
                    char_uuid = char['uuid_type']
                    try:
                        write_result = \
                            self.pri_dut.gattc_lib.writeCharByIdWithoutResponse(
                                char_id, write_value)
                        print("  Characteristic uuid write result: {} / {}".
                              format(char_uuid, write_result['result']))
                    except Exception as err:
                        pass
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_char_by_id(self, line):
        """
        Description: Write char by characteristic id reference.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
            offset: The offset value to use
            size: Function will generate random bytes by input size.
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_char_by_id char_id 0 5
            gattc_write_char_by_id char_id 20 1
        """
        cmd = "Write to GATT server characteristic ."
        try:
            args = line.split()
            if len(args) != 3:
                self.log.info("3 Arguments required: [Id] [Offset] [Size]")
                return
            id = int(args[0])
            offset = int(args[1])
            size = int(args[2])
            write_value = []
            for i in range(size):
                write_value.append(i % 256)
            self.pri_dut.gattc_lib.writeCharById(id, offset, write_value)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_char_by_id_without_response(self, line):
        """
        Description: Write char by characteristic id reference without response.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
            size: Function will generate random bytes by input size.
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_char_by_id_without_response char_id 5
        """
        cmd = "Write characteristic by id without response."
        try:
            args = line.split()
            if len(args) != 2:
                self.log.info("2 Arguments required: [Id] [Size]")
                return
            id = int(args[0])
            size = args[1]
            write_value = []
            for i in range(int(size)):
                write_value.append(i % 256)
            self.pri_dut.gattc_lib.writeCharByIdWithoutResponse(
                id, write_value)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_enable_notify_char_by_id(self, line):
        """
        Description: Enable Characteristic notification on Characteristic ID.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
        Usage:
          Examples:
            gattc_enable_notify_char_by_id char_id
        """
        cmd = "Enable notifications by Characteristic id."
        try:
            id = int(line)
            self.pri_dut.gattc_lib.enableNotifyCharacteristic(id)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_disable_notify_char_by_id(self, line):
        """
        Description: Disable Characteristic notification on Characteristic ID.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
        Usage:
          Examples:
            gattc_disable_notify_char_by_id char_id
        """
        cmd = "Disable notify Characteristic by id."
        try:
            id = int(line)
            self.pri_dut.gattc_lib.disableNotifyCharacteristic(id)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_char_by_id(self, line):
        """
        Description: Read Characteristic by ID.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
        Usage:
          Examples:
            gattc_read_char_by_id char_id
        """
        cmd = "Read Characteristic value by ID."
        try:
            id = int(line)
            read_val = self.pri_dut.gattc_lib.readCharacteristicById(id)
            self.log.info("Characteristic Value with id {}: {}".format(
                id, read_val['result']))
            str_value = ""
            for val in read_val['result']:
                str_value += chr(val)
            print("    str val: {}".format(str_value))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_write_desc_by_id(self, line):
        """
        Description: Write Descriptor by characteristic id reference.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            descriptor_id: The Descriptor id reference on the GATT service
            offset: The offset value to use
            size: Function will generate random bytes by input size.
                IE: Input of 5 will send a byte array of [00, 01, 02, 03, 04]
        Usage:
          Examples:
            gattc_write_desc_by_id desc_id 0 5
            gattc_write_desc_by_id desc_id 20 1
        """
        cmd = "Write Descriptor by id."
        try:
            args = line.split()
            id = int(args[0])
            offset = int(args[1])
            size = args[2]
            write_value = []
            for i in range(int(size)):
                write_value.append(i % 256)
            write_result = self.pri_dut.gattc_lib.writeDescriptorById(
                id, offset, write_value)
            self.log.info("Descriptor Write result {}: {}".format(
                id, write_result['result']))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_desc_by_id(self, line):
        """
        Description: Read Descriptor by ID.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            descriptor_id: The Descriptor id reference on the GATT service
        Usage:
          Examples:
            gattc_read_desc_by_id desc_id
        """
        cmd = "Read Descriptor by ID."
        try:
            id = int(line)
            read_val = self.pri_dut.gattc_lib.readDescriptorById(id)
            self.log.info("Descriptor Value with id {}: {}".format(
                id, read_val['result']))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_gattc_read_long_char_by_id(self, line):
        """
        Description: Read long Characteristic value by id.
        Assumptions: Already connected to a GATT server service.
        Input(s):
            characteristic_id: The characteristic id reference on the GATT
                service
            offset: The offset value to use.
            max_bytes: The max bytes size to return.
        Usage:
          Examples:
            gattc_read_long_char_by_id char_id 0 10
            gattc_read_long_char_by_id char_id 20 1
        """
        cmd = "Read long Characteristic value by id."
        try:
            args = line.split()
            if len(args) != 3:
                self.log.info("3 Arguments required: [Id] [Offset] [Size]")
                return
            id = int(args[0])
            offset = int(args[1])
            max_bytes = int(args[2])
            read_val = self.pri_dut.gattc_lib.readLongCharacteristicById(
                id, offset, max_bytes)
            self.log.info("Characteristic Value with id {}: {}".format(
                id, read_val['result']))

        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    """End GATT client wrappers"""
    """Begin LE scan wrappers"""

    def _update_scan_results(self, scan_results):
        self.le_ids = []
        for scan in scan_results['result']:
            self.le_ids.append(scan['id'])

    def do_ble_start_scan(self, line):
        """
        Description: Perform a BLE scan.
        Default filter name: ""
        Optional input: filter_device_name
        Usage:
          Examples:
            ble_start_scan
            ble_start_scan eddystone
        """
        cmd = "Perform a BLE scan and list discovered devices."
        try:
            scan_filter = {"name_substring": ""}
            if line:
                scan_filter = {"name_substring": line}
            self.pri_dut.gattc_lib.bleStartBleScan(scan_filter)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_ble_stop_scan(self, line):
        """
        Description: Stops a BLE scan and returns discovered devices.
        Usage:
          Examples:
            ble_stop_scan
        """
        cmd = "Stops a BLE scan and returns discovered devices."
        try:
            scan_results = self.pri_dut.gattc_lib.bleStopBleScan()
            self._update_scan_results(scan_results)
            self.log.info(pprint.pformat(scan_results))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    def do_ble_get_discovered_devices(self, line):
        """
        Description: Get discovered LE devices of an active scan.
        Usage:
          Examples:
            ble_stop_scan
        """
        cmd = "Get discovered LE devices of an active scan."
        try:
            scan_results = self.pri_dut.gattc_lib.bleGetDiscoveredDevices()
            self._update_scan_results(scan_results)
            self.log.info(pprint.pformat(scan_results))
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    """End LE scan wrappers"""
    """Begin GATT Server wrappers"""

    def complete_gatts_setup_database(self, text, line, begidx, endidx):
        if not text:
            completions = list(
                gatt_test_database.GATT_SERVER_DB_MAPPING.keys())
        else:
            completions = [
                s for s in gatt_test_database.GATT_SERVER_DB_MAPPING.keys()
                if s.startswith(text)
            ]
        return completions

    def do_gatts_setup_database(self, line):
        """
        Description: Setup a Gatt server database based on pre-defined inputs.
            Supports Tab Autocomplete.
        Input(s):
            descriptor_db_name: The descriptor db name that matches one in
                acts.test_utils.bt.gatt_test_database
        Usage:
          Examples:
            gatts_setup_database LARGE_DB_1
        """
        cmd = "Setup GATT Server Database Based of pre-defined dictionaries"
        try:
            scan_results = self.pri_dut.gatts_lib.publishServer(
                gatt_test_database.GATT_SERVER_DB_MAPPING.get(line))
            print(scan_results)
        except Exception as err:
            self.log.error(FAILURE.format(cmd, err))

    """End GATT Server wrappers"""
