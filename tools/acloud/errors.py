# Copyright 2018 - The Android Open Source Project
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
r"""Custom Exceptions for acloud."""

HTTP_NOT_FOUND_CODE = 404


class DriverError(Exception):
    """Base Android Gce driver exception."""


class ConfigError(DriverError):
    """Error related to config."""


class CommandArgError(DriverError):
    """Error related to command line args."""


class GceOperationTimeoutError(DriverError):
    """Error raised when a GCE operation timedout."""


class HttpError(DriverError):
    """Error related to http requests."""

    def __init__(self, code, message):
        self.code = code
        super(HttpError, self).__init__(message)

    @staticmethod
    def CreateFromHttpError(http_error):
        """Create from an apiclient.errors.HttpError.

        Parse the error code from apiclient.errors.HttpError
        and create an instance of HttpError from this module
        that has the error code.

        Args:
            http_error: An apiclient.errors.HttpError instance.

        Returns:
            An HttpError instance from this module.
        """
        return HttpError(http_error.resp.status, str(http_error))


class ResourceNotFoundError(HttpError):
    """Error raised when a resource is not found."""


class InvalidVirtualDeviceIpError(DriverError):
    """Invalid virtual device's IP is set.

    Raise this when the virtual device's IP of an AVD instance is invalid.
    """


class HasRetriableRequestsError(DriverError):
    """Raised when some retriable requests fail in a batch execution."""


class AuthenticationError(DriverError):
    """Raised when authentication fails."""


class DeviceBootError(DriverError):
    """To catch device boot errors."""


class NoSubnetwork(DriverError):
    """When there is no subnetwork for the GCE."""


class DeviceConnectionError(DriverError):
    """To catch device connection errors."""


class DeviceBootTimeoutError(DeviceBootError):
    """Raised when an AVD defice failed to boot within timeout."""


class SetupError(Exception):
    """Base Setup cmd exception."""


class OSTypeError(SetupError):
    """Error related to OS type."""


class NoGoogleSDKDetected(SetupError):
    """Can't find the SDK path."""


class PackageInstallError(SetupError):
    """Error related to package installation."""


class RequiredPackageNotInstalledError(SetupError):
    """Error related to required package not installed."""


class UnableToLocatePkgOnRepositoryError(SetupError):
    """Error related to unable to locate package."""


class NotSupportedPlatformError(SetupError):
    """Error related to user using a not supported os."""


class ParseBucketRegionError(SetupError):
    """Raised when parsing bucket information without region information."""


class CreateError(Exception):
    """Base Create cmd exception."""


class GetAndroidBuildEnvVarError(CreateError):
    """Can't get Android Build set environment variables."""


class CheckPathError(CreateError):
    """Path does not exist."""


class UnsupportedInstanceImageType(CreateError):
    """Unsupported create action for given instance/image type."""


class UnsupportedFlavor(CreateError):
    """Unsupported create action for given flavor name."""


class GetBuildIDError(CreateError):
    """Can't get build id from Android Build."""


class GetBranchFromRepoInfoError(CreateError):
    """Can't get branch information from output of #'repo info'."""


class NotSupportedHWPropertyError(CreateError):
    """An error to wrap a non-supported property issue."""


class MalformedDictStringError(CreateError):
    """Error related to unable to convert string to dict."""


class InvalidHWPropertyError(CreateError):
    """An error to wrap a malformed hw property issue."""


class GetLocalImageError(CreateError):
    """Can't find the local image."""


class GetCvdLocalHostPackageError(CreateError):
    """Can't find the lost host package."""


class NoCuttlefishCommonInstalled(SetupError):
    """Can't find cuttlefish_common lib."""


class UnpackBootImageError(CreateError):
    """Error related to unpack boot.img."""


class BootImgDoesNotExist(CreateError):
    """boot.img does not exist."""


class UnsupportedCompressionFileType(SetupError):
    """Don't support the compression file type."""


class LaunchCVDFail(CreateError):
    """Cuttlefish AVD launch failed."""


class NoExecuteCmd(CreateError):
    """Can't find execute bin command."""


class ReconnectError(Exception):
    """Base reconnect cmd exception."""


class NoInstancesFound(ReconnectError):
    """No instances found."""


class FunctionTimeoutError(Exception):
    """Timeout error of decorator function."""


class ZipImageError(Exception):
    """Zip image error."""
