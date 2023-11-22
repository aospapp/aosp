# Copyright 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import unittest
import its.objects

def full(props):
    """Returns whether a device is a FULL capability camera2 device.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return props.has_key("android.info.supportedHardwareLevel") and \
           props["android.info.supportedHardwareLevel"] == 1

def limited(props):
    """Returns whether a device is a LIMITED capability camera2 device.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return props.has_key("android.info.supportedHardwareLevel") and \
           props["android.info.supportedHardwareLevel"] == 0

def legacy(props):
    """Returns whether a device is a LEGACY capability camera2 device.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return props.has_key("android.info.supportedHardwareLevel") and \
           props["android.info.supportedHardwareLevel"] == 2

def manual_sensor(props):
    """Returns whether a device supports MANUAL_SENSOR capabilities.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return    props.has_key("android.request.availableCapabilities") and \
              1 in props["android.request.availableCapabilities"] \
           or full(props)

def manual_post_proc(props):
    """Returns whether a device supports MANUAL_POST_PROCESSING capabilities.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return    props.has_key("android.request.availableCapabilities") and \
              2 in props["android.request.availableCapabilities"] \
           or full(props)

def raw(props):
    """Returns whether a device supports RAW capabilities.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return props.has_key("android.request.availableCapabilities") and \
           3 in props["android.request.availableCapabilities"]

def raw16(props):
    """Returns whether a device supports RAW16 output.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return len(its.objects.get_available_output_sizes("raw", props)) > 0

def raw10(props):
    """Returns whether a device supports RAW10 output.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return len(its.objects.get_available_output_sizes("raw10", props)) > 0

def sensor_fusion(props):
    """Returns whether the camera and motion sensor timestamps for the device
    are in the same time domain and can be compared direcctly.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return props.has_key("android.sensor.info.timestampSource") and \
           props["android.sensor.info.timestampSource"] == 1

def read_3a(props):
    """Return whether a device supports reading out the following 3A settings:
        sensitivity
        exposure time
        awb gain
        awb cct
        focus distance

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    # TODO: check available result keys explicitly
    return manual_sensor(props) and manual_post_proc(props)

def compute_target_exposure(props):
    """Return whether a device supports target exposure computation in its.target module.

    Args:
        props: Camera properties object.

    Returns:
        Boolean.
    """
    return manual_sensor(props) and manual_post_proc(props)

class __UnitTest(unittest.TestCase):
    """Run a suite of unit tests on this module.
    """
    # TODO: Add more unit tests.

if __name__ == '__main__':
    unittest.main()

