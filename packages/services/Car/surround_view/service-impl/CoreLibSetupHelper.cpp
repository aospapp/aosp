/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "CoreLibSetupHelper.h"

using namespace android_auto::surround_view;

namespace android_auto {
namespace surround_view {

vector<SurroundViewCameraParams> GetCameras() {
  std::vector<android_auto::surround_view::SurroundViewCameraParams> cameras;

  // Camera 1.
  {
    android_auto::surround_view::SurroundViewCameraParams camera_params;

    camera_params.intrinsics[0] = 608.0026093794693;
    camera_params.intrinsics[1] = 0.0;
    camera_params.intrinsics[2] = 968.699544102168;
    camera_params.intrinsics[3] = 0.0;
    camera_params.intrinsics[4] = 608.205469489769;
    camera_params.intrinsics[5] = 476.38843298898996;
    camera_params.intrinsics[6] = 0.0;
    camera_params.intrinsics[7] = 0.0;
    camera_params.intrinsics[8] = 1.0;

    camera_params.distorion[0] = -0.03711481733589263;
    camera_params.distorion[1] = -0.0014805627895442888;
    camera_params.distorion[2] = -0.00030212056866592464;
    camera_params.distorion[3] = -0.00020149538570397933;

    camera_params.rvec[0] = 2.26308;
    camera_params.rvec[1] = 0.0382788;
    camera_params.rvec[2] = -0.0220549;

    camera_params.tvec[0] = -7.8028875403817685e-02;
    camera_params.tvec[1] = 1.4537396465103221e+00;
    camera_params.tvec[2] = -8.4197165554645001e-02;

    camera_params.size.width = 1920;
    camera_params.size.height = 1024;

    camera_params.circular_fov = 179;

    cameras.push_back(camera_params);
  }

  // Camera 2.
  {
    android_auto::surround_view::SurroundViewCameraParams camera_params;

    camera_params.intrinsics[0] = 607.8691721095306;
    camera_params.intrinsics[1] = 0.0;
    camera_params.intrinsics[2] = 975.5686146375716;
    camera_params.intrinsics[3] = 0.0;
    camera_params.intrinsics[4] = 608.0112887189435;
    camera_params.intrinsics[5] = 481.1938786570715;
    camera_params.intrinsics[6] = 0.0;
    camera_params.intrinsics[7] = 0.0;
    camera_params.intrinsics[8] = 1.0;

    camera_params.distorion[0] = -0.040116809827977926;
    camera_params.distorion[1] = 0.0028769489398543014;
    camera_params.distorion[2] = -0.002651039958977229;
    camera_params.distorion[3] = 0.00024260630476736675;

    camera_params.rvec[0] = 1.67415;
    camera_params.rvec[1] = -1.74075;
    camera_params.rvec[2] = 0.789399;

    camera_params.tvec[0] = 2.9715052384687407e-01;
    camera_params.tvec[1] = 1.1407102692699396e+00;
    camera_params.tvec[2] = 3.0074545273489206e-01;

    camera_params.size.width = 1920;
    camera_params.size.height = 1024;

    camera_params.circular_fov = 179;

    cameras.push_back(camera_params);
  }

  // Camera 3.
  {
    android_auto::surround_view::SurroundViewCameraParams camera_params;

    camera_params.intrinsics[0] = 608.557299289448;
    camera_params.intrinsics[1] = 0.0;
    camera_params.intrinsics[2] = 960.1949354417656;
    camera_params.intrinsics[3] = 0.0;
    camera_params.intrinsics[4] = 608.8093878512448;
    camera_params.intrinsics[5] = 474.74744054048256;
    camera_params.intrinsics[6] = 0.0;
    camera_params.intrinsics[7] = 0.0;
    camera_params.intrinsics[8] = 1.0;

    camera_params.distorion[0] = -0.03998488563470043;
    camera_params.distorion[1] = 0.0024786686909103388;
    camera_params.distorion[2] = -0.002354736769480817;
    camera_params.distorion[3] = 0.00018369619088506146;

    camera_params.rvec[0] = -0.106409;
    camera_params.rvec[1] = -2.83697;
    camera_params.rvec[2] = 1.28629;

    camera_params.tvec[0] = 1.7115269161259747e-01;
    camera_params.tvec[1] = 1.4376160762596599e+00;
    camera_params.tvec[2] = -1.9028844233159006e-02;

    camera_params.size.width = 1920;
    camera_params.size.height = 1024;

    camera_params.circular_fov = 179;

    cameras.push_back(camera_params);
  }

  // Camera 4.
  {
    android_auto::surround_view::SurroundViewCameraParams camera_params;

    camera_params.intrinsics[0] = 608.1221963545495;
    camera_params.intrinsics[1] = 0.0;
    camera_params.intrinsics[2] = 943.6280444638576;
    camera_params.intrinsics[3] = 0.0;
    camera_params.intrinsics[4] = 608.0523818661524;
    camera_params.intrinsics[5] = 474.8564698210861;
    camera_params.intrinsics[6] = 0.0;
    camera_params.intrinsics[7] = 0.0;
    camera_params.intrinsics[8] = 1.0;

    camera_params.distorion[0] = -0.038096507459563965;
    camera_params.distorion[1] = 0.0004008114278766646;
    camera_params.distorion[2] = -0.0013549275607082035;
    camera_params.distorion[3] = -5.9961182248325556e-06;

    camera_params.rvec[0] = 1.63019;
    camera_params.rvec[1] = 1.76475;
    camera_params.rvec[2] = -0.827941;

    camera_params.tvec[0] = -3.0842691427126512e-01;
    camera_params.tvec[1] = 1.0884122033556984e+00;
    camera_params.tvec[2] = 3.4419058255954926e-01;

    camera_params.size.width = 1920;
    camera_params.size.height = 1024;

    camera_params.circular_fov = 179;

    cameras.push_back(camera_params);
  }
  return cameras;

}

SurroundView2dParams Get2dParams() {
  android_auto::surround_view::Size2dInteger
      resolution{ /*width=*/ 1024, /*height*/ 768};
  // make sure resolution has the same ratio with physical_size.
  // {480 *360 }
  android_auto::surround_view::Size2dFloat physical_size{8.0, 6.0};
  android_auto::surround_view::Coordinate2dFloat physical_center{0, 0};

  return android_auto::surround_view::SurroundView2dParams(
      resolution, physical_size, physical_center);
}

SurroundView3dParams Get3dParams() {
  return android_auto::surround_view::SurroundView3dParams(
      /*plane_radius=*/ 8.0f,
      /*plane_divisions=*/ 50,
      /*curve_height=*/ 6.0f,
      /*curve_divisions=*/ 50,
      /*angular_divisions=*/ 90,
      /*curve_coefficient=*/ 3.0f,
      /*resolution=*/ Size2dInteger(1024, 768));
}

BoundingBox GetBoundingBox() {
  return android_auto::surround_view::BoundingBox(
      /*x=*/ -0.01f,
      /*y=*/ 0.01f,
      /*width=*/ 0.01f,
      /*height=*/ 0.01f);
}

vector<float> GetUndistortionScales() {
  return vector<float>{1.0f, 1.0f, 1.0f, 1.0f};
}


} // namespace surround_view
} // namespace audroid_auto

