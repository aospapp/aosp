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
import its.device
import its.objects
import os.path
import pylab
import matplotlib
import matplotlib.pyplot
import numpy

def main():
    """Tests that EV compensation is applied.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    MAX_LUMA_DELTA_THRESH = 0.01
    AVG_LUMA_DELTA_THRESH = 0.001

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()
        cam.do_3a()

        # Capture auto shots, but with a linear tonemap.
        req = its.objects.auto_capture_request()
        req["android.tonemap.mode"] = 0
        req["android.tonemap.curveRed"] = (0.0, 0.0, 1.0, 1.0)
        req["android.tonemap.curveGreen"] = (0.0, 0.0, 1.0, 1.0)
        req["android.tonemap.curveBlue"] = (0.0, 0.0, 1.0, 1.0)

        evs = range(-4,5)
        lumas = []
        for ev in evs:
            req['android.control.aeExposureCompensation'] = ev
            cap = cam.do_capture(req)
            y = its.image.convert_capture_to_planes(cap)[0]
            tile = its.image.get_image_patch(y, 0.45,0.45,0.1,0.1)
            lumas.append(its.image.compute_image_means(tile)[0])

        ev_step_size_in_stops = its.objects.rational_to_float(
                props['android.control.aeCompensationStep'])
        luma_increase_per_step = pow(2, ev_step_size_in_stops)
        expected_lumas = [lumas[0] * pow(luma_increase_per_step, i) \
                for i in range(len(evs))]

        pylab.plot(evs, lumas, 'r')
        pylab.plot(evs, expected_lumas, 'b')
        matplotlib.pyplot.savefig("%s_plot_means.png" % (NAME))

        luma_diffs = [expected_lumas[i] - lumas[i] for i in range(len(evs))]
        max_diff = max(luma_diffs)
        avg_diff = sum(luma_diffs) / len(luma_diffs)
        print "Max delta between modeled and measured lumas:", max_diff
        print "Avg delta between modeled and measured lumas:", avg_diff
        assert(max_diff < MAX_LUMA_DELTA_THRESH)
        assert(avg_diff < AVG_LUMA_DELTA_THRESH)

if __name__ == '__main__':
    main()
