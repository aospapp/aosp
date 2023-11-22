# Copyright 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import its.image
import its.caps
import its.device
import its.objects
import its.target
import numpy
import os.path

def main():
    """Test that raw streams are not croppable.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    DIFF_THRESH = 0.05

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()
        if (not its.caps.compute_target_exposure(props) or
            not its.caps.raw16(props)):
            print "Test skipped"
            return

        a = props['android.sensor.info.activeArraySize']
        ax, ay = a["left"], a["top"]
        aw, ah = a["right"] - a["left"], a["bottom"] - a["top"]
        print "Active sensor region: (%d,%d %dx%d)" % (ax, ay, aw, ah)

        # Capture without a crop region.
        # Use a manual request with a linear tonemap so that the YUV and RAW
        # should look the same (once converted by the its.image module).
        e, s = its.target.get_target_exposure_combos(cam)["minSensitivity"]
        req = its.objects.manual_capture_request(s,e, True)
        cap1_raw, cap1_yuv = cam.do_capture(req, cam.CAP_RAW_YUV)

        # Capture with a center crop region.
        req["android.scaler.cropRegion"] = {
                "top": ay + ah/3,
                "left": ax + aw/3,
                "right": ax + 2*aw/3,
                "bottom": ay + 2*ah/3}
        cap2_raw, cap2_yuv = cam.do_capture(req, cam.CAP_RAW_YUV)

        reported_crops = []
        imgs = {}
        for s,cap in [("yuv_full",cap1_yuv), ("raw_full",cap1_raw),
                ("yuv_crop",cap2_yuv), ("raw_crop",cap2_raw)]:
            img = its.image.convert_capture_to_rgb_image(cap, props=props)
            its.image.write_image(img, "%s_%s.jpg" % (NAME, s))
            r = cap["metadata"]["android.scaler.cropRegion"]
            x, y = a["left"], a["top"]
            w, h = a["right"] - a["left"], a["bottom"] - a["top"]
            reported_crops.append((x,y,w,h))
            imgs[s] = img
            print "Crop on %s: (%d,%d %dx%d)" % (s, x,y,w,h)

        # The metadata should report uncropped for all shots (since there is
        # at least 1 uncropped stream in each case).
        for (x,y,w,h) in reported_crops:
            assert((ax,ay,aw,ah) == (x,y,w,h))

        # Also check the image content; 3 of the 4 shots should match.
        # Note that all the shots are RGB below; the variable names correspond
        # to what was captured.
        # Average the images down 4x4 -> 1 prior to comparison to smooth out
        # noise.
        # Shrink the YUV images an additional 2x2 -> 1 to account for the size
        # reduction that the raw images went through in the RGB conversion.
        imgs2 = {}
        for s,img in imgs.iteritems():
            h,w,ch = img.shape
            m = 4
            if s in ["yuv_full", "yuv_crop"]:
                m = 8
            img = img.reshape(h/m,m,w/m,m,3).mean(3).mean(1).reshape(h/m,w/m,3)
            imgs2[s] = img
            print s, img.shape

        # Strip any border pixels from the raw shots (since the raw images may
        # be larger than the YUV images). Assume a symmetric padded border.
        xpad = (imgs2["raw_full"].shape[1] - imgs2["yuv_full"].shape[1]) / 2
        ypad = (imgs2["raw_full"].shape[0] - imgs2["yuv_full"].shape[0]) / 2
        wyuv = imgs2["yuv_full"].shape[1]
        hyuv = imgs2["yuv_full"].shape[0]
        imgs2["raw_full"]=imgs2["raw_full"][ypad:ypad+hyuv:,xpad:xpad+wyuv:,::]
        imgs2["raw_crop"]=imgs2["raw_crop"][ypad:ypad+hyuv:,xpad:xpad+wyuv:,::]
        print "Stripping padding before comparison:", xpad, ypad

        for s,img in imgs2.iteritems():
            its.image.write_image(img, "%s_comp_%s.jpg" % (NAME, s))

        # Compute image diffs.
        diff_yuv = numpy.fabs((imgs2["yuv_full"] - imgs2["yuv_crop"])).mean()
        diff_raw = numpy.fabs((imgs2["raw_full"] - imgs2["raw_crop"])).mean()
        print "YUV diff (crop vs. non-crop):", diff_yuv
        print "RAW diff (crop vs. non-crop):", diff_raw

        assert(diff_yuv > DIFF_THRESH)
        assert(diff_raw < DIFF_THRESH)

if __name__ == '__main__':
    main()

