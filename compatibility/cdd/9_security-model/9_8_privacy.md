## 9.8\. Privacy

### 9.8.1\. Usage History

Android stores the history of the user's choices and manages such history by
[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager.html).

Device implementations:

*   [C-0-1] MUST keep a reasonable retention period of such user history.
*   [SR] Are STRONGLY RECOMMENDED to keep the 14 days retention period as
    configured by default in the AOSP implementation.

Android stores the system events using the [`StatsLog`](https://developer.android.com/reference/android/util/StatsLog.html)
identifiers, and manages such history via the `StatsManager` and the
`IncidentManager` System API.

Device implementations:

*   [C-0-2] MUST only include the fields marked with `DEST_AUTOMATIC` in the
    incident report created by the System API class `IncidentManager`.
*   [C-0-3] MUST not use the system event identifiers to log any other event
    than what is described in the [`StatsLog`](https://developer.android.com/reference/android/util/StatsLog.html)
    SDK documents. If additional system events are logged, they MAY use a
    different atom identifier in the range between 100,000 and 200,000.

### 9.8.2\. Recording

Device implementations:

*   [C-0-1] MUST NOT preload or distribute software components out-of-box that
    send the user's private information (e.g. keystrokes, text displayed on the
    screen, bugreport) off the device without the user's consent or clear
    ongoing notifications.
*   [C-0-2] MUST display and obtain explicit user consent that includes exactly
    the same message as AOSP whenever screen casting or screen recording is
    enabled via [`MediaProjection`](https://developer.android.com/reference/android/media/projection/MediaProjection)
    or proprietary APIs. MUST NOT provide users an affordance to
    disable future display of the user consent.
*   [C-0-3] MUST have an ongoing notification to the user while screen casting
    or screen recording is enabled. AOSP meets this requirement by showing an
    ongoing notification icon in the status bar.

If device implementations include functionality in the system that either
captures the contents displayed on the screen and/or records the audio stream
played on the device other than via the System API `ContentCaptureService`, or
other proprietary means described in
[Section 9.8.6 Content Capture](#9_8_6_content_capture), they:

*   [C-1-1] MUST have an ongoing notification to the user whenever this
    functionality is enabled and actively capturing/recording.

If device implementations include a component enabled out-of-box, capable of
recording ambient audio and/or record the audio played on the device
to infer useful information about user’s context, they:

*   [C-2-1] MUST NOT store in persistent on-device storage or transmit off the
    device the recorded raw audio or any format that can be converted back into
    the original audio or a near facsimile, except with explicit user consent.

### 9.8.3\. Connectivity

If device implementations have a USB port with USB peripheral mode support,
they:

*   [C-1-1] MUST present a user interface asking for the user's consent before
allowing access to the contents of the shared storage over the USB port.


### 9.8.4\. Network Traffic

Device implementations:

*   [C-0-1] MUST preinstall the same root certificates for the system-trusted
    Certificate Authority (CA) store as [provided](https://source.android.com/security/overview/app-security.html#certificate-authorities)
    in the upstream Android Open Source Project.
*   [C-0-2] MUST ship with an empty user root CA store.
*   [C-0-3] MUST display a warning to the user indicating the network traffic
    may be monitored, when a user root CA is added.

If device traffic is routed through a VPN, device implementations:

*   [C-1-1] MUST display a warning to the user indicating either:
    *   That network traffic may be monitored.
    *   That network traffic is being routed through the specific VPN
        application providing the VPN.

If device implementations have a mechanism, enabled out-of-box by default, that
routes network data traffic through a proxy server or VPN gateway (for example,
preloading a VPN service with `android.permission.CONTROL_VPN` granted), they:

*    [C-2-1] MUST ask for the user's consent before enabling that mechanism,
     unless that VPN is enabled by the Device Policy Controller via the
     [`DevicePolicyManager.setAlwaysOnVpnPackage()`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager.html#setAlwaysOnVpnPackage%28android.content.ComponentName, java.lang.String, boolean%29)
     , in which case the user does not need to provide a separate consent, but
     MUST only be notified.

If device implementations implement a user affordance to toggle on the
"always-on VPN" function of a 3rd-party VPN app, they:

*    [C-3-1] MUST disable this user affordance for apps that do not support
     always-on VPN service in the `AndroidManifest.xml` file via setting the
     [`SERVICE_META_DATA_SUPPORTS_ALWAYS_ON`](https://developer.android.com/reference/android/net/VpnService.html#SERVICE_META_DATA_SUPPORTS_ALWAYS_ON)
     attribute to `false`.

### 9.8.5\. Device Identifiers

Device implementations:

*   [C-0-1] MUST prevent access to the device serial number and, where
    applicable, IMEI/MEID, SIM serial number, and International Mobile
    Subscriber Identity (IMSI) from an app, unless it meets one of the following
    requirements:
    * is a signed carrier app that is verified by device manufacturers.
    * has been granted the `READ_PRIVILEGED_PHONE_STATE` permission.
    * has carrier privileges as defined in [`UICC Carrier Privileges`](https://source.android.com/devices/tech/config/uicc).
    * is a device owner or profile owner that has been granted the
      `READ_PHONE_STATE` permission.

### 9.8.6\. Content Capture

Android, through the System API `ContentCaptureService`, or by other proprietary
means, supports a mechanism for device implementations to capture the
following interactions between the applications and the user.

*    Text and graphics rendered on-screen, including but not limited to,
     notifications and assist data via [`AssistStructure`](
     https://developer.android.com/reference/android/app/assist/AssistStructure)
     API.
*    Media data, such as audio or video, recorded or played by the device.
*    Input events (e.g. key, mouse, gesture, voice, video, and accessibility).
*    Any other events that an application provides to the system via the
     [`Content Capture`](
     https://developer.android.com/reference/android/view/contentcapture/package-summary)
     API or a similarly capable, proprietary API.

If device implementations capture the data above, they:

*    [C-0-1] MUST encrypt all such data when stored in the device. This
     encryption MAY be carried out using Android File Based Encryption, or any
     of the ciphers listed as API version 26+ described in [Cipher SDK](
     https://developer.android.com/reference/javax/crypto/Cipher).
*    [C-0-2] MUST NOT back up either raw or encrypted data using
     [Android backup methods](
     https://developer.android.com/guide/topics/data/backup) or any other back
     up methods.
*    [C-0-3] MUST only send all such data and the log of the device using a
     privacy-preserving mechanism. The privacy-preserving mechanism
     is defined as “those which allow only analysis in aggregate and prevent
     matching of logged events or derived outcomes to individual users”, to
     prevent any per-user data being introspectable (e.g., implemented using
     a differential privacy technology such as [`RAPPOR`](
     https://github.com/google/rappor)).
*    [C-0-4] MUST NOT associate such data with any user identity (such
     as [`Account`](https://developer.android.com/reference/android/accounts/Account))
     on the device, except with explicit user consent each time the data is
     associated.
*    [C-0-5] MUST NOT share such data with other apps, except with
     explicit user consent every time it is shared.
*    [C-0-6] MUST provide user affordance to erase such data that
     the `ContentCaptureService` or the proprietary means collects if the
     data is stored in any form on the device.

If device implementations include a service that implements the System API
`ContentCaptureService`, or any proprietary service that captures the data
as described as above, they:

*    [C-1-1] MUST NOT allow users to replace the content capture service with a
     user-installable application or service and MUST only allow the
     preinstalled service to capture such data.
*    [C-1-2] MUST NOT allow any apps other than the preinstalled content capture
     service mechanism to be able to capture such data.
*    [C-1-3] MUST provide user affordance to disable the content capture
     service.
*    [C-1-4] MUST NOT omit user affordance to manage Android permissions that
     are held by the content capture service and follow Android permissions
     model as described in [Section 9.1. Permission](#9_1_permissions.md).
*    [C-SR] Are STRONGLY RECOMMENDED to keep the content capturing service
     components separate, for example, not binding the service or sharing process
     IDs, from other system components except for the following:

     *    Telephony, Contacts, System UI, and Media

### 9.8.7\. Clipboard Access

Device implementations:

  * [C-0-1] MUST NOT return a clipped data on the clipboard (e.g. via the
    [`ClipboardManager`](
    https://developer.android.com/reference/android/content/ClipboardManager)
    API) unless the app is the default IME or is the app that currently has
    focus.

### 9.8.8\. Location

Device implementations:

*   [C-0-1] MUST NOT turn on/off device location setting and Wi-Fi/Bluetooth
scanning settings without explicit user consent or user initiation.
*   [C-0-2] MUST provide the user affordance to access location related
information including recent location requests, app level permissions and usage
of Wi-Fi/Bluetooth scanning for determining location.
*   [C-0-3] MUST ensure that the application using Emergency Location Bypass API
[LocationRequest.setLocationSettingsIgnored()] is a user initiated emergency
session (e.g. dial 911 or text to 911).
*   [C-0-4] MUST preserve the Emergency Location Bypass API's ability to
bypass device location settings without changing the settings.
*   [C-0-5] MUST schedule a notification that reminds the user after an app in
the background has accessed their location using the
[`ACCESS_BACKGROUND_LOCATION`] permission.
