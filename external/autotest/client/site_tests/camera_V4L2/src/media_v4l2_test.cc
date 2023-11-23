// Copyright (c) 2010 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <getopt.h>
#include <libyuv.h>
#include <math.h>

#include <cmath>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include "base/at_exit.h"
#include "base/command_line.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "gtest/gtest.h"

#include "camera_characteristics.h"
#include "common_types.h"
#include "media_v4l2_device.h"

struct TestCropping {
  bool check_cropping = false;
  uint32_t sensor_pixel_array_size_width = 0;
  uint32_t sensor_pixel_array_size_height = 0;
};

struct TestProfile {
  std::string test_list;
  std::string dev_name;
  bool check_1280x960 = false;
  bool check_1600x1200 = false;
  bool check_constant_framerate = false;
  bool check_maximum_resolution = false;
  bool support_constant_framerate = false;
  TestCropping cropping_profile;
  uint32_t skip_frames = 0;
  uint32_t lens_facing = FACING_FRONT;
};

/* Test lists:
 * default: for devices without ARC++, and devices with ARC++ which use
 *          camera HAL v1.
 * halv3: for devices with ARC++ which use camera HAL v3.
 * certification: for third-party labs to verify new camera modules.
 */
static const char kDefaultTestList[] = "default";
static const char kHalv3TestList[] = "halv3";
static const char kCertificationTestList[] = "certification";

/* Camera Facing */
static const char kFrontCamera[] = "user";
static const char kBackCamera[] = "world";

struct TestProfile g_profile;

static void PrintUsage(int argc, char** argv) {
  printf("Usage: %s [options]\n\n"
         "Options:\n"
         "--help               Print usage\n"
         "--device=DEVICE_NAME Video device name [/dev/video]\n"
         "--usb-info=VID:PID   Device vendor id and product id\n"
         "--test-list=TEST     Select different test list\n"
         "                     [%s | %s | %s]\n",
         argv[0], kDefaultTestList, kHalv3TestList,
         kCertificationTestList);
}

int RunTest(V4L2Device* device, V4L2Device::IOMethod io,
            uint32_t buffers, uint32_t capture_time_in_sec, uint32_t width,
            uint32_t height, uint32_t pixfmt, float fps,
            V4L2Device::ConstantFramerate constant_framerate,
            uint32_t skip_frames) {
  int32_t retcode = 0;
  if (!device->InitDevice(io, width, height, pixfmt, fps, constant_framerate,
                          skip_frames))
    retcode = 1;

  if (!retcode && !device->StartCapture())
    retcode = 2;

  if (!retcode && !device->Run(capture_time_in_sec))
    retcode = 3;

  if (!device->StopCapture())
    retcode = 4;

  if (!device->UninitDevice())
    retcode = 5;

  return retcode;
}

bool GetSupportedFormats(
    V4L2Device* device, SupportedFormats* supported_formats) {
  supported_formats->clear();

  SupportedFormat format;
  uint32_t num_format = 0;
  device->EnumFormat(&num_format, false);
  for (uint32_t i = 0; i < num_format; ++i) {
    if (!device->GetPixelFormat(i, &format.fourcc)) {
      printf("[Error] Get format error\n");
      return false;
    }
    uint32_t num_frame_size;
    if (!device->EnumFrameSize(format.fourcc, &num_frame_size, false)) {
      printf("[Error] Enumerate frame size error\n");
      return false;
    };

    for (uint32_t j = 0; j < num_frame_size; ++j) {
      if (!device->GetFrameSize(j, format.fourcc, &format.width,
                                &format.height)) {
        printf("[Error] Get frame size error\n");
        return false;
      };
      uint32_t num_frame_rate;
      if (!device->EnumFrameInterval(format.fourcc, format.width,
                                     format.height, &num_frame_rate, false)) {
        printf("[Error] Enumerate frame interval error\n");
        return false;
      };

      format.frame_rates.clear();
      float frame_rate;
      for (uint32_t k = 0; k < num_frame_rate; ++k) {
        if (!device->GetFrameInterval(k, format.fourcc, format.width,
                                      format.height, &frame_rate)) {
          printf("[Error] Get frame interval error\n");
          return false;
        };
        // All supported resolution should have at least 1 fps.
        if (frame_rate < 1.0) {
          printf("[Error] Frame rate should be at least 1.\n");
          return false;
        }
        format.frame_rates.push_back(frame_rate);
      }
      supported_formats->push_back(format);
    }
  }
  return true;
}

SupportedFormat GetMaximumResolution(const SupportedFormats& formats) {
  SupportedFormat max_format;
  memset(&max_format, 0, sizeof(max_format));
  for (const auto& format : formats) {
    if (format.width >= max_format.width) {
      max_format.width = format.width;
    }
    if (format.height >= max_format.height) {
      max_format.height = format.height;
    }
  }
  return max_format;
}

// Find format according to width and height. If multiple formats support the
// same resolution, choose V4L2_PIX_FMT_MJPEG first.
const SupportedFormat* FindFormatByResolution(const SupportedFormats& formats,
                                              uint32_t width,
                                              uint32_t height) {
  const SupportedFormat* result_format = nullptr;
  for (const auto& format : formats) {
    if (format.width == width && format.height == height) {
      if (!result_format || format.fourcc == V4L2_PIX_FMT_MJPEG) {
        result_format = &format;
      }
    }
  }
  return result_format;
}

// Find format according to V4L2 fourcc. If multiple resolution support the
// same fourcc, choose the first one.
const SupportedFormat* FindFormatByFourcc(const SupportedFormats& formats,
                                          uint32_t fourcc) {
  for (const auto& format : formats) {
    if (format.fourcc == fourcc) {
      return &format;
    }
  }
  return nullptr;
}

const SupportedFormat* GetResolutionForCropping(
    const SupportedFormats& formats,
    TestCropping cropping_profile) {
  // FOV requirement cannot allow cropping twice. If two streams resolution are
  // 1920x1080 and 1600x1200, we need a larger resolution which aspect ratio
  // is the same as sensor aspect ratio.
  float sensor_aspect_ratio =
      static_cast<float>(cropping_profile.sensor_pixel_array_size_width) /
      cropping_profile.sensor_pixel_array_size_height;

  // We need to compare the aspect ratio from sensor resolution.
  // The sensor resolution may not be just the size. It may be a little larger.
  // Add a margin to check if the sensor aspect ratio fall in the specific
  // aspect ratio.
  // 16:9=1.778, 16:10=1.6, 3:2=1.5, 4:3=1.333
  const float kAspectRatioMargin = 0.04;
  const float kFrameRate = 30.0;

  for (const auto& format : formats) {
    if (format.width >= 1920 && format.height >= 1200) {
      float aspect_ratio = static_cast<float>(format.width) / format.height;
      if (std::fabs(sensor_aspect_ratio - aspect_ratio) < kAspectRatioMargin) {
        for (const auto& frame_rate : format.frame_rates) {
          if (std::fabs(frame_rate - kFrameRate) <=
              std::numeric_limits<float>::epsilon()) {
            return &format;
          }
        }
      }
    }
  }
  return nullptr;
}

// This is for Android testCameraToSurfaceTextureMetadata CTS test case.
bool CheckConstantFramerate(const std::vector<int64_t>& timestamps,
                            float require_fps) {
  // Timestamps are from driver. We only allow 1.5% error buffer for the frame
  // duration. The margin is aligned to CTS tests.
  float slop_margin = 0.015;
  float slop_max_frame_duration_ms = (1000.f / require_fps) * (1 + slop_margin);
  float slop_min_frame_duration_ms = (1000.f / require_fps) * (1 - slop_margin);

  for (size_t i = 1; i < timestamps.size(); i++) {
    float frame_duration_ms =
        (timestamps[i] - timestamps[i - 1]) / 1000000.f;
    if (frame_duration_ms > slop_max_frame_duration_ms ||
        frame_duration_ms < slop_min_frame_duration_ms) {
      printf("[Warning] Frame duration %f out of frame rate bounds [%f,%f]\n",
          frame_duration_ms, slop_min_frame_duration_ms,
          slop_max_frame_duration_ms);
      return false;
    }
  }
  return true;
}

bool TestIO(const std::string& dev_name) {
  uint32_t buffers = 4;
  uint32_t width = 640;
  uint32_t height = 480;
  uint32_t pixfmt = V4L2_PIX_FMT_YUYV;
  float fps = 30.0;
  uint32_t time_to_capture = 3;  // The unit is second.
  uint32_t skip_frames = 0;
  V4L2Device::ConstantFramerate constant_framerate =
      V4L2Device::DEFAULT_FRAMERATE_SETTING;

  std::unique_ptr<V4L2Device> device(
      new V4L2Device(dev_name.c_str(), buffers));

  if (!device->OpenDevice())
    return false;

  v4l2_capability cap;
  if (!device->ProbeCaps(&cap))
    return false;

  if (cap.capabilities & V4L2_CAP_STREAMING) {
    int mmap_ret = RunTest(device.get(), V4L2Device::IO_METHOD_MMAP, buffers,
        time_to_capture, width, height, pixfmt, fps, constant_framerate,
        skip_frames);
    int userp_ret = RunTest(device.get(), V4L2Device::IO_METHOD_USERPTR,
        buffers, time_to_capture, width, height, pixfmt, fps,
        constant_framerate, skip_frames);
    if (mmap_ret && userp_ret) {
      printf("[Error] Stream I/O failed.\n");
      return false;
    }
  } else {
    printf("[Error] Streaming capability is mandatory.\n");
    return false;
  }

  device->CloseDevice();
  return true;
}

// Test all required resolutions with 30 fps.
// If device supports constant framerate, the test will toggle the setting
// and check actual fps. Otherwise, use the default setting of
// V4L2_CID_EXPOSURE_AUTO_PRIORITY.
bool TestResolutions(const std::string& dev_name,
                     bool check_1280x960,
                     bool check_1600x1200,
                     TestCropping cropping_profile,
                     bool test_constant_framerate) {
  const int kMaxRetryTimes = 5;
  uint32_t buffers = 4;
  uint32_t time_to_capture = 3;
  uint32_t skip_frames = 0;
  bool pass = true;

  V4L2Device::IOMethod io = V4L2Device::IO_METHOD_MMAP;
  std::unique_ptr<V4L2Device> device(
      new V4L2Device(dev_name.c_str(), buffers));

  if (!device->OpenDevice())
    return false;

  std::vector<V4L2Device::ConstantFramerate> constant_framerate_setting;
  if (test_constant_framerate) {
    constant_framerate_setting.push_back(V4L2Device::ENABLE_CONSTANT_FRAMERATE);
    constant_framerate_setting.push_back(
        V4L2Device::DISABLE_CONSTANT_FRAMERATE);
  } else {
    constant_framerate_setting.push_back(
        V4L2Device::DEFAULT_FRAMERATE_SETTING);
  }

  SupportedFormats supported_formats;
  if (!GetSupportedFormats(device.get(), &supported_formats)) {
    printf("[Error] Get supported formats failed in %s.\n", dev_name.c_str());
    return false;
  }
  SupportedFormat max_resolution = GetMaximumResolution(supported_formats);

  const float kFrameRate = 30.0;
  SupportedFormats required_resolutions;
  required_resolutions.push_back(SupportedFormat(320, 240, 0, kFrameRate));
  required_resolutions.push_back(SupportedFormat(640, 480, 0, kFrameRate));
  required_resolutions.push_back(SupportedFormat(1280, 720, 0, kFrameRate));
  required_resolutions.push_back(SupportedFormat(1920, 1080, 0, kFrameRate));
  if (check_1280x960) {
    required_resolutions.push_back(SupportedFormat(1280, 960, 0, kFrameRate));
  }
  if (check_1600x1200) {
    required_resolutions.push_back(SupportedFormat(1600, 1200, 0, kFrameRate));
  }
  if (cropping_profile.check_cropping) {
    const SupportedFormat* cropping_resolution =
        GetResolutionForCropping(supported_formats, cropping_profile);
    if (cropping_resolution != nullptr) {
      printf("[Info] Add resolution without cropping %dx%d.\n",
          cropping_resolution->width, cropping_resolution->height);
      required_resolutions.push_back(*cropping_resolution);
    } else if (max_resolution.width >= 1920 && max_resolution.height >= 1200) {
      printf("[Error] Can not find cropping resolution.\n");
      pass = false;
    }
  }

  for (const auto& test_resolution : required_resolutions) {
    // Skip the resolution that is larger than the maximum.
    if (max_resolution.width < test_resolution.width ||
        max_resolution.height < test_resolution.height) {
      continue;
    }

    const SupportedFormat* test_format = FindFormatByResolution(
        supported_formats, test_resolution.width, test_resolution.height);
    if (test_format == nullptr) {
      printf("[Error] %dx%d not found in %s\n", test_resolution.width,
          test_resolution.height, dev_name.c_str());
      pass = false;
      continue;
    }

    bool frame_rate_30_supported = false;
    for (const auto& frame_rate : test_format->frame_rates) {
      if (std::fabs(frame_rate - kFrameRate) <=
          std::numeric_limits<float>::epsilon()) {
        frame_rate_30_supported = true;
        break;
      }
    }
    if (!frame_rate_30_supported) {
      printf("[Error] Cannot test 30 fps for %dx%d (%08X) failed in %s\n",
          test_format->width, test_format->height, test_format->fourcc,
          dev_name.c_str());
      pass = false;
    }

    for (const auto& constant_framerate : constant_framerate_setting) {
      int retry_count = 0;

      if (!frame_rate_30_supported && constant_framerate ==
          V4L2Device::ENABLE_CONSTANT_FRAMERATE) {
        continue;
      }

      for (retry_count = 0; retry_count < kMaxRetryTimes; retry_count++) {
        if (RunTest(device.get(), io, buffers, time_to_capture,
              test_format->width, test_format->height, test_format->fourcc,
              kFrameRate, constant_framerate, skip_frames)) {
          printf("[Error] Could not capture frames for %dx%d (%08X) in %s\n",
              test_format->width, test_format->height, test_format->fourcc,
              dev_name.c_str());
          pass = false;
          break;
        }

        // Make sure the driver didn't adjust the format.
        v4l2_format fmt;
        if (!device->GetV4L2Format(&fmt)) {
          pass = false;
          break;
        } else {
          if (test_format->width != fmt.fmt.pix.width ||
              test_format->height != fmt.fmt.pix.height ||
              test_format->fourcc != fmt.fmt.pix.pixelformat ||
              std::fabs(kFrameRate - device->GetFrameRate()) >
                  std::numeric_limits<float>::epsilon()) {
            printf("[Error] Capture test %dx%d (%08X) %.2f fps failed in %s\n",
                test_format->width, test_format->height, test_format->fourcc,
                kFrameRate, dev_name.c_str());
            pass = false;
            break;
          }
        }

        if (constant_framerate != V4L2Device::ENABLE_CONSTANT_FRAMERATE) {
          break;
        }

        float actual_fps = (device->GetNumFrames() - 1) /
            static_cast<float>(time_to_capture);
        // 1 fps buffer is because |time_to_capture| may be too short.
        // EX: 30 fps and capture 3 secs. We may get 89 frames or 91 frames.
        // The actual fps will be 29.66 or 30.33.
        if (fabsf(actual_fps - kFrameRate) > 1) {
          printf("[Warning] Capture test %dx%d (%08X) failed with fps %.2f in "
                 "%s\n", test_format->width, test_format->height,
                 test_format->fourcc, actual_fps, dev_name.c_str());
          continue;
        }

        if (!CheckConstantFramerate(device->GetFrameTimestamps(), kFrameRate)) {
          printf("[Warning] Capture test %dx%d (%08X) failed and didn't meet "
                 "constant framerate in %s\n", test_format->width,
                 test_format->height, test_format->fourcc, dev_name.c_str());
          continue;
        }
        break;
      }
      if (retry_count == kMaxRetryTimes) {
        printf("[Error] Cannot meet constant framerate requirement %d times\n",
               kMaxRetryTimes);
        pass = false;
      }
    }
  }
  device->CloseDevice();
  return pass;
}

bool TestFirstFrameAfterStreamOn(const std::string& dev_name,
                                 uint32_t skip_frames) {
  uint32_t buffers = 4;
  uint32_t pixfmt = V4L2_PIX_FMT_MJPEG;
  uint32_t fps = 30;
  V4L2Device::ConstantFramerate constant_framerate =
      V4L2Device::DEFAULT_FRAMERATE_SETTING;
  V4L2Device::IOMethod io = V4L2Device::IO_METHOD_MMAP;

  std::unique_ptr<V4L2Device> device(
      new V4L2Device(dev_name.c_str(), buffers));
  if (!device->OpenDevice())
    return false;

  SupportedFormats supported_formats;
  if (!GetSupportedFormats(device.get(), &supported_formats)) {
    printf("[Error] Get supported formats failed in %s.\n", dev_name.c_str());
    return false;
  }
  const SupportedFormat* test_format = FindFormatByFourcc(
      supported_formats, V4L2_PIX_FMT_MJPEG);
  if (test_format == nullptr) {
    printf("[Info] The camera doesn't support MJPEG format.\n");
    return true;
  }

  uint32_t width = test_format->width;
  uint32_t height = test_format->height;

  const size_t kTestLoop = 20;
  for (size_t i = 0; i < kTestLoop; i++) {
    if (!device->InitDevice(io, width, height, pixfmt, fps, constant_framerate,
                            skip_frames))
      return false;

    if (!device->StartCapture())
      return false;

    uint32_t buf_index, data_size;
    int ret;
    while ((ret = device->ReadOneFrame(&buf_index, &data_size)) == 0);
    if (ret < 0) {
      return false;
    }

    const V4L2Device::Buffer& buffer = device->GetBufferInfo(buf_index);
    std::unique_ptr<uint8_t[]> yuv_buffer(new uint8_t[width * height * 2]);

    int res = libyuv::MJPGToI420(
        reinterpret_cast<uint8_t*>(buffer.start), data_size,
        yuv_buffer.get(), width,
        yuv_buffer.get() + width * height, width / 2,
        yuv_buffer.get() + width * height * 5 / 4, width / 2,
        width, height, width, height);
    if (res) {
      printf("[Error] First frame is not a valid mjpeg image.\n");
      base::WriteFile(base::FilePath("FirstFrame.jpg"),
                      static_cast<char*>(buffer.start), data_size);
      return false;
    }

    if (!device->EnqueueBuffer(buf_index))
      return false;

    if (!device->StopCapture())
      return false;

    if (!device->UninitDevice())
      return false;

  }

  device->CloseDevice();
  return true;
}

// ChromeOS spec requires world-facing camera should be at least 1920x1080 and
// user-facing camera should be at least 1280x720.
bool TestMaximumSupportedResolution(const std::string& dev_name,
                                    uint32_t facing) {
  uint32_t buffers = 4;
  std::unique_ptr<V4L2Device> device(
      new V4L2Device(dev_name.c_str(), buffers));

  if (!device->OpenDevice())
    return false;

  SupportedFormats supported_formats;
  if (!GetSupportedFormats(device.get(), &supported_formats)) {
    printf("[Error] Get supported formats failed in %s.\n", dev_name.c_str());
    return false;
  }
  device->CloseDevice();
  SupportedFormat max_resolution = GetMaximumResolution(supported_formats);

  uint32_t required_width = 0, required_height = 0;
  std::string facing_str = "";
  if (facing == FACING_FRONT) {
    required_width = 1080;
    required_height = 720;
    facing_str = kFrontCamera;
  } else if (facing == FACING_BACK) {
    required_width = 1920;
    required_height = 1080;
    facing_str = kBackCamera;
  } else {
    printf("[Error] Undefined facing: %d\n", facing);
    return false;
  }

  if (max_resolution.width < required_width ||
      max_resolution.height < required_height) {
    printf("[Error] The maximum resolution %dx%d does not meet requirement "
           "%dx%d for %s-facing\n", max_resolution.width,
           max_resolution.height, required_width, required_height,
           facing_str.c_str());
    return false;
  }
  return true;
}

const TestProfile GetTestProfile(const std::string& dev_name,
                                 const std::string& usb_info,
                                 const std::string& test_list) {
  const int VID_PID_LENGTH = 9;  // 0123:abcd format
  const DeviceInfo* device_info = nullptr;
  CameraCharacteristics characteristics;
  if (!usb_info.empty()) {
    if (usb_info.length() != VID_PID_LENGTH) {
      printf("[Error] Invalid usb info: %s\n", usb_info.c_str());
      exit(EXIT_FAILURE);
    }
    device_info = characteristics.Find(usb_info.substr(0, 4),
                                       usb_info.substr(5, 9));
  }

  if (test_list != kDefaultTestList) {
    if (!characteristics.ConfigFileExists()) {
      printf("[Error] %s test list needs camera config file\n",
          test_list.c_str());
      exit(EXIT_FAILURE);
    }
    if (device_info == nullptr) {
      printf("[Error] %s is not described in camera config file\n",
          usb_info.c_str());
      exit(EXIT_FAILURE);
    }
  } else {
    if (!characteristics.ConfigFileExists()) {
      printf("[Info] Camera config file doesn't exist\n");
    } else if (device_info == nullptr) {
      printf("[Info] %s is not described in camera config file\n",
          usb_info.c_str());
    }
  }

  TestProfile profile;
  profile.test_list = test_list;
  profile.dev_name = dev_name;
  // Get parameter from config file.
  if (device_info) {
    profile.support_constant_framerate =
        !device_info->constant_framerate_unsupported;
    profile.skip_frames = device_info->frames_to_skip_after_streamon;
    profile.lens_facing = device_info->lens_facing;
    profile.check_maximum_resolution = true;

    // If there is a camera config and test list is not HAL v1, then we can
    // check cropping requirement according to the sensor physical size.
    if (test_list != kDefaultTestList) {
      profile.cropping_profile.check_cropping = true;
      profile.cropping_profile.sensor_pixel_array_size_width =
          device_info->sensor_info_pixel_array_size_width;
      profile.cropping_profile.sensor_pixel_array_size_height =
          device_info->sensor_info_pixel_array_size_height;
    }
  }

  if (test_list == kDefaultTestList) {
    profile.check_1280x960 = false;
    profile.check_1600x1200 = false;
    profile.check_constant_framerate = false;
  } else if (test_list == kHalv3TestList) {
    profile.check_1280x960 = true;
    profile.check_1600x1200 = true;
    profile.skip_frames = 0;
    profile.check_constant_framerate = true;
  } else if (test_list == kCertificationTestList) {
    profile.check_1280x960 = true;
    profile.check_1600x1200 = true;
    profile.skip_frames = 0;
    profile.check_maximum_resolution = false;
    profile.check_constant_framerate = true;
  }

  printf("[Info] check 1280x960: %d\n", profile.check_1280x960);
  printf("[Info] check 1600x1200: %d\n", profile.check_1600x1200);
  printf("[Info] check constant framerate: %d\n",
      profile.check_constant_framerate);
  printf("[Info] check cropping: %d\n",
      profile.cropping_profile.check_cropping);
  printf("[Info] num of skip frames after stream on: %d\n",
      profile.skip_frames);

  return profile;
}

TEST(TestList, TestIO) {
  ASSERT_TRUE(TestIO(g_profile.dev_name));
}

TEST(TestList, TestResolutions) {
  if (g_profile.test_list == kHalv3TestList &&
      !g_profile.support_constant_framerate) {
    printf("[Error] Hal v3 should support constant framerate.\n");
    ASSERT_TRUE(false);
  }

  ASSERT_TRUE(TestResolutions(g_profile.dev_name, g_profile.check_1280x960,
                              g_profile.check_1600x1200,
                              g_profile.cropping_profile,
                              g_profile.check_constant_framerate));
}

TEST(TestList, TestMaximumSupportedResolution) {
  if (g_profile.test_list == kCertificationTestList)
    return;
  if (g_profile.check_maximum_resolution) {
    ASSERT_TRUE(TestMaximumSupportedResolution(g_profile.dev_name,
                                               g_profile.lens_facing));
  }
}

TEST(TestList, TestFirstFrameAfterStreamOn) {
  if (g_profile.test_list == kDefaultTestList)
    return;
  ASSERT_TRUE(TestFirstFrameAfterStreamOn(g_profile.dev_name,
                                          g_profile.skip_frames));
}

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);
  base::CommandLine::Init(argc, argv);
  base::ShadowingAtExitManager at_exit_manager;

  const base::CommandLine* cmd_line = base::CommandLine::ForCurrentProcess();
  DCHECK(cmd_line);

  std::string dev_name = "/dev/video";
  std::string usb_info = "";
  std::string test_list = kDefaultTestList;

  base::CommandLine::SwitchMap switches = cmd_line->GetSwitches();
  for (base::CommandLine::SwitchMap::const_iterator it = switches.begin();
       it != switches.end(); ++it) {
    if (it->first == "h" || it->first == "help") {
      PrintUsage(argc, argv);
      return EXIT_SUCCESS;
    }
    if (it->first == "device") {
      dev_name = it->second;
      continue;
    }
    if (it->first == "usb-info") {
      usb_info = it->second;
      continue;
    }
    if (it->first == "test-list") {
      test_list = it->second;
      continue;
    }

    PrintUsage(argc, argv);
    LOGF(ERROR) << "Unexpected switch: " << it->first << ":" << it->second;
    return EXIT_FAILURE;
  }

  g_profile = GetTestProfile(dev_name, usb_info, test_list);

  return RUN_ALL_TESTS();
}
