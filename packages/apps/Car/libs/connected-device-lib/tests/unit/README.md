# Instructions for running unit tests

### Build unit test module

`m connected-device-lib-unit-tests`

### Install resulting apk on device

`adb install -r -t $OUT/testcases/connected-device-lib-unit-tests/arm64/connected-device-lib-unit-tests.apk`

### Run all tests

`adb shell am instrument -w com.android.car.connecteddevice.tests.unit`

### Run tests in a class

`adb shell am instrument -w -e class com.android.car.connecteddevice.<classPath> com.android.car.connecteddevice.tests.unit`

### Run a specific test

`adb shell am instrument -w -e class com.android.car.connecteddevice.<classPath>#<testMethod> com.android.car.connecteddevice.tests.unit`

More general information can be found at
http://developer.android.com/reference/android/support/test/runner/AndroidJUnitRunner.html