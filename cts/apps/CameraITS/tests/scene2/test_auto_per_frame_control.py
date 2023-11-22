# Copyright 2018 The Android Open Source Project
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

import os.path

import its.caps
import its.device
import its.image
import its.objects

import matplotlib
from matplotlib import pylab
import numpy as np

AE_STATE_CONVERGED = 2
CONTROL_AE_STATE_FLASH_REQUIRED = 4
NAME = os.path.basename(__file__).split('.')[0]
NUM_CAPTURE = 30
VALID_STABLE_LUMA_MIN = 0.1
VALID_STABLE_LUMA_MAX = 0.9


def is_awb_af_stable(prev_cap, cap):
    awb_gains_0 = prev_cap['metadata']['android.colorCorrection.gains']
    awb_gains_1 = cap['metadata']['android.colorCorrection.gains']
    ccm_0 = prev_cap['metadata']['android.colorCorrection.transform']
    ccm_1 = cap['metadata']['android.colorCorrection.transform']
    focus_distance_0 = prev_cap['metadata']['android.lens.focusDistance']
    focus_distance_1 = cap['metadata']['android.lens.focusDistance']

    return (np.allclose(awb_gains_0, awb_gains_1, rtol=0.01) and
            ccm_0 == ccm_1 and
            np.isclose(focus_distance_0, focus_distance_1, rtol=0.01))


def main():
    """Tests PER_FRAME_CONTROL properties for auto capture requests.

    If debug is required, MANUAL_POSTPROCESSING capability is implied
    since its.caps.read_3a is valid for test. Debug can performed with
    a defined tonemap curve:
    req['android.tonemap.mode'] = 0
    gamma = sum([[i/63.0,math.pow(i/63.0,1/2.2)] for i in xrange(64)],[])
    req['android.tonemap.curve'] = {
            'red': gamma, 'green': gamma, 'blue': gamma}
    """

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()
        its.caps.skip_unless(its.caps.per_frame_control(props) and
                             its.caps.read_3a(props))

        debug = its.caps.debug_mode()
        largest_yuv = its.objects.get_largest_yuv_format(props)
        if debug:
            fmt = largest_yuv
        else:
            match_ar = (largest_yuv['width'], largest_yuv['height'])
            fmt = its.objects.get_smallest_yuv_format(props, match_ar=match_ar)

        req = its.objects.auto_capture_request()
        caps = cam.do_capture([req]*NUM_CAPTURE, fmt)

        total_gains = []
        lumas = []
        ae_states = []
        for i, cap in enumerate(caps):
            print '=========== frame %d ==========' % i
            y = its.image.convert_capture_to_planes(cap)[0]
            tile = its.image.get_image_patch(y, 0.45, 0.45, 0.1, 0.1)
            luma = its.image.compute_image_means(tile)[0]

            ae_state = cap['metadata']['android.control.aeState']
            iso = cap['metadata']['android.sensor.sensitivity']
            isp_gain = cap['metadata']['android.control.postRawSensitivityBoost']
            exp_time = cap['metadata']['android.sensor.exposureTime']
            total_gain = iso*isp_gain/100.0*exp_time/1000000.0
            awb_state = cap['metadata']['android.control.awbState']
            awb_gains = cap['metadata']['android.colorCorrection.gains']
            ccm = cap['metadata']['android.colorCorrection.transform']
            focus_distance = cap['metadata']['android.lens.focusDistance']

            # Convert CCM from rational to float, as numpy arrays.
            awb_ccm = np.array(its.objects.rational_to_float(ccm)).reshape(3, 3)

            print 'AE: %d ISO: %d ISP_sen: %d exp(ms): %d tot_gain: %f' % (
                    ae_state, iso, isp_gain, exp_time, total_gain),
            print 'luma: %f' % luma
            print 'fd: %f' % focus_distance
            print 'AWB: %d, AWB gains: %s\n AWB matrix: %s' % (
                    awb_state, str(awb_gains), str(awb_ccm))
            print 'Tonemap curve:', cap['metadata']['android.tonemap.curve']

            lumas.append(luma)
            total_gains.append(total_gain)
            ae_states.append(ae_state)
            img = its.image.convert_capture_to_rgb_image(cap)
            its.image.write_image(img, '%s_frame_%d.jpg'% (NAME, i))

        norm_gains = [x / max(total_gains) * max(lumas) for x in total_gains]
        pylab.plot(range(len(lumas)), lumas, '-g.',
                   label='Center patch brightness')
        pylab.plot(range(len(norm_gains)), norm_gains, '-r.',
                   label='Metadata AE setting product')
        pylab.title(NAME)
        pylab.xlabel('frame index')
        pylab.legend()
        matplotlib.pyplot.savefig('%s_plot.png' % (NAME))

        for i in range(1, len(caps)):
            if is_awb_af_stable(caps[i-1], caps[i]):
                prev_total_gain = total_gains[i-1]
                total_gain = total_gains[i]
                delta_gain = total_gain - prev_total_gain
                prev_luma = lumas[i-1]
                luma = lumas[i]
                delta_luma = luma - prev_luma
                # luma and total_gain should change in same direction
                msg = 'Frame %d to frame %d:' % (i-1, i)
                msg += ' metadata gain %f->%f (%s), luma %f->%f (%s)' % (
                        prev_total_gain, total_gain,
                        'increasing' if delta_gain > 0.0 else 'decreasing',
                        prev_luma, luma,
                        'increasing' if delta_luma > 0.0 else 'decreasing')
                assert delta_gain * delta_luma >= 0.0, msg
            else:
                print 'Frame %d->%d AWB/AF changed' % (i-1, i)

        for i in range(len(lumas)):
            luma = lumas[i]
            ae_state = ae_states[i]
            if (ae_state == AE_STATE_CONVERGED or
                        ae_state == CONTROL_AE_STATE_FLASH_REQUIRED):
                msg = 'Frame %d AE converged luma %f. valid range: (%f, %f)' % (
                        i, luma, VALID_STABLE_LUMA_MIN, VALID_STABLE_LUMA_MAX)
                assert VALID_STABLE_LUMA_MIN < luma < VALID_STABLE_LUMA_MAX, msg

if __name__ == '__main__':
    main()
