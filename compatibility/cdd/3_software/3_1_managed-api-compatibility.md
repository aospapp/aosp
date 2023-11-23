## 3.1\. Managed API Compatibility

The managed Dalvik bytecode execution environment is the primary vehicle for
Android applications. The Android application programming interface (API) is the
set of Android platform interfaces exposed to applications running in the
managed runtime environment.

Device implementations:

*    [C-0-1] MUST provide complete implementations, including all documented
     behaviors, of any documented API exposed by the [Android SDK](
     http://developer.android.com/reference/packages.html)
     or any API decorated with the “@SystemApi” marker in the upstream Android
     source code.

*    [C-0-2] MUST support/preserve all classes, methods, and associated elements
     marked by the TestApi annotation (@TestApi).

*    [C-0-3] MUST NOT omit any managed APIs, alter API interfaces or signatures,
     deviate from the documented behavior, or include no-ops, except where
     specifically allowed by this Compatibility Definition.

*    [C-0-4] MUST still keep the APIs present and behave
     in a reasonable way, even when some hardware features for which Android
     includes APIs are omitted. See [section 7](#7_hardware_compatibility)
     for specific requirements for this scenario.

*    [C-0-5] MUST NOT allow third-party apps to use non-SDK interfaces, which
     are defined as methods and fields in the Java language packages that are
     in the boot classpath in AOSP, and that do not form part of the public
     SDK. This includes APIs decorated with the `@hide` annotation but not with
     a `@SystemAPI`, as described in the [SDK documents](https://developer.android.com/distribute/best-practices/develop/restrictions-non-sdk-interfaces)
     and private and package-private class members.

*    [C-0-6] MUST ship with each and every non-SDK interface on the same restricted
     lists as provided via the `greylist`, `greylist-max-o`, `greylist-max-p`,
     and blacklist flags in `prebuilts/runtime/appcompat/hiddenapi-flags.csv`
     path for the appropriate API level branch in the AOSP.

*    [C-0-7] MUST support the [signed config](https://source.android.com/devices/tech/dalvik/signed-config)
     dynamic update mechanism to remove non-SDK interfaces from a restricted list
     by embedding signed configuration in any APK, using the existing public keys
     present in AOSP.

     However they:

     *   MAY, if a hidden API is absent or implemented differently on the device
         implementation, move the hidden API into the blacklist or omit it from
         all restricted lists (i.e. light-grey, dark-grey, black).
     *   MAY, if a hidden API does not already exist in the AOSP, add the hidden
         API to any of the restricted lists (i.e. light-grey, dark-grey, black).


### 3.1.1\. Android Extensions

Android includes the support of extending the managed APIs while keeping the
same API level version.

*   [C-0-1] Android device implementations MUST preload the AOSP implementation
of both the shared library `ExtShared` and services `ExtServices` with versions
higher than or equal to the minimum versions allowed per each API level.
For example, Android 7.0 device implementations, running API level 24 MUST
include at least version 1.

### 3.1.2\. Android Library

Due to [Apache HTTP client deprecation](https://developer.android.com/preview/behavior-changes#apache-p),
device implementations:

*   [C-0-1] MUST NOT place the `org.apache.http.legacy` library in the
bootclasspath.
*   [C-0-2] MUST add the `org.apache.http.legacy` library to the application
classpath only when the app satisfies one of the following conditions:
    *    Targets API level 28 or lower.
    *    Declares in its manifest that it needs the library by setting the
    `android:name` attribute of `<uses-library>` to `org.apache.http.legacy`.

The AOSP implementation meets these requirements.
