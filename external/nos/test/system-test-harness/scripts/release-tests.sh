#!/bin/bash
set -e

source build/envsetup.sh

function integration_tests() {
    m -j citadel_integration_tests || return 1
    adb shell stop || return 1
    adb sync || return 1
    adb shell start || return 1
    adb shell \
        /vendor/bin/hw/citadel_integration_tests --release-tests || return 1
}

# TODO: add AVB / Weaver / Keymaster VTS / CTS tests with filters here.

function oem_lock_vts_tests() {
    atest VtsHalOemLockV1_0TargetTest || return 1
}

function keymaster_cts_tests() {
    atest CtsKeystoreTestCases || return 1
}

function keymaster_vts_tests() {
    m -j VtsHalKeymasterV4_0TargetTest || return 1
    adb sync data || return 1
    adb shell \
        /data/nativetest64/VtsHalKeymasterV4_0TargetTest/VtsHalKeymasterV4_0TargetTest \
        --verbose \
        --hal_service_instance=android.hardware.keymaster@4.0::IKeymasterDevice/strongbox || return 1
}

function weaver_cts_tests() {
  # These CTS tests make a lot of use of Weaver by enrolling and changing
  # credentials. Add omre if you come across them.
  atest com.android.cts.devicepolicy.ManagedProfileTest\#testLockNowWithKeyEviction || return 1
  atest com.android.cts.devicepolicy.DeviceAdminHostSideTestApi24 || return 1
}

function weaver_vts_tests() {
    atest VtsHalWeaverV1_0TargetTest || return 1
}

function auth_secret_vts_tests() {
    atest VtsHalAuthSecretV1_0TargetTest || return 1
}

function pay_cts_tests() {
    runtest --path \
            cts/tests/tests/keystore/src/android/keystore/cts/ImportWrappedKeyTest.java || return 1
}

# TODO: add any other tests

if [ -z "${TARGET_PRODUCT:-}" ]; then
  echo "You need to run the Android setup stuff first"
  exit 1
fi

adb root && adb remount

# keymaster tests need to run before integration tests, which mess
# with factory reset, and break keymaster on-boot info.
for t in \
             keymaster_vts_tests \
             keymaster_cts_tests \
             pay_cts_tests \
             integration_tests \
             oem_lock_vts_tests \
             weaver_cts_tests \
             weaver_vts_tests \
             auth_secret_vts_tests; do
    if eval "${t}"; then
        echo "PASS: ${t}"
    else
        echo "FAIL: ${t}"
        exit 1
    fi
done

# TODO: factory reset the device to original state.
