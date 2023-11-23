# Copyright 2022 - The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Utility functions that process cuttlefish images."""

import collections
import glob
import logging
import os
import posixpath as remote_path
import re
import subprocess
import tempfile

from acloud import errors
from acloud.create import create_common
from acloud.internal import constants
from acloud.internal.lib import ota_tools
from acloud.internal.lib import ssh
from acloud.internal.lib import utils
from acloud.public import report


logger = logging.getLogger(__name__)

# Local build artifacts to be uploaded.
_ARTIFACT_FILES = ["*.img", "bootloader", "kernel"]
# The boot image name pattern corresponds to the use cases:
# - In a cuttlefish build environment, ANDROID_PRODUCT_OUT conatins boot.img
#   and boot-debug.img. The former is the default boot image. The latter is not
#   useful for cuttlefish.
# - In an officially released GKI (Generic Kernel Image) package, the image
#   name is boot-<kernel version>.img.
_BOOT_IMAGE_NAME_PATTERN = r"boot(-[\d.]+)?\.img"
_VENDOR_BOOT_IMAGE_NAME = "vendor_boot.img"
_KERNEL_IMAGE_NAMES = ("kernel", "bzImage", "Image")
_INITRAMFS_IMAGE_NAME = "initramfs.img"
_VENDOR_IMAGE_NAMES = ("vendor.img", "vendor_dlkm.img", "odm.img",
                       "odm_dlkm.img")
VendorImagePaths = collections.namedtuple(
    "VendorImagePaths",
    ["vendor", "vendor_dlkm", "odm", "odm_dlkm"])

# The relative path to the base directory containing cuttelfish images, tools,
# and runtime files. On a GCE instance, the directory is the SSH user's HOME.
GCE_BASE_DIR = "."
_REMOTE_HOST_BASE_DIR_FORMAT = "acloud_cf_%(num)d"
# Relative paths in a base directory.
_REMOTE_IMAGE_DIR = "acloud_image"
_REMOTE_BOOT_IMAGE_PATH = remote_path.join(_REMOTE_IMAGE_DIR, "boot.img")
_REMOTE_VENDOR_BOOT_IMAGE_PATH = remote_path.join(
    _REMOTE_IMAGE_DIR, _VENDOR_BOOT_IMAGE_NAME)
_REMOTE_VBMETA_IMAGE_PATH = remote_path.join(_REMOTE_IMAGE_DIR, "vbmeta.img")
_REMOTE_KERNEL_IMAGE_PATH = remote_path.join(
    _REMOTE_IMAGE_DIR, _KERNEL_IMAGE_NAMES[0])
_REMOTE_INITRAMFS_IMAGE_PATH = remote_path.join(
    _REMOTE_IMAGE_DIR, _INITRAMFS_IMAGE_NAME)
_REMOTE_SUPER_IMAGE_DIR = remote_path.join(_REMOTE_IMAGE_DIR,
                                           "super_image_dir")

# Remote host instance name
_REMOTE_HOST_INSTANCE_NAME_FORMAT = (
    constants.INSTANCE_TYPE_HOST +
    "-%(ip_addr)s-%(num)d-%(build_id)s-%(build_target)s")
_REMOTE_HOST_INSTANCE_NAME_PATTERN = re.compile(
    constants.INSTANCE_TYPE_HOST + r"-(?P<ip_addr>[\d.]+)-(?P<num>\d+)-.+")
# launch_cvd arguments.
_DATA_POLICY_CREATE_IF_MISSING = "create_if_missing"
_DATA_POLICY_ALWAYS_CREATE = "always_create"
_NUM_AVDS_ARG = "-num_instances=%(num_AVD)s"
AGREEMENT_PROMPT_ARG = "-report_anonymous_usage_stats=y"
UNDEFOK_ARG = "-undefok=report_anonymous_usage_stats,config"
# Connect the OpenWrt device via console file.
_ENABLE_CONSOLE_ARG = "-console=true"
# WebRTC args
_WEBRTC_ID = "--webrtc_device_id=%(instance)s"
_WEBRTC_ARGS = ["--start_webrtc", "--vm_manager=crosvm"]
_VNC_ARGS = ["--start_vnc_server=true"]

# Cuttlefish runtime directory is specified by `-instance_dir <runtime_dir>`.
# Cuttlefish tools may create a symbolic link at the specified path.
# The actual location of the runtime directory depends on the version:
#
# In Android 10, the directory is `<runtime_dir>`.
#
# In Android 11 and 12, the directory is `<runtime_dir>.<num>`.
# `<runtime_dir>` is a symbolic link to the first device's directory.
#
# In the latest version, if `--instance-dir <runtime_dir>` is specified, the
# directory is `<runtime_dir>/instances/cvd-<num>`.
# `<runtime_dir>_runtime` and `<runtime_dir>.<num>` are symbolic links.
#
# If `--instance-dir <runtime_dir>` is not specified, the directory is
# `~/cuttlefish/instances/cvd-<num>`.
# `~/cuttlefish_runtime` and `~/cuttelfish_runtime.<num>` are symbolic links.
_LOCAL_LOG_DIR_FORMAT = os.path.join(
    "%(runtime_dir)s", "instances", "cvd-%(num)d", "logs")
# Relative paths in a base directory.
_REMOTE_RUNTIME_DIR_FORMAT = remote_path.join(
    "cuttlefish", "instances", "cvd-%(num)d")
_REMOTE_LEGACY_RUNTIME_DIR_FORMAT = "cuttlefish_runtime.%(num)d"
HOST_KERNEL_LOG = report.LogFile(
    "/var/log/kern.log", constants.LOG_TYPE_KERNEL_LOG, "host_kernel.log")

# Contents of the target_files archive.
_DOWNLOAD_MIX_IMAGE_NAME = "{build_target}-target_files-{build_id}.zip"
_TARGET_FILES_META_DIR_NAME = "META"
_TARGET_FILES_IMAGES_DIR_NAME = "IMAGES"
_MISC_INFO_FILE_NAME = "misc_info.txt"

# ARM flavor build target pattern.
_ARM_TARGET_PATTERN = "arm"


def GetAdbPorts(base_instance_num, num_avds_per_instance):
    """Get ADB ports of cuttlefish.

    Args:
        base_instance_num: An integer or None, the instance number of the first
                           device.
        num_avds_per_instance: An integer or None, the number of devices.

    Returns:
        The port numbers as a list of integers.
    """
    return [constants.CF_ADB_PORT + (base_instance_num or 1) - 1 + index
            for index in range(num_avds_per_instance or 1)]

def GetFastbootPorts(base_instance_num, num_avds_per_instance):
    """Get Fastboot ports of cuttlefish.

    Args:
        base_instance_num: An integer or None, the instance number of the first
                           device.
        num_avds_per_instance: An integer or None, the number of devices.

    Returns:
        The port numbers as a list of integers.
    """
    return [constants.CF_FASTBOOT_PORT + (base_instance_num or 1) - 1 + index
            for index in range(num_avds_per_instance or 1)]

def GetVncPorts(base_instance_num, num_avds_per_instance):
    """Get VNC ports of cuttlefish.

    Args:
        base_instance_num: An integer or None, the instance number of the first
                           device.
        num_avds_per_instance: An integer or None, the number of devices.

    Returns:
        The port numbers as a list of integers.
    """
    return [constants.CF_VNC_PORT + (base_instance_num or 1) - 1 + index
            for index in range(num_avds_per_instance or 1)]


def _UploadImageZip(ssh_obj, remote_dir, image_zip):
    """Upload an image zip to a remote host and a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        image_zip: The path to the image zip.
    """
    remote_cmd = f"/usr/bin/install_zip.sh {remote_dir} < {image_zip}"
    logger.debug("remote_cmd:\n %s", remote_cmd)
    ssh_obj.Run(remote_cmd)


def _UploadImageDir(ssh_obj, remote_dir, image_dir):
    """Upload an image directory to a remote host or a GCE instance.

    The images are compressed for faster upload.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        image_dir: The directory containing the files to be uploaded.
    """
    try:
        images_path = os.path.join(image_dir, "required_images")
        with open(images_path, "r", encoding="utf-8") as images:
            artifact_files = images.read().splitlines()
    except IOError:
        # Older builds may not have a required_images file. In this case
        # we fall back to *.img.
        artifact_files = []
        for file_name in _ARTIFACT_FILES:
            artifact_files.extend(
                os.path.basename(image) for image in glob.glob(
                    os.path.join(image_dir, file_name)))
    # Upload android-info.txt to parse config value.
    artifact_files.append(constants.ANDROID_INFO_FILE)
    cmd = (f"tar -cf - --lzop -S -C {image_dir} {' '.join(artifact_files)} | "
           f"{ssh_obj.GetBaseCmd(constants.SSH_BIN)} -- "
           f"tar -xf - --lzop -S -C {remote_dir}")
    logger.debug("cmd:\n %s", cmd)
    ssh.ShellCmdWithRetry(cmd)


def _UploadCvdHostPackage(ssh_obj, remote_dir, cvd_host_package):
    """Upload a CVD host package to a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        cvd_host_package: The path to the CVD host package.
    """
    if cvd_host_package.endswith(".tar.gz"):
        remote_cmd = f"tar -xzf - -C {remote_dir} < {cvd_host_package}"
        logger.debug("remote_cmd:\n %s", remote_cmd)
        ssh_obj.Run(remote_cmd)
    else:
        cmd = (f"tar -cf - --lzop -S -C {cvd_host_package} . | "
               f"{ssh_obj.GetBaseCmd(constants.SSH_BIN)} -- "
               f"tar -xf - --lzop -S -C {remote_dir}")
        logger.debug("cmd:\n %s", cmd)
        ssh.ShellCmdWithRetry(cmd)


@utils.TimeExecute(function_description="Processing and uploading local images")
def UploadArtifacts(ssh_obj, remote_dir, image_path, cvd_host_package):
    """Upload images and a CVD host package to a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        image_path: A string, the path to the image zip built by `m dist` or
                    the directory containing the images built by `m`.
        cvd_host_package: A string, the path to the CVD host package in gzip.
    """
    if os.path.isdir(image_path):
        _UploadImageDir(ssh_obj, remote_dir, image_path)
    else:
        _UploadImageZip(ssh_obj, remote_dir, image_path)
    _UploadCvdHostPackage(ssh_obj, remote_dir, cvd_host_package)


def FindBootImages(search_path):
    """Find boot and vendor_boot images in a path.

    Args:
        search_path: A path to an image file or an image directory.

    Returns:
        The boot image path and the vendor_boot image path. Each value can be
        None if the path doesn't exist.

    Raises:
        errors.GetLocalImageError if search_path contains more than one boot
        image or the file format is not correct.
    """
    boot_image_path = create_common.FindBootImage(search_path,
                                                  raise_error=False)
    vendor_boot_image_path = os.path.join(search_path, _VENDOR_BOOT_IMAGE_NAME)
    if not os.path.isfile(vendor_boot_image_path):
        vendor_boot_image_path = None

    return boot_image_path, vendor_boot_image_path


def FindKernelImages(search_path):
    """Find kernel and initramfs images in a path.

    Args:
        search_path: A path to an image directory.

    Returns:
        The kernel image path and the initramfs image path. Each value can be
        None if the path doesn't exist.
    """
    paths = [os.path.join(search_path, name) for name in _KERNEL_IMAGE_NAMES]
    kernel_image_path = next((path for path in paths if os.path.isfile(path)),
                             None)

    initramfs_image_path = os.path.join(search_path, _INITRAMFS_IMAGE_NAME)
    if not os.path.isfile(initramfs_image_path):
        initramfs_image_path = None

    return kernel_image_path, initramfs_image_path


@utils.TimeExecute(function_description="Uploading local kernel images.")
def _UploadKernelImages(ssh_obj, remote_dir, search_path):
    """Find and upload kernel or boot images to a remote host or a GCE
    instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        search_path: A path to an image file or an image directory.

    Returns:
        A list of strings, the launch_cvd arguments including the remote paths.

    Raises:
        errors.GetLocalImageError if search_path does not contain kernel
        images.
    """
    # Assume that the caller cleaned up the remote home directory.
    ssh_obj.Run("mkdir -p " + remote_path.join(remote_dir, _REMOTE_IMAGE_DIR))

    kernel_image_path, initramfs_image_path = FindKernelImages(search_path)
    if kernel_image_path and initramfs_image_path:
        remote_kernel_image_path = remote_path.join(
            remote_dir, _REMOTE_KERNEL_IMAGE_PATH)
        remote_initramfs_image_path = remote_path.join(
            remote_dir, _REMOTE_INITRAMFS_IMAGE_PATH)
        ssh_obj.ScpPushFile(kernel_image_path, remote_kernel_image_path)
        ssh_obj.ScpPushFile(initramfs_image_path, remote_initramfs_image_path)
        return ["-kernel_path", remote_kernel_image_path,
                "-initramfs_path", remote_initramfs_image_path]

    boot_image_path, vendor_boot_image_path = FindBootImages(search_path)
    if boot_image_path:
        remote_boot_image_path = remote_path.join(
            remote_dir, _REMOTE_BOOT_IMAGE_PATH)
        ssh_obj.ScpPushFile(boot_image_path, remote_boot_image_path)
        launch_cvd_args = ["-boot_image", remote_boot_image_path]
        if vendor_boot_image_path:
            remote_vendor_boot_image_path = remote_path.join(
                remote_dir, _REMOTE_VENDOR_BOOT_IMAGE_PATH)
            ssh_obj.ScpPushFile(vendor_boot_image_path,
                                remote_vendor_boot_image_path)
            launch_cvd_args.extend(["-vendor_boot_image",
                                    remote_vendor_boot_image_path])
        return launch_cvd_args

    raise errors.GetLocalImageError(
        f"{search_path} is not a boot image or a directory containing images.")


@utils.TimeExecute(function_description="Uploading disabled vbmeta image.")
def _UploadDisabledVbmetaImage(ssh_obj, remote_dir, local_tool_dirs):
    """Upload disabled vbmeta image to a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        local_tool_dirs: A list of local directories containing tools.

    Returns:
        A list of strings, the launch_cvd arguments including the remote paths.

    Raises:
        CheckPathError if local_tool_dirs do not contain OTA tools.
    """
    # Assume that the caller cleaned up the remote home directory.
    ssh_obj.Run("mkdir -p " + remote_path.join(remote_dir, _REMOTE_IMAGE_DIR))

    remote_vbmeta_image_path = remote_path.join(remote_dir,
                                                _REMOTE_VBMETA_IMAGE_PATH)
    with tempfile.NamedTemporaryFile(prefix="vbmeta",
                                     suffix=".img") as temp_file:
        tool_dirs = local_tool_dirs + create_common.GetNonEmptyEnvVars(
                constants.ENV_ANDROID_SOONG_HOST_OUT,
                constants.ENV_ANDROID_HOST_OUT)
        ota = ota_tools.FindOtaTools(tool_dirs)
        ota.MakeDisabledVbmetaImage(temp_file.name)
        ssh_obj.ScpPushFile(temp_file.name, remote_vbmeta_image_path)

    return ["-vbmeta_image", remote_vbmeta_image_path]


def UploadExtraImages(ssh_obj, remote_dir, avd_spec):
    """Find and upload the images specified in avd_spec.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        avd_spec: An AvdSpec object containing extra image paths.

    Returns:
        A list of strings, the launch_cvd arguments including the remote paths.

    Raises:
        errors.GetLocalImageError if any specified image path does not exist.
    """
    extra_img_args = []
    if avd_spec.local_kernel_image:
        extra_img_args += _UploadKernelImages(ssh_obj, remote_dir,
                                              avd_spec.local_kernel_image)
    if avd_spec.local_vendor_image:
        extra_img_args += _UploadDisabledVbmetaImage(ssh_obj, remote_dir,
                                                     avd_spec.local_tool_dirs)
    return extra_img_args


@utils.TimeExecute(function_description="Uploading local super image")
def UploadSuperImage(ssh_obj, remote_dir, super_image_path):
    """Upload a super image to a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        super_image_path: Path to the super image file.

    Returns:
        A list of strings, the launch_cvd arguments including the remote paths.
    """
    # Assume that the caller cleaned up the remote home directory.
    super_image_stem = os.path.basename(super_image_path)
    remote_super_image_dir = remote_path.join(
        remote_dir, _REMOTE_SUPER_IMAGE_DIR)
    remote_super_image_path = remote_path.join(
        remote_super_image_dir, super_image_stem)
    ssh_obj.Run(f"mkdir -p {remote_super_image_dir}")
    cmd = (f"tar -cf - --lzop -S -C {os.path.dirname(super_image_path)} "
           f"{super_image_stem} | "
           f"{ssh_obj.GetBaseCmd(constants.SSH_BIN)} -- "
           f"tar -xf - --lzop -S -C {remote_super_image_dir}")
    ssh.ShellCmdWithRetry(cmd)
    launch_cvd_args = ["-super_image", remote_super_image_path]
    return launch_cvd_args


def CleanUpRemoteCvd(ssh_obj, remote_dir, raise_error):
    """Call stop_cvd and delete the files on a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        raise_error: Whether to raise an error if the remote instance is not
                     running.

    Raises:
        subprocess.CalledProcessError if any command fails.
    """
    home = remote_path.join("$HOME", remote_dir)
    stop_cvd_path = remote_path.join(remote_dir, "bin", "stop_cvd")
    stop_cvd_cmd = f"'HOME={home} {stop_cvd_path}'"
    if raise_error:
        ssh_obj.Run(stop_cvd_cmd)
    else:
        try:
            ssh_obj.Run(stop_cvd_cmd, retry=0)
        except Exception as e:
            logger.debug(
                "Failed to stop_cvd (possibly no running device): %s", e)

    # This command deletes all files except hidden files under HOME.
    # It does not raise an error if no files can be deleted.
    ssh_obj.Run(f"'rm -rf {remote_path.join(remote_dir, '*')}'")


def GetRemoteHostBaseDir(base_instance_num):
    """Get remote base directory by instance number.

    Args:
        base_instance_num: Integer or None, the instance number of the device.

    Returns:
        The remote base directory.
    """
    return _REMOTE_HOST_BASE_DIR_FORMAT % {"num": base_instance_num or 1}


def FormatRemoteHostInstanceName(ip_addr, base_instance_num, build_id,
                                 build_target):
    """Convert an IP address and build info to an instance name.

    Args:
        ip_addr: String, the IP address of the remote host.
        base_instance_num: Integer or None, the instance number of the device.
        build_id: String, the build id.
        build_target: String, the build target, e.g., aosp_cf_x86_64_phone.

    Return:
        String, the instance name.
    """
    return _REMOTE_HOST_INSTANCE_NAME_FORMAT % {
        "ip_addr": ip_addr,
        "num": base_instance_num or 1,
        "build_id": build_id,
        "build_target": build_target}


def ParseRemoteHostAddress(instance_name):
    """Parse IP address from a remote host instance name.

    Args:
        instance_name: String, the instance name.

    Returns:
        The IP address and the base directory as strings.
        None if the name does not represent a remote host instance.
    """
    match = _REMOTE_HOST_INSTANCE_NAME_PATTERN.fullmatch(instance_name)
    if match:
        return (match.group("ip_addr"),
                GetRemoteHostBaseDir(int(match.group("num"))))
    return None


# pylint:disable=too-many-branches
def GetLaunchCvdArgs(avd_spec, config=None):
    """Get launch_cvd arguments for remote instances.

    Args:
        avd_spec: An AVDSpec instance.
        config: A string, the name of the predefined hardware config.
                e.g., "auto", "phone", and "tv".

    Returns:
        A list of strings, arguments of launch_cvd.
    """
    launch_cvd_args = []

    blank_data_disk_size_gb = avd_spec.cfg.extra_data_disk_size_gb
    if blank_data_disk_size_gb and blank_data_disk_size_gb > 0:
        launch_cvd_args.append(
            "-data_policy=" + _DATA_POLICY_CREATE_IF_MISSING)
        launch_cvd_args.append(
            "-blank_data_image_mb=" + str(blank_data_disk_size_gb * 1024))

    if config:
        launch_cvd_args.append("-config=" + config)
    if avd_spec.hw_customize or not config:
        launch_cvd_args.append(
            "-x_res=" + avd_spec.hw_property[constants.HW_X_RES])
        launch_cvd_args.append(
            "-y_res=" + avd_spec.hw_property[constants.HW_Y_RES])
        launch_cvd_args.append(
            "-dpi=" + avd_spec.hw_property[constants.HW_ALIAS_DPI])
        if constants.HW_ALIAS_DISK in avd_spec.hw_property:
            launch_cvd_args.append(
                "-data_policy=" + _DATA_POLICY_ALWAYS_CREATE)
            launch_cvd_args.append(
                "-blank_data_image_mb="
                + avd_spec.hw_property[constants.HW_ALIAS_DISK])
        if constants.HW_ALIAS_CPUS in avd_spec.hw_property:
            launch_cvd_args.append(
                "-cpus=" + str(avd_spec.hw_property[constants.HW_ALIAS_CPUS]))
        if constants.HW_ALIAS_MEMORY in avd_spec.hw_property:
            launch_cvd_args.append(
                "-memory_mb=" +
                str(avd_spec.hw_property[constants.HW_ALIAS_MEMORY]))

    if avd_spec.connect_webrtc:
        launch_cvd_args.extend(_WEBRTC_ARGS)
        if avd_spec.webrtc_device_id:
            launch_cvd_args.append(
                _WEBRTC_ID % {"instance": avd_spec.webrtc_device_id})
    if avd_spec.connect_vnc:
        launch_cvd_args.extend(_VNC_ARGS)
    if avd_spec.openwrt:
        launch_cvd_args.append(_ENABLE_CONSOLE_ARG)
    if avd_spec.num_avds_per_instance > 1:
        launch_cvd_args.append(
            _NUM_AVDS_ARG % {"num_AVD": avd_spec.num_avds_per_instance})
    if avd_spec.base_instance_num:
        launch_cvd_args.append(
            "--base-instance-num=" + str(avd_spec.base_instance_num))
    if avd_spec.launch_args:
        launch_cvd_args.append(avd_spec.launch_args)

    launch_cvd_args.append(UNDEFOK_ARG)
    launch_cvd_args.append(AGREEMENT_PROMPT_ARG)
    return launch_cvd_args


def _GetRemoteRuntimeDirs(ssh_obj, remote_dir, base_instance_num,
                          num_avds_per_instance):
    """Get cuttlefish runtime directories on a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        base_instance_num: An integer, the instance number of the first device.
        num_avds_per_instance: An integer, the number of devices.

    Returns:
        A list of strings, the paths to the runtime directories.
    """
    runtime_dir = remote_path.join(
        remote_dir, _REMOTE_RUNTIME_DIR_FORMAT % {"num": base_instance_num})
    try:
        ssh_obj.Run(f"test -d {runtime_dir}", retry=0)
        return [remote_path.join(remote_dir,
                                 _REMOTE_RUNTIME_DIR_FORMAT %
                                 {"num": base_instance_num + num})
                for num in range(num_avds_per_instance)]
    except subprocess.CalledProcessError:
        logger.debug("%s is not the runtime directory.", runtime_dir)

    legacy_runtime_dirs = [
        remote_path.join(remote_dir, constants.REMOTE_LOG_FOLDER)]
    legacy_runtime_dirs.extend(
        remote_path.join(remote_dir,
                         _REMOTE_LEGACY_RUNTIME_DIR_FORMAT %
                         {"num": base_instance_num + num})
        for num in range(1, num_avds_per_instance))
    return legacy_runtime_dirs


def GetRemoteFetcherConfigJson(remote_dir):
    """Get the config created by fetch_cvd on a remote host or a GCE instance.

    Args:
        remote_dir: The remote base directory.

    Returns:
        An object of report.LogFile.
    """
    return report.LogFile(remote_path.join(remote_dir, "fetcher_config.json"),
                          constants.LOG_TYPE_CUTTLEFISH_LOG)


def _GetRemoteTombstone(runtime_dir, name_suffix):
    """Get log object for tombstones in a remote cuttlefish runtime directory.

    Args:
        runtime_dir: The path to the remote cuttlefish runtime directory.
        name_suffix: The string appended to the log name. It is used to
                     distinguish log files found in different runtime_dirs.

    Returns:
        A report.LogFile object.
    """
    return report.LogFile(remote_path.join(runtime_dir, "tombstones"),
                          constants.LOG_TYPE_DIR,
                          "tombstones-zip" + name_suffix)


def _GetLogType(file_name):
    """Determine log type by file name.

    Args:
        file_name: A file name.

    Returns:
        A string, one of the log types defined in constants.
        None if the file is not a log file.
    """
    if file_name == "kernel.log":
        return constants.LOG_TYPE_KERNEL_LOG
    if file_name == "logcat":
        return constants.LOG_TYPE_LOGCAT
    if file_name.endswith(".log") or file_name == "cuttlefish_config.json":
        return constants.LOG_TYPE_CUTTLEFISH_LOG
    return None


def FindRemoteLogs(ssh_obj, remote_dir, base_instance_num,
                   num_avds_per_instance):
    """Find log objects on a remote host or a GCE instance.

    Args:
        ssh_obj: An Ssh object.
        remote_dir: The remote base directory.
        base_instance_num: An integer or None, the instance number of the first
                           device.
        num_avds_per_instance: An integer or None, the number of devices.

    Returns:
        A list of report.LogFile objects.
    """
    runtime_dirs = _GetRemoteRuntimeDirs(
        ssh_obj, remote_dir,
        (base_instance_num or 1), (num_avds_per_instance or 1))
    logs = []
    for log_path in utils.FindRemoteFiles(ssh_obj, runtime_dirs):
        file_name = remote_path.basename(log_path)
        log_type = _GetLogType(file_name)
        if not log_type:
            continue
        base, ext = remote_path.splitext(file_name)
        # The index of the runtime_dir containing log_path.
        index_str = ""
        for index, runtime_dir in enumerate(runtime_dirs):
            if log_path.startswith(runtime_dir + remote_path.sep):
                index_str = "." + str(index) if index else ""
        log_name = ("full_gce_logcat" + index_str if file_name == "logcat" else
                    base + index_str + ext)

        logs.append(report.LogFile(log_path, log_type, log_name))

    logs.extend(_GetRemoteTombstone(runtime_dir,
                                    ("." + str(index) if index else ""))
                for index, runtime_dir in enumerate(runtime_dirs))
    return logs


def FindLocalLogs(runtime_dir, instance_num):
    """Find log objects in a local runtime directory.

    Args:
        runtime_dir: A string, the runtime directory path.
        instance_num: An integer, the instance number.

    Returns:
        A list of report.LogFile.
    """
    log_dir = _LOCAL_LOG_DIR_FORMAT % {"runtime_dir": runtime_dir,
                                       "num": instance_num}
    if not os.path.isdir(log_dir):
        log_dir = runtime_dir

    logs = []
    for parent_dir, _, file_names in os.walk(log_dir, followlinks=False):
        for file_name in file_names:
            log_path = os.path.join(parent_dir, file_name)
            log_type = _GetLogType(file_name)
            if os.path.islink(log_path) or not log_type:
                continue
            logs.append(report.LogFile(log_path, log_type))
    return logs


def GetRemoteBuildInfoDict(avd_spec):
    """Convert remote build infos to a dictionary for reporting.

    Args:
        avd_spec: An AvdSpec object containing the build infos.

    Returns:
        A dict containing the build infos.
    """
    build_info_dict = {
        key: val for key, val in avd_spec.remote_image.items() if val}

    # kernel_target has a default value. If the user provides kernel_build_id
    # or kernel_branch, then convert kernel build info.
    if (avd_spec.kernel_build_info.get(constants.BUILD_ID) or
            avd_spec.kernel_build_info.get(constants.BUILD_BRANCH)):
        build_info_dict.update(
            {"kernel_" + key: val
             for key, val in avd_spec.kernel_build_info.items() if val}
        )
    build_info_dict.update(
        {"system_" + key: val
         for key, val in avd_spec.system_build_info.items() if val}
    )
    build_info_dict.update(
        {"bootloader_" + key: val
         for key, val in avd_spec.bootloader_build_info.items() if val}
    )
    return build_info_dict


def GetMixBuildTargetFilename(build_target, build_id):
    """Get the mix build target filename.

    Args:
        build_id: String, Build id, e.g. "2263051", "P2804227"
        build_target: String, the build target, e.g. cf_x86_phone-userdebug

    Returns:
        String, a file name, e.g. "cf_x86_phone-target_files-2263051.zip"
    """
    return _DOWNLOAD_MIX_IMAGE_NAME.format(
        build_target=build_target.split('-')[0],
        build_id=build_id)


def FindMiscInfo(image_dir):
    """Find misc info in build output dir or extracted target files.

    Args:
        image_dir: The directory to search for misc info.

    Returns:
        image_dir if the directory structure looks like an output directory
        in build environment.
        image_dir/META if it looks like extracted target files.

    Raises:
        errors.CheckPathError if this function cannot find misc info.
    """
    misc_info_path = os.path.join(image_dir, _MISC_INFO_FILE_NAME)
    if os.path.isfile(misc_info_path):
        return misc_info_path
    misc_info_path = os.path.join(image_dir, _TARGET_FILES_META_DIR_NAME,
                                  _MISC_INFO_FILE_NAME)
    if os.path.isfile(misc_info_path):
        return misc_info_path
    raise errors.CheckPathError(
        f"Cannot find {_MISC_INFO_FILE_NAME} in {image_dir}. The "
        f"directory is expected to be an extracted target files zip or "
        f"{constants.ENV_ANDROID_PRODUCT_OUT}.")


def FindImageDir(image_dir):
    """Find images in build output dir or extracted target files.

    Args:
        image_dir: The directory to search for images.

    Returns:
        image_dir if the directory structure looks like an output directory
        in build environment.
        image_dir/IMAGES if it looks like extracted target files.

    Raises:
        errors.GetLocalImageError if this function cannot find any image.
    """
    if glob.glob(os.path.join(image_dir, "*.img")):
        return image_dir
    subdir = os.path.join(image_dir, _TARGET_FILES_IMAGES_DIR_NAME)
    if glob.glob(os.path.join(subdir, "*.img")):
        return subdir
    raise errors.GetLocalImageError(
        "Cannot find images in %s." % image_dir)


def IsArmImage(image):
    """Check if the image is built for ARM.

    Args:
        image: Image meta info.

    Returns:
        A boolean, whether the image is for ARM.
    """
    return _ARM_TARGET_PATTERN in image.get("build_target", "")


def FindVendorImages(image_dir):
    """Find vendor, vendor_dlkm, odm, and odm_dlkm image in build output dir.

    Args:
        image_dir: The directory to search for images.

    Returns:
        An object of VendorImagePaths.

    Raises:
        errors.GetLocalImageError if this function cannot find images.
    """

    image_paths = []
    for image_name in _VENDOR_IMAGE_NAMES:
        image_path = os.path.join(image_dir, image_name)
        if not os.path.isfile(image_path):
            raise errors.GetLocalImageError(
                f"Cannot find {image_path} in {image_dir}.")
        image_paths.append(image_path)

    return VendorImagePaths(*image_paths)
