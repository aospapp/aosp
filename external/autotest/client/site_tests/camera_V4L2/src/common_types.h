/*
 * Copyright 2015 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#ifndef COMMON_TYPES_H_
#define COMMON_TYPES_H_

#include <string>
#include <vector>

#include <base/logging.h>

#define LOGF(level) LOG(level) << __FUNCTION__ << "(): "
#define VLOGF(level) VLOG(level) << __FUNCTION__ << "(): "

// The definition should match camera_metadata_enum_android_lens_facing_t
// in camera_metadata_tags.h.
enum lens_facing {
  FACING_FRONT,
  FACING_BACK,
};

// The types in this file should match Android camera HAL.

struct DeviceInfo {
  int camera_id;

  // TODO(shik): Change this to base::FilePath.
  // ex: /dev/video0
  std::string device_path;

  // USB vendor id, the emulated vivid devices do not have this field.
  std::string usb_vid;

  // USB product id, the emulated vivid devices do not have this field.
  std::string usb_pid;

  // Some cameras need to wait several frames to output correct images.
  uint32_t frames_to_skip_after_streamon = 0;

  // The camera doesn't support constant frame rate. That means HAL cannot set
  // V4L2_CID_EXPOSURE_AUTO_PRIORITY to 0 to have constant frame rate in low
  // light environment.
  bool constant_framerate_unsupported = false;

  // Member definitions can be found in https://developer.android.com/
  // reference/android/hardware/camera2/CameraCharacteristics.html
  uint32_t lens_facing = FACING_FRONT;
  int32_t sensor_orientation = 0;

  // These fields are not available for external cameras.
  std::vector<float> lens_info_available_apertures;
  std::vector<float> lens_info_available_focal_lengths;
  float lens_info_minimum_focus_distance;
  float lens_info_optimal_focus_distance;
  int32_t sensor_info_pixel_array_size_width;
  int32_t sensor_info_pixel_array_size_height;
  float sensor_info_physical_size_width;
  float sensor_info_physical_size_height;

  // FOV parameters for HAL v1.
  float horizontal_view_angle_16_9;
  float horizontal_view_angle_4_3;
  float vertical_view_angle_16_9;
  float vertical_view_angle_4_3;
};

typedef std::vector<DeviceInfo> DeviceInfos;

struct SupportedFormat {
  SupportedFormat() {}
  SupportedFormat(uint32_t w, uint32_t h, uint32_t fmt, uint32_t fps)
      : width(w), height(h), fourcc(fmt) {
    frame_rates.push_back(fps);
  }
  uint32_t width;
  uint32_t height;
  uint32_t fourcc;
  // All the supported frame rates in fps with given width, height, and
  // pixelformat. This is not sorted. For example, suppose width, height, and
  // fourcc are 640x480 YUYV. If frameRates are 15.0 and 30.0, the camera
  // supports outputting  640X480 YUYV in 15fps or 30fps.
  std::vector<float> frame_rates;
};

typedef std::vector<SupportedFormat> SupportedFormats;

#endif
