## UWB Multi-device CTS Tests

### General Requirements for test setup calibration
Two Android UWB devices (DUT and reference device) are needed for these tests.

The devices need to also obtain a valid country code to enable UWB. Country code
sources used are (any one of them is sufficient):
1. Country code from telephony connection (insert a working SIM card in the
device)
2. Country code from Wi-Fi access points which support 80211.d (have one or more
wifi access points in the environment)
3. Country code from location (turn on [location](
https://support.google.com/android/answer/3467281?sjid=2881239016184571424-NA)
on your devices and enable [location accuracy](
https://support.google.com/android/answer/3467281?sjid=2881239016184571424-NA#location_accuracy)).

#### Special Requirements for `CtsUwbMultiDeviceTestCase_RangingMeasurementTests` test case
The devices should be placed in the line of sight environment in a non-reflective chamber with
1 m distance between the DUT and reference device. Both devices should be positioned in portrait
orientation perpendicular to the ground, with the screen facing perpendicular away from each other.

Refer to [this page](
https://source.android.com/docs/core/connect/presence-requirements#calibration-setup)
for an example of similar physical setup with illustration.
