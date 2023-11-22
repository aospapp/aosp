#/usr/bin/env python3.4
#
# Copyright (C) 2016 The Android Open Source Project
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
This test script exercises different GATT connection tests.
"""

import pprint
from queue import Empty
import time
from contextlib import suppress

from acts.test_utils.bt.BluetoothBaseTest import BluetoothBaseTest
from acts.test_utils.bt.GattEnum import GattCharacteristic
from acts.test_utils.bt.GattEnum import GattDescriptor
from acts.test_utils.bt.GattEnum import GattService
from acts.test_utils.bt.GattEnum import MtuSize
from acts.test_utils.bt.GattEnum import GattCbErr
from acts.test_utils.bt.GattEnum import GattCbStrings
from acts.test_utils.bt.GattEnum import GattConnectionPriority
from acts.test_utils.bt.GattEnum import GattTransport
from acts.test_utils.bt.bt_gatt_utils import GattTestUtilsError
from acts.test_utils.bt.bt_gatt_utils import disconnect_gatt_connection
from acts.test_utils.bt.bt_gatt_utils import orchestrate_gatt_connection
from acts.test_utils.bt.bt_gatt_utils import setup_gatt_characteristics
from acts.test_utils.bt.bt_gatt_utils import setup_gatt_connection
from acts.test_utils.bt.bt_gatt_utils import setup_gatt_descriptors
from acts.test_utils.bt.bt_test_utils import get_mac_address_of_generic_advertisement
from acts.test_utils.bt.bt_test_utils import log_energy_info


class GattConnectTest(BluetoothBaseTest):
    adv_instances = []
    default_timeout = 10
    default_discovery_timeout = 3

    def __init__(self, controllers):
        BluetoothBaseTest.__init__(self, controllers)
        self.cen_ad = self.android_devices[0]
        self.per_ad = self.android_devices[1]

    def teardown_test(self):
        for adv in self.adv_instances:
            self.per_ad.droid.bleStopBleAdvertising(adv)
        self.log.debug(log_energy_info(self.android_devices, "End"))
        return True

    def _setup_characteristics_and_descriptors(self, droid):
        characteristic_input = [
            {
                'uuid': "aa7edd5a-4d1d-4f0e-883a-d145616a1630",
                'property': GattCharacteristic.PROPERTY_WRITE.value
                | GattCharacteristic.PROPERTY_WRITE_NO_RESPONSE.value,
                'permission': GattCharacteristic.PERMISSION_WRITE.value
            },
            {
                'uuid': "21c0a0bf-ad51-4a2d-8124-b74003e4e8c8",
                'property': GattCharacteristic.PROPERTY_NOTIFY.value
                | GattCharacteristic.PROPERTY_READ.value,
                'permission': GattCharacteristic.PERMISSION_READ.value
            },
            {
                'uuid': "6774191f-6ec3-4aa2-b8a8-cf830e41fda6",
                'property': GattCharacteristic.PROPERTY_NOTIFY.value
                | GattCharacteristic.PROPERTY_READ.value,
                'permission': GattCharacteristic.PERMISSION_READ.value
            },
        ]
        descriptor_input = [
            {
                'uuid': "aa7edd5a-4d1d-4f0e-883a-d145616a1630",
                'property': GattDescriptor.PERMISSION_READ.value
                | GattDescriptor.PERMISSION_WRITE.value,
            }, {
                'uuid': "76d5ed92-ca81-4edb-bb6b-9f019665fb32",
                'property': GattDescriptor.PERMISSION_READ.value
                | GattCharacteristic.PERMISSION_WRITE.value,
            }
        ]
        characteristic_list = setup_gatt_characteristics(droid,
                                                         characteristic_input)
        descriptor_list = setup_gatt_descriptors(droid, descriptor_input)
        return characteristic_list, descriptor_list

    def _orchestrate_gatt_disconnection(self, bluetooth_gatt, gatt_callback):
        self.log.info("Disconnecting from peripheral device.")
        test_result = disconnect_gatt_connection(self.cen_ad, bluetooth_gatt,
                                                 gatt_callback)
        self.cen_ad.droid.gattClientClose(bluetooth_gatt)
        if not test_result:
            self.log.info("Failed to disconnect from peripheral device.")
            return False
        return True

    def _iterate_attributes(self, discovered_services_index):
        services_count = self.cen_ad.droid.gattClientGetDiscoveredServicesCount(
            discovered_services_index)
        for i in range(services_count):
            service = self.cen_ad.droid.gattClientGetDiscoveredServiceUuid(
                discovered_services_index, i)
            self.log.info("Discovered service uuid {}".format(service))
            characteristic_uuids = (
                self.cen_ad.droid.gattClientGetDiscoveredCharacteristicUuids(
                    discovered_services_index, i))
            for characteristic in characteristic_uuids:
                self.log.info("Discovered characteristic uuid {}".format(
                    characteristic))
                descriptor_uuids = (
                    self.cen_ad.droid.gattClientGetDiscoveredDescriptorUuids(
                        discovered_services_index, i, characteristic))
                for descriptor in descriptor_uuids:
                    self.log.info("Discovered descriptor uuid {}".format(
                        descriptor))

    def _find_service_added_event(self, gatt_server_callback, uuid):
        expected_event = GattCbStrings.SERV_ADDED.value.format(
            gatt_server_callback)
        try:
            event = self.per_ad.ed.pop_event(expected_event,
                                             self.default_timeout)
        except Empty:
            self.log.error(GattCbErr.SERV_ADDED_ERR.value.format(
                expected_event))
            return False
        if event['data']['serviceUuid'].lower() != uuid.lower():
            self.log.error("Uuid mismatch. Found: {}, Expected {}.".format(
                event['data']['serviceUuid'], uuid))
            return False
        return True

    def _setup_multiple_services(self):
        gatt_server_callback = (
            self.per_ad.droid.gattServerCreateGattServerCallback())
        gatt_server = self.per_ad.droid.gattServerOpenGattServer(
            gatt_server_callback)
        characteristic_list, descriptor_list = (
            self._setup_characteristics_and_descriptors(self.per_ad.droid))
        self.per_ad.droid.gattServerCharacteristicAddDescriptor(
            characteristic_list[1], descriptor_list[0])
        self.per_ad.droid.gattServerCharacteristicAddDescriptor(
            characteristic_list[2], descriptor_list[1])
        gatt_service = self.per_ad.droid.gattServerCreateService(
            "00000000-0000-1000-8000-00805f9b34fb",
            GattService.SERVICE_TYPE_PRIMARY.value)
        gatt_service2 = self.per_ad.droid.gattServerCreateService(
            "FFFFFFFF-0000-1000-8000-00805f9b34fb",
            GattService.SERVICE_TYPE_PRIMARY.value)
        gatt_service3 = self.per_ad.droid.gattServerCreateService(
            "3846D7A0-69C8-11E4-BA00-0002A5D5C51B",
            GattService.SERVICE_TYPE_PRIMARY.value)
        for characteristic in characteristic_list:
            self.per_ad.droid.gattServerAddCharacteristicToService(
                gatt_service, characteristic)
        self.per_ad.droid.gattServerAddService(gatt_server, gatt_service)
        result = self._find_service_added_event(
            gatt_server_callback, "00000000-0000-1000-8000-00805f9b34fb")
        if not result:
            return False
        for characteristic in characteristic_list:
            self.per_ad.droid.gattServerAddCharacteristicToService(
                gatt_service2, characteristic)
        self.per_ad.droid.gattServerAddService(gatt_server, gatt_service2)
        result = self._find_service_added_event(
            gatt_server_callback, "FFFFFFFF-0000-1000-8000-00805f9b34fb")
        if not result:
            return False
        for characteristic in characteristic_list:
            self.per_ad.droid.gattServerAddCharacteristicToService(
                gatt_service3, characteristic)
        self.per_ad.droid.gattServerAddService(gatt_server, gatt_service3)
        result = self._find_service_added_event(
            gatt_server_callback, "3846D7A0-69C8-11E4-BA00-0002A5D5C51B")
        if not result:
            return False, False
        return gatt_server_callback, gatt_server

    def _cleanup_services(self, gatt_server):
        self.per_ad.droid.gattServerClearServices(gatt_server)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect(self):
        """Test GATT connection over LE.

        Test establishing a gatt connection between a GATT server and GATT
        client.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT
        Priority: 0
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_autoconnect(self):
        """Test GATT connection over LE.

        Test re-establishing a gat connection using autoconnect
        set to True in order to test connection whitelist.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. Disconnect the GATT connection.
        7. Create a GATT connection with autoconnect set to True
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was re-established and then disconnected
        successfully.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT
        Priority: 0
        """
        autoconnect = False
        mac_address, adv_callback = (
            get_mac_address_of_generic_advertisement(self.cen_ad, self.per_ad))
        test_result, bluetooth_gatt, gatt_callback = setup_gatt_connection(
            self.cen_ad, mac_address, autoconnect)
        if not disconnect_gatt_connection(self.cen_ad, bluetooth_gatt,
                                          gatt_callback):
            return False
        autoconnect = True
        bluetooth_gatt = self.cen_ad.droid.gattClientConnectGatt(
            gatt_callback, mac_address, autoconnect, GattTransport.TRANSPORT_AUTO)
        expected_event = GattCbStrings.GATT_CONN_CHANGE.value.format(
            gatt_callback)
        try:
            event = self.cen_ad.ed.pop_event(expected_event,
                                             self.default_timeout)
        except Empty:
            log.error(GattCbErr.GATT_CONN_CHANGE_ERR.value.format(
                expected_event))
            test_result = False
        return True

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_request_min_mtu(self):
        """Test GATT connection over LE and exercise MTU sizes.

        Test establishing a gatt connection between a GATT server and GATT
        client. Request an MTU size that matches the correct minimum size.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (client) request MTU size change to the
        minimum value.
        7. Find the MTU changed event on the client.
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and the MTU value found
        matches the expected MTU value.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, MTU
        Priority: 0
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        expected_mtu = MtuSize.MIN.value
        self.cen_ad.droid.gattClientRequestMtu(bluetooth_gatt, expected_mtu)
        expected_event = GattCbStrings.MTU_CHANGED.value.format(gatt_callback)
        try:
            mtu_event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
            mtu_size_found = mtu_event['data']['MTU']
            if mtu_size_found != expected_mtu:
                self.log.error("MTU size found: {}, expected: {}".format(
                    mtu_size_found, expected_mtu))
                return False
        except Empty:
            self.log.error(GattCbErr.MTU_CHANGED_ERR.value.format(
                expected_event))
            return False
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_request_max_mtu(self):
        """Test GATT connection over LE and exercise MTU sizes.

        Test establishing a gatt connection between a GATT server and GATT
        client. Request an MTU size that matches the correct maximum size.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (client) request MTU size change to the
        maximum value.
        7. Find the MTU changed event on the client.
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and the MTU value found
        matches the expected MTU value.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, MTU
        Priority: 0
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        expected_mtu = MtuSize.MAX.value
        self.cen_ad.droid.gattClientRequestMtu(bluetooth_gatt, expected_mtu)
        expected_event = GattCbStrings.MTU_CHANGED.value.format(gatt_callback)
        try:
            mtu_event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
            mtu_size_found = mtu_event['data']['MTU']
            if mtu_size_found != expected_mtu:
                self.log.error("MTU size found: {}, expected: {}".format(
                    mtu_size_found, expected_mtu))
                return False
        except Empty:
            self.log.error(GattCbErr.MTU_CHANGED_ERR.value.format(
                expected_event))
            return False
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_request_out_of_bounds_mtu(self):
        """Test GATT connection over LE and exercise an out of bound MTU size.

        Test establishing a gatt connection between a GATT server and GATT
        client. Request an MTU size that is the MIN value minus 1.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (client) request MTU size change to the
        minimum value minus one.
        7. Find the MTU changed event on the client.
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that an MTU changed event was not discovered and that
        it didn't cause an exception when requesting an out of bounds
        MTU.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, MTU
        Priority: 0
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        self.cen_ad.droid.gattClientRequestMtu(bluetooth_gatt,
                                               MtuSize.MIN.value - 1)
        expected_event = GattCbStrings.MTU_CHANGED.value.format(gatt_callback)
        try:
            self.cen_ad.ed.pop_event(expected_event, self.default_timeout)
            self.log.error("Found {} event when it wasn't expected".format(
                expected_event))
            return False
        except Empty:
            self.log.debug("Successfully didn't find {} event".format(
                expected_event))
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_trigger_on_read_rssi(self):
        """Test GATT connection over LE read RSSI.

        Test establishing a gatt connection between a GATT server and GATT
        client then read the RSSI.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner, request to read the RSSI of the advertiser.
        7. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully. Verify that the RSSI was ready correctly.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, RSSI
        Priority: 1
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        expected_event = GattCbStrings.RD_REMOTE_RSSI.value.format(
            gatt_callback)
        if self.cen_ad.droid.gattClientReadRSSI(bluetooth_gatt):
            try:
                self.cen_ad.ed.pop_event(expected_event, self.default_timeout)
            except Empty:
                self.log.error(GattCbErr.RD_REMOTE_RSSI_ERR.value.format(
                    expected_event))
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_trigger_on_services_discovered(self):
        """Test GATT connection and discover services of peripheral.

        Test establishing a gatt connection between a GATT server and GATT
        client the discover all services from the connected device.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (central device), discover services.
        7. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully. Verify that the service were discovered.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, Services
        Priority: 1
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        if self.cen_ad.droid.gattClientDiscoverServices(bluetooth_gatt):
            expected_event = GattCbStrings.GATT_SERV_DISC.value.format(
                gatt_callback)
            try:
                event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
            except Empty:
                self.log.error(GattCbErr.GATT_SERV_DISC_ERR.value.format(
                    expected_event))
                return False
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_trigger_on_services_discovered_iterate_attributes(
            self):
        """Test GATT connection and iterate peripherals attributes.

        Test establishing a gatt connection between a GATT server and GATT
        client and iterate over all the characteristics and descriptors of the
        discovered services.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (central device), discover services.
        7. Iterate over all the characteristics and descriptors of the
        discovered features.
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully. Verify that the services, characteristics, and descriptors
        were discovered.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, Services
        Characteristics, Descriptors
        Priority: 1
        """
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        if self.cen_ad.droid.gattClientDiscoverServices(bluetooth_gatt):
            expected_event = GattCbStrings.GATT_SERV_DISC.value.format(
                gatt_callback)
            try:
                event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
                discovered_services_index = event['data']['ServicesIndex']
            except Empty:
                self.log.error(GattCbErr.GATT_SERV_DISC_ERR.value.format(
                    expected_event))
                return False
            self._iterate_attributes(discovered_services_index)
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_with_service_uuid_variations(self):
        """Test GATT connection with multiple service uuids.

        Test establishing a gatt connection between a GATT server and GATT
        client with multiple service uuid variations.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. From the scanner (central device), discover services.
        7. Verify that all the service uuid variations are found.
        8. Disconnect the GATT connection.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully. Verify that the service uuid variations are found.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, Services
        Priority: 2
        """
        gatt_server_callback, gatt_server = self._setup_multiple_services()
        if not gatt_server_callback or not gatt_server:
            return False
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        if self.cen_ad.droid.gattClientDiscoverServices(bluetooth_gatt):
            expected_event = GattCbStrings.GATT_SERV_DISC.value.format(
                gatt_callback)
            try:
                event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
            except Empty:
                self.log.error(GattCbErr.GATT_SERV_DISC_ERR.value.format(
                    expected_event))
                return False
            discovered_services_index = event['data']['ServicesIndex']
            self._iterate_attributes(discovered_services_index)

        self._cleanup_services(gatt_server)
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_in_quick_succession(self):
        """Test GATT connections multiple times.

        Test establishing a gatt connection between a GATT server and GATT
        client with multiple iterations.

        Steps:
        1. Start a generic advertisement.
        2. Start a generic scanner.
        3. Find the advertisement and extract the mac address.
        4. Stop the first scanner.
        5. Create a GATT connection between the scanner and advertiser.
        6. Disconnect the GATT connection.
        7. Repeat steps 5 and 6 twenty times.

        Expected Result:
        Verify that a connection was established and then disconnected
        successfully twenty times.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, Stress
        Priority: 1
        """
        mac_address, adv_callback = get_mac_address_of_generic_advertisement(
            self.cen_ad, self.per_ad)
        autoconnect = False
        for i in range(1000):
            self.log.info("Starting connection iteration {}".format(i + 1))
            test_result, bluetooth_gatt, gatt_callback = setup_gatt_connection(
                self.cen_ad, mac_address, autoconnect)
            if not test_result:
                self.log.info("Could not connect to peripheral.")
                return False
            test_result = self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                               gatt_callback)
            if not test_result:
                self.log.info("Failed to disconnect from peripheral device.")
                return False
        self.adv_instances.append(adv_callback)
        return True

    @BluetoothBaseTest.bt_test_wrap
    def test_gatt_connect_mitm_attack(self):
        """Test GATT connection with permission write encrypted mitm.

        Test establishing a gatt connection between a GATT server and GATT
        client while the GATT server's characteristic includes the property
        write value and the permission write encrypted mitm value. This will
        prompt LE pairing and then the devices will create a bond.

        Steps:
        1. Create a GATT server and server callback on the peripheral device.
        2. Create a unique service and characteristic uuid on the peripheral.
        3. Create a characteristic on the peripheral with these properties:
            GattCharacteristic.PROPERTY_WRITE.value,
            GattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM.value
        4. Create a GATT service on the peripheral.
        5. Add the characteristic to the GATT service.
        6. Create a GATT connection between your central and peripheral device.
        7. From the central device, discover the peripheral's services.
        8. Iterate the services found until you find the unique characteristic
            created in step 3.
        9. Once found, write a random but valid value to the characteristic.
        10. Start pairing helpers on both devices immediately after attempting
            to write to the characteristic.
        11. Within 10 seconds of writing the characteristic, there should be
            a prompt to bond the device from the peripheral. The helpers will
            handle the UI interaction automatically. (see
            BluetoothConnectionFacade.java bluetoothStartPairingHelper).
        12. Verify that the two devices are bonded.

        Expected Result:
        Verify that a connection was established and the devices are bonded.

        Returns:
          Pass if True
          Fail if False

        TAGS: LE, Advertising, Filtering, Scanning, GATT, Characteristic, MITM
        Priority: 1
        """
        gatt_server_callback, gatt_server = self._setup_multiple_services()
        if not gatt_server_callback or not gatt_server:
            return False
        bonded = False
        test_uuid = "aa7edd5a-4d1d-4f0e-883a-d145616a1630"
        try:
            bluetooth_gatt, gatt_callback, adv_callback = (
                orchestrate_gatt_connection(self.cen_ad, self.per_ad))
        except GattTestUtilsError:
            return False
        self.adv_instances.append(adv_callback)
        if self.cen_ad.droid.gattClientDiscoverServices(bluetooth_gatt):
            expected_event = GattCbStrings.GATT_SERV_DISC.value.format(
                gatt_callback)
            try:
                event = self.cen_ad.ed.pop_event(expected_event,
                                                 self.default_timeout)
            except Empty:
                self.log.error(GattCbErr.GATT_SERV_DISC_ERR.value.format(
                    expected_event))
                return False
            discovered_services_index = event['data']['ServicesIndex']
        else:
            self.log.info("Failed to discover services.")
            return False
        test_value = [1,2,3,4,5,6,7]
        services_count = self.cen_ad.droid.gattClientGetDiscoveredServicesCount(
            discovered_services_index)
        for i in range(services_count):
            characteristic_uuids = (
                self.cen_ad.droid.gattClientGetDiscoveredCharacteristicUuids(
                    discovered_services_index, i))
            for characteristic_uuid in characteristic_uuids:
                if characteristic_uuid == test_uuid:
                    self.cen_ad.droid.bluetoothStartPairingHelper()
                    self.per_ad.droid.bluetoothStartPairingHelper()
                    self.cen_ad.droid.gattClientCharacteristicSetValue(
                        bluetooth_gatt, discovered_services_index, i,
                        characteristic_uuid, test_value)
                    self.cen_ad.droid.gattClientWriteCharacteristic(
                        bluetooth_gatt, discovered_services_index, i,
                        characteristic_uuid)
                    start_time = time.time() + self.default_timeout
                    target_name = self.per_ad.droid.bluetoothGetLocalName()
                    while time.time() < start_time and bonded == False:
                        bonded_devices = self.cen_ad.droid.bluetoothGetBondedDevices(
                        )
                        for device in bonded_devices:
                            if 'name' in device.keys() and device[
                                    'name'] == target_name:
                                bonded = True
                                break
        self._cleanup_services(gatt_server)
        return self._orchestrate_gatt_disconnection(bluetooth_gatt,
                                                    gatt_callback)

