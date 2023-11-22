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

import copy
import math
import os
import os.path
import re
import subprocess
import sys
import tempfile
import threading
import time

import its.caps
import its.cv2image
import its.device
from its.device import ItsSession
import its.image

import numpy as np

# For sanity checking the installed APK's target SDK version
MIN_SUPPORTED_SDK_VERSION = 28  # P

CHART_DELAY = 1  # seconds
CHART_DISTANCE = 30.0  # cm
CHART_HEIGHT = 13.5  # cm
CHART_LEVEL = 96
CHART_SCALE_START = 0.65
CHART_SCALE_STOP = 1.35
CHART_SCALE_STEP = 0.025
FACING_EXTERNAL = 2
NUM_TRYS = 2
PROC_TIMEOUT_CODE = -101  # terminated process return -process_id
PROC_TIMEOUT_TIME = 900  # timeout in seconds for a process (15 minutes)
SCENE3_FILE = os.path.join(os.environ['CAMERA_ITS_TOP'], 'pymodules', 'its',
                           'test_images', 'ISO12233.png')
SKIP_RET_CODE = 101  # note this must be same as tests/scene*/test_*
VGA_HEIGHT = 480
VGA_WIDTH = 640

# Not yet mandated tests
NOT_YET_MANDATED = {
        'scene0': [
                'test_test_patterns',
                'test_tonemap_curve'
        ],
        'scene1': [
                'test_ae_precapture_trigger',
                'test_channel_saturation'
        ],
        'scene2': [
                'test_auto_per_frame_control'
        ],
        'scene2b': [],
        'scene2c': [],
        'scene3': [],
        'scene4': [],
        'scene5': [],
        'sensor_fusion': []
}

# Must match mHiddenPhysicalCameraSceneIds in ItsTestActivity.java
HIDDEN_PHYSICAL_CAMERA_TESTS = {
        'scene0': [
                'test_burst_capture',
                'test_metadata',
                'test_read_write',
                'test_sensor_events'
        ],
        'scene1': [
                'test_exposure',
                'test_dng_noise_model',
                'test_linearity',
                'test_raw_exposure',
                'test_raw_sensitivity'
        ],
        'scene2': [
                'test_faces',
                'test_num_faces'
        ],
        'scene2b': [],
        'scene2c': [],
        'scene3': [],
        'scene4': [
                'test_aspect_ratio_and_crop'
        ],
        'scene5': [],
        'sensor_fusion': [
                'test_sensor_fusion'
        ]
}

def run_subprocess_with_timeout(cmd, fout, ferr, outdir):
    """Run subprocess with a timeout.

    Args:
        cmd:    list containing python command
        fout:   stdout file for the test
        ferr:   stderr file for the test
        outdir: dir location for fout/ferr

    Returns:
        process status or PROC_TIMEOUT_CODE if timer maxes
    """

    proc = subprocess.Popen(
            cmd, stdout=fout, stderr=ferr, cwd=outdir)
    timer = threading.Timer(PROC_TIMEOUT_TIME, proc.kill)

    try:
        timer.start()
        proc.communicate()
        test_code = proc.returncode
    finally:
        timer.cancel()

    if test_code < 0:
        return PROC_TIMEOUT_CODE
    else:
        return test_code


def calc_camera_fov(camera_id, hidden_physical_id):
    """Determine the camera field of view from internal params."""
    with ItsSession(camera_id, hidden_physical_id) as cam:
        props = cam.get_camera_properties()
        props = cam.override_with_hidden_physical_camera_props(props)
        focal_ls = props['android.lens.info.availableFocalLengths']
        if len(focal_ls) > 1:
            print 'Doing capture to determine logical camera focal length'
            cap = cam.do_capture(its.objects.auto_capture_request())
            focal_l = cap['metadata']['android.lens.focalLength']
        else:
            focal_l = focal_ls[0]
    sensor_size = props['android.sensor.info.physicalSize']
    diag = math.sqrt(sensor_size['height'] ** 2 +
                     sensor_size['width'] ** 2)
    try:
        fov = str(round(2 * math.degrees(math.atan(diag / (2 * focal_l))), 2))
    except ValueError:
        fov = str(0)
    print 'Calculated FoV: %s' % fov
    return fov


def evaluate_socket_failure(err_file_path):
    """Determine if test fails due to socket FAIL."""
    socket_fail = False
    with open(err_file_path, 'r') as ferr:
        for line in ferr:
            if (line.find('socket.error') != -1 or
                line.find('socket.timeout') != -1 or
                line.find('Problem with socket') != -1):
                socket_fail = True
    return socket_fail


def skip_sensor_fusion(camera_id):
    """Determine if sensor fusion test is skipped for this camera."""

    skip_code = SKIP_RET_CODE
    with ItsSession(camera_id) as cam:
        props = cam.get_camera_properties()
        if (its.caps.sensor_fusion(props) and its.caps.manual_sensor(props) and
                props['android.lens.facing'] is not FACING_EXTERNAL):
            skip_code = None
    return skip_code


def main():
    """Run all the automated tests, saving intermediate files, and producing
    a summary/report of the results.

    Script should be run from the top-level CameraITS directory.

    Command line arguments:
        camera:  the camera(s) to be tested. Use comma to separate multiple
                 camera Ids. Ex: "camera=0,1" or "camera=1"
        device:  device id for adb
        scenes:  the test scene(s) to be executed. Use comma to separate
                 multiple scenes. Ex: "scenes=scene0,scene1" or
                 "scenes=0,1,sensor_fusion" (sceneX can be abbreviated by X
                 where X is a integer)
        chart:   [Experimental] another android device served as test chart
                 display. When this argument presents, change of test scene
                 will be handled automatically. Note that this argument
                 requires special physical/hardware setup to work and may not
                 work on all android devices.
        result:  Device ID to forward results to (in addition to the device
                 that the tests are running on).
        rot_rig: [Experimental] ID of the rotation rig being used (formatted as
                 "<vendor ID>:<product ID>:<channel #>" or "default")
        tmp_dir: location of temp directory for output files
        skip_scene_validation: force skip scene validation. Used when test scene
                 is setup up front and don't require tester validation.
        dist:    [Experimental] chart distance in cm.
    """

    all_scenes = ["scene0", "scene1", "scene2", "scene2b", "scene2c", "scene3", "scene4", "scene5",
                  "sensor_fusion"]

    auto_scenes = ["scene0", "scene1", "scene2", "scene2b", "scene2c", "scene3", "scene4"]

    scene_req = {
        "scene0": None,
        "scene1": "A grey card covering at least the middle 30% of the scene",
        "scene2": "A picture containing human faces",
        "scene2b": "A picture containing human faces",
        "scene2c": "A picture containing human faces",
        "scene3": "The ISO 12233 chart",
        "scene4": "A specific test page of a circle covering at least the "
                  "middle 50% of the scene. See CameraITS.pdf section 2.3.4 "
                  "for more details",
        "scene5": "Capture images with a diffuser attached to the camera. See "
                  "CameraITS.pdf section 2.3.4 for more details",
        "sensor_fusion": "Rotating checkboard pattern. See "
                         "sensor_fusion/SensorFusion.pdf for detailed "
                         "instructions.\nNote that this test will be skipped "
                         "on devices not supporting REALTIME camera timestamp."
    }
    scene_extra_args = {
        "scene5": ["doAF=False"]
    }

    camera_id_combos = []
    scenes = []
    chart_host_id = None
    result_device_id = None
    rot_rig_id = None
    tmp_dir = None
    skip_scene_validation = False
    chart_distance = CHART_DISTANCE
    chart_level = CHART_LEVEL
    one_camera_argv = sys.argv[1:]

    for s in list(sys.argv[1:]):
        if s[:7] == "camera=" and len(s) > 7:
            camera_ids = s[7:].split(',')
            camera_id_combos = its.device.parse_camera_ids(camera_ids)
            one_camera_argv.remove(s)
        elif s[:7] == "scenes=" and len(s) > 7:
            scenes = s[7:].split(',')
        elif s[:6] == 'chart=' and len(s) > 6:
            chart_host_id = s[6:]
        elif s[:7] == 'result=' and len(s) > 7:
            result_device_id = s[7:]
        elif s[:8] == 'rot_rig=' and len(s) > 8:
            rot_rig_id = s[8:]  # valid values: 'default' or '$VID:$PID:$CH'
            # The default '$VID:$PID:$CH' is '04d8:fc73:1'
        elif s[:8] == 'tmp_dir=' and len(s) > 8:
            tmp_dir = s[8:]
        elif s == 'skip_scene_validation':
            skip_scene_validation = True
        elif s[:5] == 'dist=' and len(s) > 5:
            chart_distance = float(re.sub('cm', '', s[5:]))
        elif s[:11] == 'brightness=' and len(s) > 11:
            chart_level = s[11:]

    chart_dist_arg = 'dist= ' + str(chart_distance)
    chart_level_arg = 'brightness=' + str(chart_level)
    auto_scene_switch = chart_host_id is not None
    merge_result_switch = result_device_id is not None

    # Run through all scenes if user does not supply one
    possible_scenes = auto_scenes if auto_scene_switch else all_scenes
    if not scenes:
        scenes = possible_scenes
    else:
        # Validate user input scene names
        valid_scenes = True
        temp_scenes = []
        for s in scenes:
            if s in possible_scenes:
                temp_scenes.append(s)
            else:
                try:
                    # Try replace "X" to "sceneX"
                    scene_str = "scene" + s
                    if scene_str not in possible_scenes:
                        valid_scenes = False
                        break
                    temp_scenes.append(scene_str)
                except ValueError:
                    valid_scenes = False
                    break

        if not valid_scenes:
            print 'Unknown scene specified:', s
            assert False
        scenes = temp_scenes

    # Initialize test results
    results = {}
    result_key = ItsSession.RESULT_KEY
    for s in all_scenes:
        results[s] = {result_key: ItsSession.RESULT_NOT_EXECUTED}

    # Make output directories to hold the generated files.
    topdir = tempfile.mkdtemp(dir=tmp_dir)
    subprocess.call(['chmod', 'g+rx', topdir])
    print "Saving output files to:", topdir, "\n"

    device_id = its.device.get_device_id()
    device_id_arg = "device=" + device_id
    print "Testing device " + device_id

    # Sanity check CtsVerifier SDK level
    # Here we only do warning as there is no guarantee on pm dump output formt not changed
    # Also sometimes it's intentional to run mismatched versions
    cmd = "adb -s %s shell pm dump com.android.cts.verifier" % (device_id)
    dump_path = os.path.join(topdir, 'CtsVerifier.txt')
    with open(dump_path, 'w') as fout:
        fout.write('ITS minimum supported SDK version is %d\n--\n' % (MIN_SUPPORTED_SDK_VERSION))
        fout.flush()
        ret_code = subprocess.call(cmd.split(), stdout=fout)

    if ret_code != 0:
        print "Warning: cannot get CtsVerifier SDK version. Is CtsVerifier installed?"

    ctsv_version = None
    ctsv_version_name = None
    with open(dump_path, 'r') as f:
        target_sdk_found = False
        version_name_found = False
        for line in f:
            match = re.search('targetSdk=([0-9]+)', line)
            if match:
                ctsv_version = int(match.group(1))
                target_sdk_found = True
            match = re.search('versionName=([\S]+)$', line)
            if match:
                ctsv_version_name = match.group(1)
                version_name_found = True
            if target_sdk_found and version_name_found:
                break

    if ctsv_version is None:
        print "Warning: cannot get CtsVerifier SDK version. Is CtsVerifier installed?"
    elif ctsv_version < MIN_SUPPORTED_SDK_VERSION:
        print "Warning: CtsVerifier version (%d) < ITS version (%d), is this intentional?" % (
                ctsv_version, MIN_SUPPORTED_SDK_VERSION)
    else:
        print "CtsVerifier targetSdk is", ctsv_version
        if ctsv_version_name:
            print "CtsVerifier version name is", ctsv_version_name

    # Hard check on ItsService/host script version that should catch incompatible APK/script
    with ItsSession() as cam:
        cam.check_its_version_compatible()

    # Sanity Check for devices
    device_bfp = its.device.get_device_fingerprint(device_id)
    assert device_bfp is not None

    if auto_scene_switch:
        chart_host_bfp = its.device.get_device_fingerprint(chart_host_id)
        assert chart_host_bfp is not None

    if merge_result_switch:
        result_device_bfp = its.device.get_device_fingerprint(result_device_id)
        assert_err_msg = ('Cannot merge result to a different build, from '
                          '%s to %s' % (device_bfp, result_device_bfp))
        assert device_bfp == result_device_bfp, assert_err_msg

    # user doesn't specify camera id, run through all cameras
    if not camera_id_combos:
        with its.device.ItsSession() as cam:
            camera_ids = cam.get_camera_ids()
            camera_id_combos = its.device.parse_camera_ids(camera_ids);

    print "Running ITS on camera: %s, scene %s" % (camera_id_combos, scenes)

    if auto_scene_switch:
        # merge_result only supports run_parallel_tests
        if merge_result_switch and camera_ids[0] == "1":
            print "Skip chart screen"
            time.sleep(1)
        else:
            print "Waking up chart screen: ", chart_host_id
            screen_id_arg = ("screen=%s" % chart_host_id)
            cmd = ["python", os.path.join(os.environ["CAMERA_ITS_TOP"], "tools",
                                          "wake_up_screen.py"), screen_id_arg,
                   chart_level_arg]
            wake_code = subprocess.call(cmd)
            assert wake_code == 0

    for id_combo in camera_id_combos:
        camera_fov = calc_camera_fov(id_combo.id, id_combo.sub_id)
        id_combo_string = id_combo.id;
        has_hidden_sub_camera = id_combo.sub_id is not None
        if has_hidden_sub_camera:
            id_combo_string += ":" + id_combo.sub_id
            scenes = [scene for scene in scenes if HIDDEN_PHYSICAL_CAMERA_TESTS[scene]]
        # Loop capturing images until user confirm test scene is correct
        camera_id_arg = "camera=" + id_combo.id
        print "Preparing to run ITS on camera", id_combo_string, "for scenes ", scenes

        os.mkdir(os.path.join(topdir, id_combo_string))
        for d in scenes:
            os.mkdir(os.path.join(topdir, id_combo_string, d))

        tot_tests = []
        tot_pass = 0
        for scene in scenes:
            skip_code = None
            tests = [(s[:-3], os.path.join("tests", scene, s))
                     for s in os.listdir(os.path.join("tests", scene))
                     if s[-3:] == ".py" and s[:4] == "test"]
            tests.sort()
            tot_tests.extend(tests)

            summary = "Cam" + id_combo_string + " " + scene + "\n"
            numpass = 0
            numskip = 0
            num_not_mandated_fail = 0
            numfail = 0
            validate_switch = True
            if scene_req[scene] is not None:
                out_path = os.path.join(topdir, id_combo_string, scene+".jpg")
                out_arg = "out=" + out_path
                if scene == 'sensor_fusion':
                    skip_code = skip_sensor_fusion(id_combo.id)
                    if rot_rig_id or skip_code == SKIP_RET_CODE:
                        validate_switch = False
                if skip_scene_validation:
                    validate_switch = False
                cmd = None
                if auto_scene_switch:
                    if (not merge_result_switch or
                            (merge_result_switch and id_combo_string == '0')):
                        scene_arg = 'scene=' + scene
                        fov_arg = 'fov=' + camera_fov
                        cmd = ['python',
                               os.path.join(os.getcwd(), 'tools/load_scene.py'),
                               scene_arg, chart_dist_arg, fov_arg, screen_id_arg]
                    else:
                        time.sleep(CHART_DELAY)
                else:
                    # Skip scene validation under certain conditions
                    if validate_switch and not merge_result_switch:
                        scene_arg = 'scene=' + scene_req[scene]
                        extra_args = scene_extra_args.get(scene, [])
                        cmd = ['python',
                               os.path.join(os.getcwd(),
                                            'tools/validate_scene.py'),
                               camera_id_arg, out_arg,
                               scene_arg, device_id_arg] + extra_args
                if cmd is not None:
                    valid_scene_code = subprocess.call(cmd, cwd=topdir)
                    assert valid_scene_code == 0
            print 'Start running ITS on camera %s, %s' % (id_combo_string, scene)
            # Extract chart from scene for scene3 once up front
            chart_loc_arg = ''
            chart_height = CHART_HEIGHT
            if scene == 'scene3':
                chart_height *= its.cv2image.calc_chart_scaling(
                        chart_distance, camera_fov)
                chart = its.cv2image.Chart(SCENE3_FILE, chart_height,
                                           chart_distance, CHART_SCALE_START,
                                           CHART_SCALE_STOP, CHART_SCALE_STEP,
                                           id_combo.id)
                chart_loc_arg = 'chart_loc=%.2f,%.2f,%.2f,%.2f,%.3f' % (
                        chart.xnorm, chart.ynorm, chart.wnorm, chart.hnorm,
                        chart.scale)
            # Run each test, capturing stdout and stderr.
            for (testname, testpath) in tests:
                # Only pick predefined tests for hidden physical camera
                if has_hidden_sub_camera and \
                        testname not in HIDDEN_PHYSICAL_CAMERA_TESTS[scene]:
                    numskip += 1
                    continue
                if auto_scene_switch:
                    if merge_result_switch and id_combo_string == '0':
                        # Send an input event to keep the screen not dimmed.
                        # Since we are not using camera of chart screen, FOCUS event
                        # should do nothing but keep the screen from dimming.
                        # The "sleep after x minutes of inactivity" display setting
                        # determines how long this command can keep screen bright.
                        # Setting it to something like 30 minutes should be enough.
                        cmd = ('adb -s %s shell input keyevent FOCUS'
                               % chart_host_id)
                        subprocess.call(cmd.split())
                t0 = time.time()
                for num_try in range(NUM_TRYS):
                    outdir = os.path.join(topdir, id_combo_string, scene)
                    outpath = os.path.join(outdir, testname+'_stdout.txt')
                    errpath = os.path.join(outdir, testname+'_stderr.txt')
                    if scene == 'sensor_fusion':
                        if skip_code is not SKIP_RET_CODE:
                            if rot_rig_id:
                                print 'Rotating phone w/ rig %s' % rot_rig_id
                                rig = ('python tools/rotation_rig.py rotator=%s' %
                                       rot_rig_id)
                                subprocess.Popen(rig.split())
                            else:
                                print 'Rotate phone 15s as shown in SensorFusion.pdf'
                        else:
                            test_code = skip_code
                    if skip_code is not SKIP_RET_CODE:
                        cmd = ['python', os.path.join(os.getcwd(), testpath)]
                        cmd += one_camera_argv + ["camera="+id_combo_string] + [chart_loc_arg]
                        cmd += [chart_dist_arg]
                        with open(outpath, 'w') as fout, open(errpath, 'w') as ferr:
                            test_code = run_subprocess_with_timeout(
                                cmd, fout, ferr, outdir)
                    if test_code == 0 or test_code == SKIP_RET_CODE:
                        break
                    else:
                        socket_fail = evaluate_socket_failure(errpath)
                        if socket_fail or test_code == PROC_TIMEOUT_CODE:
                            if num_try != NUM_TRYS-1:
                                print ' Retry %s/%s' % (scene, testname)
                            else:
                                break
                        else:
                            break
                t1 = time.time()

                test_failed = False
                if test_code == 0:
                    retstr = "PASS "
                    numpass += 1
                elif test_code == SKIP_RET_CODE:
                    retstr = "SKIP "
                    numskip += 1
                elif test_code != 0 and testname in NOT_YET_MANDATED[scene]:
                    retstr = "FAIL*"
                    num_not_mandated_fail += 1
                else:
                    retstr = "FAIL "
                    numfail += 1
                    test_failed = True

                msg = "%s %s/%s [%.1fs]" % (retstr, scene, testname, t1-t0)
                print msg
                its.device.adb_log(device_id, msg)
                msg_short = "%s %s [%.1fs]" % (retstr, testname, t1-t0)
                if test_failed:
                    summary += msg_short + "\n"

            if numskip > 0:
                skipstr = ", %d test%s skipped" % (
                    numskip, "s" if numskip > 1 else "")
            else:
                skipstr = ""

            test_result = "\n%d / %d tests passed (%.1f%%)%s" % (
                numpass + num_not_mandated_fail, len(tests) - numskip,
                100.0 * float(numpass + num_not_mandated_fail) /
                (len(tests) - numskip)
                if len(tests) != numskip else 100.0, skipstr)
            print test_result

            if num_not_mandated_fail > 0:
                msg = "(*) tests are not yet mandated"
                print msg

            tot_pass += numpass
            print "%s compatibility score: %.f/100\n" % (
                    scene, 100.0 * numpass / len(tests))

            summary_path = os.path.join(topdir, id_combo_string, scene, "summary.txt")
            with open(summary_path, "w") as f:
                f.write(summary)

            passed = numfail == 0
            results[scene][result_key] = (ItsSession.RESULT_PASS if passed
                                          else ItsSession.RESULT_FAIL)
            results[scene][ItsSession.SUMMARY_KEY] = summary_path

        if tot_tests:
            print "Compatibility Score: %.f/100" % (100.0 * tot_pass / len(tot_tests))
        else:
            print "Compatibility Score: 0/100"

        msg = "Reporting ITS result to CtsVerifier"
        print msg
        its.device.adb_log(device_id, msg)
        if merge_result_switch:
            # results are modified by report_result
            results_backup = copy.deepcopy(results)
            its.device.report_result(result_device_id, id_combo_string, results_backup)

        # Report hidden_physical_id results as well.
        its.device.report_result(device_id, id_combo_string, results)

    if auto_scene_switch:
        if merge_result_switch:
            print 'Skip shutting down chart screen'
        else:
            print 'Shutting down chart screen: ', chart_host_id
            screen_id_arg = ('screen=%s' % chart_host_id)
            cmd = ['python', os.path.join(os.environ['CAMERA_ITS_TOP'], 'tools',
                                          'turn_off_screen.py'), screen_id_arg]
            screen_off_code = subprocess.call(cmd)
            assert screen_off_code == 0

            print 'Shutting down DUT screen: ', device_id
            screen_id_arg = ('screen=%s' % device_id)
            cmd = ['python', os.path.join(os.environ['CAMERA_ITS_TOP'], 'tools',
                                          'turn_off_screen.py'), screen_id_arg]
            screen_off_code = subprocess.call(cmd)
            assert screen_off_code == 0

    print "ITS tests finished. Please go back to CtsVerifier and proceed"

if __name__ == '__main__':
    main()
