## 7.6\. Memory and Storage

### 7.6.1\. Minimum Memory and Storage

Device implementations:

*   [C-0-1] MUST include a [Download Manager](
    http://developer.android.com/reference/android/app/DownloadManager.html)
    that applications MAY use to download data files and they MUST be capable of
    downloading individual files of at least 100MB in size to the default
    “cache” location.

### 7.6.2\. Application Shared Storage

Device implementations:

*   [C-0-1] MUST offer storage to be shared by applications, also often referred
    as “shared external storage”, "application shared storage" or by the Linux
    path "/sdcard" it is mounted on.
*   [C-0-2] MUST be configured with shared storage mounted by default, in other
    words “out of the box”, regardless of whether the storage is implemented on
    an internal storage component or a removable storage medium (e.g. Secure
    Digital card slot).
*   [C-0-3] MUST mount the application shared storage directly on the Linux path
    `sdcard` or include a Linux symbolic link from `sdcard` to the actual mount
    point.
*   [C-0-4] MUST enforce the `android.permission.WRITE_EXTERNAL_STORAGE`
    permission on this shared storage as documented in the SDK.
*   [C-0-5] MUST enable [scoped storage](
    https://developer.android.com/privacy/scoped-storage) by default for all
    apps targeting API level 29 or above, except in the following situations:
    *   when the app was installed before the device upgraded to API level 29,
        regardless of the target API of the app.
    *   when the app has requested `android:requestLegacyExternalStorage="true"`
        in their manifest.
    *   when the app is granted the `android.permission.WRITE_MEDIA_STORAGE`
        permission.
*   [C-0-6] MUST enforce that apps with scoped storage enabled have no direct
    filesystem access to files outside of their application-specific
    directories, as returned by [`Context`](
    https://developer.android.com/reference/android/content/Context.html) API
    methods such as [`Context.getExternalFilesDirs()`](
    https://developer.android.com/reference/android/content/Context.html#getExternalFilesDirs%28java.lang.String%29),
    [`Context.getExternalCacheDirs()`](
    https://developer.android.com/reference/android/content/Context.html#getExternalCacheDirs%28%29),
    [`Context.getExternalMediaDirs()`](
    https://developer.android.com/reference/android/content/Context.html#getExternalMediaDirs%28%29),
    and
    [`Context.getObbDirs()`](https://developer.android.com/reference/android/content/Context.html#getObbDirs%28%29) methods.
*   [C-0-7] MUST redact location metadata, such as GPS Exif tags, stored in
    media files when those files are accessed through `MediaStore`, except when
    the calling app holds the `ACCESS_MEDIA_LOCATION` permission.

Device implementations MAY meet the above requirements using either of the
following:

* User-accessible removable storage, such as a Secure Digital (SD) card slot.
* A portion of the internal (non-removable) storage as implemented in the
  Android Open Source Project (AOSP).

If device implementations use removable storage to satisfy the above
requirements, they:

*   [C-1-1] MUST implement a toast or pop-up user interface warning the user
    when there is no storage medium inserted in the slot.
*   [C-1-2] MUST include a FAT-formatted storage medium (e.g. SD card) or show
    on the box and other material available at time of purchase that the storage
    medium has to be purchased separately.

If device implementations use a portion of the non-removable storage to satisfy
the above requirements, they:

*   SHOULD use the AOSP implementation of the internal application shared
    storage.
*   MAY share the storage space with the application private data.

If device implementations include multiple shared storage paths (such
as both an SD card slot and shared internal storage), they:

*   [C-2-1] MUST allow only pre-installed and privileged Android
    applications with the `WRITE_MEDIA_STORAGE` permission to write to the
    secondary external storage, except when writing to their package-specific
    directories or within the `URI` returned by firing the
    `ACTION_OPEN_DOCUMENT_TREE` intent.
*   [C-2-2] MUST require that the direct access associated with the
    `android.permission.WRITE_MEDIA_STORAGE` permission is only given to
    user-visible apps when the `android.permission.WRITE_EXTERNAL_STORAGE`
    permission is also granted.
*   [SR] STRONGLY RECOMMENDED that pre-installed and privileged Android
    applications use public APIs such as `MediaStore` to interact with storage
    devices, instead of relying on the direct access granted by
    `android.permission.WRITE_MEDIA_STORAGE`.

If device implementations have a USB port with USB peripheral mode support,
they:

*   [C-3-1] MUST provide a mechanism to access the data on the application
    shared storage from a host computer.
*   SHOULD expose content from both storage paths transparently through
    Android’s media scanner service and `android.provider.MediaStore`.
*   MAY use USB mass storage, but SHOULD use Media Transfer Protocol to satisfy
    this requirement.

If device implementations have a USB port with USB peripheral mode and support
Media Transfer Protocol, they:

*   SHOULD be compatible with the reference Android MTP host,
[Android File Transfer](http://www.android.com/filetransfer).
*   SHOULD report a USB device class of 0x00.
*   SHOULD report a USB interface name of 'MTP'.

### 7.6.3\. Adoptable Storage

If the device is expected to be mobile in nature unlike Television,
device implementations are:

*   [SR] STRONGLY RECOMMENDED to implement the adoptable storage in
a long-term stable location, since accidentally disconnecting them can
cause data loss/corruption.

If the removable storage device port is in a long-term stable location,
such as within the battery compartment or other protective cover,
device implementations are:

*   [SR] STRONGLY RECOMMENDED to implement
[adoptable storage](http://source.android.com/devices/storage/adoptable.html).
