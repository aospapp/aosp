#!/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
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

import dbus
import dbus.mainloop.glib
import dbus.service
import logging
import time
import os
import subprocess

from acts.test_utils.coex.coex_constants import ADAPTER_INTERFACE
from acts.test_utils.coex.coex_constants import CALL_MANAGER
from acts.test_utils.coex.coex_constants import CMD_FIND
from acts.test_utils.coex.coex_constants import CMD_HCI
from acts.test_utils.coex.coex_constants import CMD_PATH
from acts.test_utils.coex.coex_constants import commands
from acts.test_utils.coex.coex_constants import DBUS_INTERFACE
from acts.test_utils.coex.coex_constants import DEVICE_INTERFACE
from acts.test_utils.coex.coex_constants import DISCOVERY_TIME
from acts.test_utils.coex.coex_constants import KILL_CMD
from acts.test_utils.coex.coex_constants import MEDIA_CONTROL_INTERFACE
from acts.test_utils.coex.coex_constants import MEDIA_PLAY_INTERFACE
from acts.test_utils.coex.coex_constants import OBJECT_MANGER
from acts.test_utils.coex.coex_constants import OFONO_MANAGER
from acts.test_utils.coex.coex_constants import PROPERTIES
from acts.test_utils.coex.coex_constants import PROPERTIES_CHANGED
from acts.test_utils.coex.coex_constants import SERVICE_NAME
from acts.test_utils.coex.coex_constants import VOICE_CALL
from acts.test_utils.coex.coex_constants import WAIT_TIME
from acts.utils import create_dir

from gi.repository import GObject

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)


class BluezUtils():

    def __init__(self, profile, password, log_path):
        devices = {}
        self.device_interface = False
        self.mainloop = 0
        self.property_changed = False
        self.bd_address = None
        self.list_daemon = ["dbus", "bluez"]
        self.log_path = os.path.join(log_path, "bluez")
        create_dir(self.log_path)
        self.sudo_command = "echo " + password + " | sudo -S "
        if profile.lower() == "hfp":
            self.list_daemon.append("ofonod")
        elif profile.lower() == "a2dp":
            self.list_daemon.append("pulseaudio")
        elif profile.lower() == "multiprofile":
            self.list_daemon.extend(["pulseaudio", "ofonod"])
        if not self.hci_config("up"):
            logging.error("Can't get device info: No such device")
        self.run_daemons()
        self.bus = dbus.SystemBus()
        self.bus.add_signal_receiver(
            self.properties_changed,
            dbus_interface=DBUS_INTERFACE,
            signal_name=PROPERTIES_CHANGED,
            arg0=DEVICE_INTERFACE,
            path_keyword="path")
        self.om = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, "/"), OBJECT_MANGER)
        objects = self.om.GetManagedObjects()
        for path, interfaces in objects.items():
            if ADAPTER_INTERFACE in interfaces:
                devices[path] = interfaces[ADAPTER_INTERFACE]
                self.adapter = self.find_adapter(0)

    def hci_config(self, hci_params):
        """ Sets the interface up or down based on hci_params

        Args:
            hci_params : String denoting hciconfig paramters.

        Returns:
            True if success, False otherwise.
        """
        output, err = self.run_subprocess(CMD_HCI)
        if hci_params not in output.decode().split("\n")[2].lower():
            cmd = self.sudo_command + CMD_HCI + hci_params
            out, err = self.run_subprocess(cmd)
            if err:
                logging.debug("command HCI not executed error = {}".format(err))
                return False
        return True

    def run_daemons(self):
        """Runs all the bluez related daemons which are in the list."""
        self.kill_all_daemon()
        for daemon in self.list_daemon:
            cmd = CMD_PATH + commands[daemon]
            if daemon != "pulseaudio":
                cmd = self.sudo_command + cmd

            # is_async = True if process runs on background.
            self.run_subprocess(cmd, is_async=True)
            time.sleep(5)

    def kill_all_daemon(self):
        """Kills all the bluez related daemons running."""
        for daemon in self.list_daemon:
            cmd = CMD_FIND + CMD_PATH + daemon \
                  + "|grep -v grep|grep -v sudo"
            result, err = self.run_subprocess(cmd)
            if err:
                logging.info("cmd {} not executed".format(cmd))
            if result:
                data = result.decode().split("\n")
                command = self.sudo_command + KILL_CMD
                for i in range(len(data) - 1):
                    pid = data[i].split()[3]
                    cmd = command + pid
                    out, err = self.run_subprocess(cmd)
                    if err:
                        logging.error("command {} with error {}".format(
                            err, cmd))
                        logging.error("process with pid {}"
                                      " not terminated".format(pid))

    def run_subprocess(self, cmd, is_async=None, error_fd=subprocess.PIPE):
        """Runs subprocess in the background and moves error data to a file.

        Args:
            cmd: command to be executed.
            is_async: Boolean value to set background process.
            error_fd: handler for error file.

        Returns:
            Output of proc.communicate(), if is_async is not set.
        """
        if is_async:
            is_async = os.setpgrp
            if "dbus" not in cmd:
                file_name = os.path.join(self.log_path,
                    (cmd.split("/")[4].split("-")[0])) + "_daemon_error.txt"
                error_fd = open(file_name, "w+")

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=error_fd,
            preexec_fn=is_async,
            shell=True)
        logging.debug("Start standing subprocess with cmd: %s", cmd)
        if is_async:
            return
        return proc.communicate()

    def register_signal(self):
        """Start signal_dispatcher"""
        self.mainloop = GObject.MainLoop()
        self.mainloop.run()

    def unregister_signal(self):
        """Stops signal_dispatcher"""
        self.mainloop.quit()

    def get_properties(self, props, path, check):
        """Return's status for parameter check .

        Args:
            props:dbus interface
            path:path for getting status
            check:String for which status need to be checked
        """
        return props.Get(path, check)

    def properties_changed(self, interface, changed, invalidated, path):
        """Function to be executed when specified signal is caught"""
        if path == "/org/bluez/hci0/dev_" + (self.bd_address).replace(":", "_"):
            self.unregister_signal()
            return

    def get_managed_objects(self):
        """Gets the instance of all the objects in dbus.

        Returns:
            Dictionary containing path and interface of
            all the instance in dbus.
        """
        manager = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, "/"), OBJECT_MANGER)
        return manager.GetManagedObjects()

    def find_adapter(self, pattern=None):
        """Gets the adapter interface with specified pattern in dbus.

        Args:
            pattern: Adapter name pattern to be found out.

        Returns:
             Adapter interface with specified pattern.
        """
        return self.find_adapter_in_objects(self.get_managed_objects(), pattern)

    def find_adapter_in_objects(self, objects, pattern=None):
        """Gets the adapter interface with specified pattern in dbus.

        Args:
            objects: Dictionary containing path and interface of
            all the instance in dbus.
            pattern: Adapter name pattern to be found out.

        Returns:
             Adapter interface if successful else raises an exception.
        """
        for path, ifaces in objects.items():
            adapter = ifaces.get(ADAPTER_INTERFACE)
            if adapter is None:
                continue
            if not pattern or pattern == adapter["Address"] or \
                    path.endswith(pattern):
                adapter_obj = self.bus.get_object(SERVICE_NAME, path)
                return dbus.Interface(adapter_obj, ADAPTER_INTERFACE)
        raise Exception("Bluetooth adapter not found")

    def find_device_in_objects(self,
                               objects,
                               device_address,
                               adapter_pattern=None):
        """Gets the device interface in objects with specified device
        address and pattern.

        Args:
            objects: Dictionary containing path and interface of
            all the instance in dbus.
            device_address: Bluetooth interface MAC address of the device
            which is to be found out.
            adapter_pattern: Adapter name pattern to be found out.

        Returns:
             Device interface if successful else raises an exception.
        """
        path_prefix = ""
        if adapter_pattern:
            adapter = self.find_adapter_in_objects(objects, adapter_pattern)
            path_prefix = adapter.object_path
        for path, ifaces in objects.items():
            device = ifaces.get(DEVICE_INTERFACE)
            if device is None:
                continue
            if (device["Address"] == device_address and
                    path.startswith(path_prefix)):
                device_obj = self.bus.get_object(SERVICE_NAME, path)
                return dbus.Interface(device_obj, DEVICE_INTERFACE)
        raise Exception("Bluetooth device not found")

    def get_bluetooth_adapter_address(self):
        """Gets the bluetooth adapter address.

        Returns:
            Address of bluetooth adapter.
        """
        path = self.adapter.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        address = props.Get(ADAPTER_INTERFACE, "Address")
        return address

    def find_device(self, device_address):
        """Discovers the DUT and returns its dbus interface.

        Args:
            Device_address: Bluetooth interface MAC address of the device.

        Returns:
            Dbus interface of the device.
        """
        self.bd_address = device_address
        addr = "dev_" + str(device_address).replace(":", "_")
        device_path = "org/bluez/hci0/" + addr
        self.adapter.StartDiscovery()
        time.sleep(DISCOVERY_TIME)
        objects = self.om.GetManagedObjects()
        for path, interfaces in objects.items():
            if device_path in path:
                obj = self.bus.get_object(SERVICE_NAME, path)
                self.device_interface = dbus.Interface(obj, DEVICE_INTERFACE)
                self.adapter.StopDiscovery()
        if not self.device_interface:
            self.adapter.StopDiscovery()
            return False
        return True

    def media_control_iface(self, device_address):
        """Gets the dbus media control interface for the device
        and returns it.

        Args:
            device_address: Bluetooth interface MAC address of the device.

        Returns:
            Dbus media control interface of the device.
        """
        control_iface = dbus.Interface(
            self.bus.get_object(
                SERVICE_NAME,
                '/org/bluez/hci0/dev_' + device_address.replace(":", "_")),
            MEDIA_CONTROL_INTERFACE)
        return control_iface

    def get_a2dp_interface(self, device_address):
        """Gets the dbus media interface for the device.

        Args:
            device_address: Bluetooth interface MAC address of the device.

        Returns:
            Dbus media interface of the device.
        """
        a2dp_interface = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, '/org/bluez/hci0/dev_' +
                                device_address.replace(":", "_") + '/player0'),
            MEDIA_PLAY_INTERFACE)
        return a2dp_interface

    def ofo_iface(self):
        """Gets dbus hfp interface for the device.

        Returns:
            Dbus hfp interface of the device.
        """
        manager = dbus.Interface(
            self.bus.get_object('org.ofono', '/'), OFONO_MANAGER)
        modems = manager.GetModems()
        return modems

    def call_manager(self, path):
        """Gets Ofono(HFP) interface for the device.

        Args:
            path: Ofono interface path of the device.

        Returns:
            Ofono interface for the device.
        """
        vcm = dbus.Interface(
            self.bus.get_object('org.ofono', path), CALL_MANAGER)
        return vcm

    def answer_call_interface(self, path):
        """Gets the voice call interface for the device.

        Args
            path: Voice call path of the device.

        Returns:
             Interface for the voice call.
        """
        call = dbus.Interface(
            self.bus.get_object('org.ofono', path), VOICE_CALL)
        return call

    def pair_bluetooth_device(self):
        """Pairs the bluez machine with DUT.

        Returns:
            True if pairing is successful else False.
        """
        self.device_interface.Pair()
        path = self.device_interface.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        paired = self.get_properties(props, DEVICE_INTERFACE, "Paired")
        return paired

    def connect_bluetooth_device(self, *args):
        """Connects the bluez machine to DUT with the specified
        profile.

        Args:
            uuid: Profile UUID which is to be connected.

        Returns:
            True if connection is successful else False.
        """

        self.register_signal()
        for uuid in args:
            self.device_interface.ConnectProfile(uuid)
        path = self.device_interface.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        connect = self.get_properties(props, DEVICE_INTERFACE, "Connected")
        return connect

    def disconnect_bluetooth_profile(self, uuid, pri_ad):
        """Disconnects the DUT for the specified profile.

        Args:
            uuid: Profile UUID which is to be disconnected.
            pri_ad: An android device object.

        Returns:
            True if disconnection of profile is successful else False.
        """

        self.register_signal()
        self.device_interface.DisconnectProfile(uuid)
        time.sleep(10)  #Time taken to check disconnection.
        connected_devices = pri_ad.droid.bluetoothGetConnectedDevices()
        if len(connected_devices) > 0:
            return False
        return True

    def play_media(self, address):
        """Initiate media play for the specified device.

        Args:
            address: Bluetooth interface MAC address of the device.

        Returns:
            "playing" if successful else "stopped" or "paused".
        """
        self.register_signal()
        a2dp = self.media_control_iface(address)
        time.sleep(WAIT_TIME)
        a2dp.Play()
        play_pause = self.get_a2dp_interface(address)
        path = play_pause.object_path
        time.sleep(WAIT_TIME)
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        status = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Status")
        return status

    def pause_media(self, address):
        """Pauses the media palyer for the specified device.

        Args:
            address: Bluetooth interface MAC address of the device.

        Return:
            "paused" or "stopped" if successful else "playing".
        """
        self.register_signal()
        a2dp = self.get_a2dp_interface(address)
        time.sleep(WAIT_TIME)
        a2dp.Pause()
        path = a2dp.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        status = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Status")
        return status

    def remove_bluetooth_device(self, address):
        """Removes the device from the paired list.

        Args:
            address: Bluetooth interface MAC address of the device.

        Returns:
            True if removing of device is successful else False.
        """
        managed_objects = self.get_managed_objects()
        adapter = self.find_adapter_in_objects(managed_objects)
        try:
            dev = self.find_device_in_objects(managed_objects, address)
            path = dev.object_path
        except:
            return False

        adapter.RemoveDevice(path)
        return True

    def stop_media(self, address):
        """Stops the media player for the specified device.

        Args:
            address: Bluetooth interface MAC address of the device.

        Returns:
            "paused" or "stopped" if successful else "playing".
        """
        self.register_signal()
        a2dp = self.get_a2dp_interface(address)
        time.sleep(WAIT_TIME)
        a2dp.Stop()
        path = a2dp.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        status = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Status")
        return status

    def skip_next(self, address):
        """Skips to Next track in media player.

        Args:
            address: Bluetooth interface MAC address of the device.

        Returns:
            True if the media track change is successful else False.
        """
        self.register_signal()
        a2dp = self.get_a2dp_interface(address)
        time.sleep(WAIT_TIME)
        path = a2dp.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        track = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Track")
        Title = track['Title']
        a2dp.Next()
        time.sleep(WAIT_TIME)
        track = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Track")
        if Title == track['Title']:
            return False
        return True

    def skip_previous(self, address):
        """Skips to previous track in media player.

        Args:
            address: Buetooth interface MAC address of the device.

        Returns:
            True if media track change is successful else False.
        """
        a2dp = self.get_a2dp_interface(address)
        time.sleep(WAIT_TIME)
        path = a2dp.object_path
        props = dbus.Interface(
            self.bus.get_object(SERVICE_NAME, path), PROPERTIES)
        track = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Track")
        Title = track['Title']
        a2dp.Previous()
        a2dp.Previous()
        time.sleep(WAIT_TIME)
        track = self.get_properties(props, MEDIA_PLAY_INTERFACE, "Track")
        if Title == track['Title']:
            return False
        return True

    def volumeup(self, address):
        """Increases volume of the media playing.

        Args:
            address: Buetooth interface MAC address of the device.

        Returns:
            True if successful.
        """
        a2dp = self.media_control_iface(address)
        a2dp.VolumeUp()
        return True

    def volumedown(self, address):
        """Decreases volume of the media playing.

        Args:
            address: Buetooth interface MAC address of the device.

        Returns:
            True if successful.
        """
        a2dp = self.media_control_iface(address)
        a2dp.VolumeDown()
        return True

    def call_volume(self, duration):
        """Performs Speaker gain and microphone gain when call is active.

        Args:
            duration:time in seconds to increase volume continuously.

        Returns:
            True if Property has been changed, otherwise False.
        """
        vol_level = 0
        modems = self.ofo_iface()
        path = modems[0][0]
        props = dbus.Interface(
            self.bus.get_object('org.ofono', path), 'org.ofono.CallVolume')
        start_time = time.time()
        while (time.time()) < (start_time + duration):
            props.SetProperty("SpeakerVolume", dbus.Byte(int(vol_level)))
            time.sleep(WAIT_TIME)
            props.SetProperty("MicrophoneVolume", dbus.Byte(int(vol_level)))
            vol_level += 5
        return True

    def avrcp_actions(self, address):
        """Performs AVRCP actions for the device

        Args:
            address: Bluetooth interface MAC address of the device.

        Returns:
            True if avrcp actions are performed else False.
        """
        if not self.skip_next(address):
            logging.info("skip Next failed")
            return False
        time.sleep(WAIT_TIME)

        if not self.skip_previous(address):
            logging.info("skip previous failed")
            return False
        time.sleep(WAIT_TIME)

        if not self.volumeup(address):
            logging.info("Volume up failed")
            return False
        time.sleep(WAIT_TIME)

        if not self.volumedown(address):
            logging.info("Volume down failed")
            return False
        return True

    def initiate_and_disconnect_call_from_hf(self, phone_no, duration):
        """Initiates the call from bluez for the specified phone number.

        Args:
            phone_no: Phone number to which the call should be made.
            duration: Time till which the call should be active.

        Returns:
             True if the call is initiated and disconnected else False.
        """
        modems = self.ofo_iface()
        modem = modems[0][0]
        hide_callerid = "default"
        vcm = self.call_manager(modem)
        time.sleep(WAIT_TIME)
        path = vcm.Dial(phone_no, hide_callerid)
        if 'voicecall' not in path:
            return False
        time.sleep(duration)
        vcm.HangupAll()
        return True

    def answer_call(self, duration):
        """Answers the incoming call from bluez.

        Args:
            duration: Time till which the call should be active.

        Returns:
             True if call is answered else False.
        """
        modems = self.ofo_iface()
        for path, properties in modems:
            if CALL_MANAGER not in properties["Interfaces"]:
                continue
            mgr = self.call_manager(path)
            calls = mgr.GetCalls()
            for path, properties in calls:
                state = properties["State"]
                if state != "incoming":
                    continue
                call = self.answer_call_interface(path)
                call.Answer()
                time.sleep(duration)
                call.Hangup()
        return True
