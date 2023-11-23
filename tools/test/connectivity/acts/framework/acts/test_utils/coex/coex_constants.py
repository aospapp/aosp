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

AUDIO_ROUTE_SPEAKER = "SPEAKER"
AUDIO_ROUTE_BLUETOOTH = "BLUETOOTH"

CALL_WAIT_TIME = 10
DISCOVERY_TIME = 13
WAIT_TIME = 3
PROP_UPDATE_TIME = 5

OBJECT_MANGER = "org.freedesktop.DBus.ObjectManager"
PROPERTIES = "org.freedesktop.DBus.Properties"
PROPERTIES_CHANGED = "PropertiesChanged"
SERVICE_NAME = "org.bluez"
CALL_MANAGER = "org.ofono.VoiceCallManager"
VOICE_CALL = "org.ofono.VoiceCall"
OFONO_MANAGER = "org.ofono.Manager"

ADAPTER_INTERFACE = SERVICE_NAME + ".Adapter1"
DBUS_INTERFACE = "org.freedesktop.DBus.Properties"
DEVICE_INTERFACE = SERVICE_NAME + ".Device1"
MEDIA_CONTROL_INTERFACE = SERVICE_NAME + ".MediaControl1"
MEDIA_PLAY_INTERFACE = SERVICE_NAME + ".MediaPlayer1"

A2DP_TEST = "a2dp"
HFP_TEST = "hfp"
MULTIPROFILE_TEST = "multiprofile"
HCI_CONFIG_UP = "up"
HCI_CONFIG_DOWN = "down"
BLUEZ_PATH = "/usr/local/bluez/"
KILL_CMD = "kill -9 "
FIND_PROCESS = "ps -elf|grep "
CMD_FIND = ""
CMD_PATH = ""
CMD_HCI = "hciconfig hci0 "

commands = {
    "dbus": "dbus-1.8.6/bin/dbus-daemon --system --nopidfile",
    "bluez": "bluez-5.39/libexec/bluetooth/bluetoothd -ndE --compat",
    "ofono": "ofono-1.17/sbin/ofonod -nd",
    "pulseaudio": "pulseaudio-8.0/bin/pulseaudio  -vvv",
}

bluetooth_profiles = {
    "A2DP_SRC": "0000110a-0000-1000-8000-00805f9b34fb",
    "HFP_AG": "0000111f-0000-1000-8000-00805f9b34fb"
}
