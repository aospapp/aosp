#ifndef FP_TEST
#define FP_TEST

#include <android/hardware/biometrics/fingerprint/2.1/IBiometricsFingerprint.h>
#include <android/hardware/biometrics/fingerprint/2.1/IBiometricsFingerprintClientCallback.h>

using android::hardware::biometrics::fingerprint::V2_1::IBiometricsFingerprint;
using android::hardware::biometrics::fingerprint::V2_1::IBiometricsFingerprintClientCallback;
using android::hardware::biometrics::fingerprint::V2_1::RequestStatus;
using android::sp;


int main(int argc, char *argv[]);
void toolUsage(void);

#endif //FP_TEST
