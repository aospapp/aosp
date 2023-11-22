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

import math
import os.path
import re
import sys
import cv2

import its.caps
import its.device
import its.image
import its.objects

import numpy as np

ALIGN_TOL_MM = 4.0E-3  # mm
ALIGN_TOL = 0.01  # multiplied by sensor diagonal to convert to pixels
CHART_DISTANCE_CM = 22  # cm
CIRCLE_RTOL = 0.1
GYRO_REFERENCE = 1
NAME = os.path.basename(__file__).split('.')[0]
TRANS_REF_MATRIX = np.array([0, 0, 0])

def convert_to_world_coordinates(x, y, r, t, k, z_w):
    """Convert x,y coordinates to world coordinates.

    Conversion equation is:
    A = [[x*r[2][0] - dot(k_row0, r_col0), x*r_[2][1] - dot(k_row0, r_col1)],
         [y*r[2][0] - dot(k_row1, r_col0), y*r_[2][1] - dot(k_row1, r_col1)]]
    b = [[z_w*dot(k_row0, r_col2) + dot(k_row0, t) - x*(r[2][2]*z_w + t[2])],
         [z_w*dot(k_row1, r_col2) + dot(k_row1, t) - y*(r[2][2]*z_w + t[2])]]

    [[x_w], [y_w]] = inv(A) * b

    Args:
        x:      x location in pixel space
        y:      y location in pixel space
        r:      rotation matrix
        t:      translation matrix
        k:      intrinsic matrix
        z_w:    z distance in world space

    Returns:
        x_w:    x in meters in world space
        y_w:    y in meters in world space
    """
    c_1 = r[2, 2] * z_w + t[2]
    k_x1 = np.dot(k[0, :], r[:, 0])
    k_x2 = np.dot(k[0, :], r[:, 1])
    k_x3 = z_w * np.dot(k[0, :], r[:, 2]) + np.dot(k[0, :], t)
    k_y1 = np.dot(k[1, :], r[:, 0])
    k_y2 = np.dot(k[1, :], r[:, 1])
    k_y3 = z_w * np.dot(k[1, :], r[:, 2]) + np.dot(k[1, :], t)

    a = np.array([[x*r[2][0]-k_x1, x*r[2][1]-k_x2],
                  [y*r[2][0]-k_y1, y*r[2][1]-k_y2]])
    b = np.array([[k_x3-x*c_1], [k_y3-y*c_1]])
    return np.dot(np.linalg.inv(a), b)


def convert_to_image_coordinates(p_w, r, t, k):
    p_c = np.dot(r, p_w) + t
    p_h = np.dot(k, p_c)
    return p_h[0] / p_h[2], p_h[1] / p_h[2]


def rotation_matrix(rotation):
    """Convert the rotation parameters to 3-axis data.

    Args:
        rotation:   android.lens.Rotation vector
    Returns:
        3x3 matrix w/ rotation parameters
    """
    x = rotation[0]
    y = rotation[1]
    z = rotation[2]
    w = rotation[3]
    return np.array([[1-2*y**2-2*z**2, 2*x*y-2*z*w, 2*x*z+2*y*w],
                     [2*x*y+2*z*w, 1-2*x**2-2*z**2, 2*y*z-2*x*w],
                     [2*x*z-2*y*w, 2*y*z+2*x*w, 1-2*x**2-2*y**2]])


# TODO: merge find_circle() & test_aspect_ratio_and_crop.measure_aspect_ratio()
# for a unified circle script that is and in pymodules/image.py
def find_circle(gray, name):
    """Find the black circle in the image.

    Args:
        gray:           numpy grayscale array with pixel values in [0,255].
        name:           string of file name.
    Returns:
        circle:         (circle_center_x, circle_center_y, radius)
    """
    size = gray.shape
    # otsu threshold to binarize the image
    _, img_bw = cv2.threshold(np.uint8(gray), 0, 255,
                              cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # connected component
    cv2_version = cv2.__version__
    if cv2_version.startswith('2.4.'):
        contours, hierarchy = cv2.findContours(255-img_bw, cv2.RETR_TREE,
                                               cv2.CHAIN_APPROX_SIMPLE)
    elif cv2_version.startswith('3.2.'):
        _, contours, hierarchy = cv2.findContours(255-img_bw, cv2.RETR_TREE,
                                                  cv2.CHAIN_APPROX_SIMPLE)

    # Check each component and find the black circle
    min_cmpt = size[0] * size[1] * 0.005
    max_cmpt = size[0] * size[1] * 0.35
    num_circle = 0
    for ct, hrch in zip(contours, hierarchy[0]):
        # The radius of the circle is 1/3 of the length of the square, meaning
        # around 1/3 of the area of the square
        # Parental component should exist and the area is acceptable.
        # The contour of a circle should have at least 5 points
        child_area = cv2.contourArea(ct)
        if (hrch[3] == -1 or child_area < min_cmpt or child_area > max_cmpt
                    or len(ct) < 15):
            continue
        # Check the shapes of current component and its parent
        child_shape = component_shape(ct)
        parent = hrch[3]
        prt_shape = component_shape(contours[parent])
        prt_area = cv2.contourArea(contours[parent])
        dist_x = abs(child_shape['ctx']-prt_shape['ctx'])
        dist_y = abs(child_shape['cty']-prt_shape['cty'])
        # 1. 0.56*Parent's width < Child's width < 0.76*Parent's width.
        # 2. 0.56*Parent's height < Child's height < 0.76*Parent's height.
        # 3. Child's width > 0.1*Image width
        # 4. Child's height > 0.1*Image height
        # 5. 0.25*Parent's area < Child's area < 0.45*Parent's area
        # 6. Child is a black, and Parent is white
        # 7. Center of Child and center of parent should overlap
        if (prt_shape['width'] * 0.56 < child_shape['width']
                    < prt_shape['width'] * 0.76
                    and prt_shape['height'] * 0.56 < child_shape['height']
                    < prt_shape['height'] * 0.76
                    and child_shape['width'] > 0.1 * size[1]
                    and child_shape['height'] > 0.1 * size[0]
                    and 0.30 * prt_area < child_area < 0.50 * prt_area
                    and img_bw[child_shape['cty']][child_shape['ctx']] == 0
                    and img_bw[child_shape['top']][child_shape['left']] == 255
                    and dist_x < 0.1 * child_shape['width']
                    and dist_y < 0.1 * child_shape['height']):
            # Calculate circle center and size
            circle_ctx = float(child_shape['ctx'])
            circle_cty = float(child_shape['cty'])
            circle_w = float(child_shape['width'])
            circle_h = float(child_shape['height'])
            num_circle += 1
            # If more than one circle found, break
            if num_circle == 2:
                break
    its.image.write_image(gray[..., np.newaxis]/255.0, name)

    if num_circle == 0:
        print 'No black circle was detected. Please take pictures according',
        print 'to instruction carefully!\n'
        assert num_circle == 1

    if num_circle > 1:
        print 'More than one black circle was detected. Background of scene',
        print 'may be too complex.\n'
        assert num_circle == 1
    return [circle_ctx, circle_cty, (circle_w+circle_h)/4.0]


def component_shape(contour):
    """Measure the shape of a connected component.

    Args:
        contour: return from cv2.findContours. A list of pixel coordinates of
        the contour.

    Returns:
        The most left, right, top, bottom pixel location, height, width, and
        the center pixel location of the contour.
    """
    shape = {'left': np.inf, 'right': 0, 'top': np.inf, 'bottom': 0,
             'width': 0, 'height': 0, 'ctx': 0, 'cty': 0}
    for pt in contour:
        if pt[0][0] < shape['left']:
            shape['left'] = pt[0][0]
        if pt[0][0] > shape['right']:
            shape['right'] = pt[0][0]
        if pt[0][1] < shape['top']:
            shape['top'] = pt[0][1]
        if pt[0][1] > shape['bottom']:
            shape['bottom'] = pt[0][1]
    shape['width'] = shape['right'] - shape['left'] + 1
    shape['height'] = shape['bottom'] - shape['top'] + 1
    shape['ctx'] = (shape['left']+shape['right'])/2
    shape['cty'] = (shape['top']+shape['bottom'])/2
    return shape


def define_reference_camera(pose_reference, cam_reference):
    """Determine the reference camera.

    Args:
        pose_reference: 0 for cameras, 1 for gyro
        cam_reference:  dict with key of physical camera and value True/False
    Returns:
        i_ref:          physical id of reference camera
        i_2nd:          physical id of secondary camera
    """

    if pose_reference == GYRO_REFERENCE:
        print 'pose_reference is GYRO'
        i_ref = list(cam_reference.keys())[0]  # pick first camera as ref
        i_2nd = list(cam_reference.keys())[1]
    else:
        print 'pose_reference is CAMERA'
        i_ref = (k for (k, v) in cam_reference.iteritems() if v).next()
        i_2nd = (k for (k, v) in cam_reference.iteritems() if not v).next()
    return i_ref, i_2nd


def main():
    """Test the multi camera system parameters related to camera spacing.

    Using the multi-camera physical cameras, take a picture of scene4
    (a black circle and surrounding square on a white background) with
    one of the physical cameras. Then find the circle center. Using the
    parameters:
        android.lens.poseReference
        android.lens.poseTranslation
        android.lens.poseRotation
        android.lens.instrinsicCalibration
        android.lens.distortion (if available)
    project the circle center to the world coordinates for each camera.
    Compare the difference between the two cameras' circle centers in
    world coordinates.

    Reproject the world coordinates back to pixel coordinates and compare
    against originals as a sanity check.

    Compare the circle sizes if the focal lengths of the cameras are
    different using
        android.lens.availableFocalLengths.
    """
    chart_distance = CHART_DISTANCE_CM
    for s in sys.argv[1:]:
        if s[:5] == 'dist=' and len(s) > 5:
            chart_distance = float(re.sub('cm', '', s[5:]))
            print 'Using chart distance: %.1fcm' % chart_distance
    chart_distance *= 1.0E-2

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()
        its.caps.skip_unless(its.caps.read_3a(props) and
                             its.caps.per_frame_control(props) and
                             its.caps.logical_multi_camera(props))

        # Find physical camera IDs that support raw, and skip if less than 2
        ids = its.caps.logical_multi_camera_physical_ids(props)
        props_physical = {}
        physical_ids = []
        for i in ids:
            prop = cam.get_camera_properties_by_id(i)
            if its.caps.raw16(prop) and len(physical_ids) < 2:
                physical_ids.append(i)
                props_physical[i] = cam.get_camera_properties_by_id(i)

        debug = its.caps.debug_mode()
        avail_fls = props['android.lens.info.availableFocalLengths']
        pose_reference = props['android.lens.poseReference']

        # Find highest resolution image and determine formats
        fmts = ['yuv']
        if len(physical_ids) == 2:
            fmts.insert(0, 'raw')  # insert in first location in list
        else:
            physical_ids = ids[0:1]

        w, h = its.objects.get_available_output_sizes('yuv', props)[0]

        # do captures on 2 cameras
        caps = {}
        for i, fmt in enumerate(fmts):
            out_surfaces = [{'format': 'yuv', 'width': w, 'height': h},
                            {'format': fmt, 'physicalCamera': physical_ids[0]},
                            {'format': fmt, 'physicalCamera': physical_ids[1]}]

            out_surfaces_supported = cam.is_stream_combination_supported(out_surfaces)
            its.caps.skip_unless(out_surfaces_supported)

            # Do 3A and get the values
            s, e, _, _, fd = cam.do_3a(get_results=True,
                                       lock_ae=True, lock_awb=True)
            if fmt == 'raw':
                e_corrected = e * 2  # brighten RAW images
            else:
                e_corrected = e
            print 'out_surfaces:', out_surfaces
            req = its.objects.manual_capture_request(s, e_corrected, fd)
            _, caps[(fmt, physical_ids[0])], caps[(fmt, physical_ids[1])] = cam.do_capture(
                    req, out_surfaces);

    for j, fmt in enumerate(fmts):
        size = {}
        k = {}
        cam_reference = {}
        r = {}
        t = {}
        circle = {}
        fl = {}
        sensor_diag = {}
        print '\nFormat:', fmt
        for i in physical_ids:
            # process image
            img = its.image.convert_capture_to_rgb_image(
                    caps[(fmt, i)], props=props_physical[i])
            size[i] = (caps[fmt, i]['width'], caps[fmt, i]['height'])

            # save images if debug
            if debug:
                its.image.write_image(img, '%s_%s_%s.jpg' % (NAME, fmt, i))

            # convert to [0, 255] images
            img *= 255

            # scale to match calibration data if RAW
            if fmt == 'raw':
                img = cv2.resize(img.astype(np.uint8), None, fx=2, fy=2)
            else:
                img = img.astype(np.uint8)

            # load parameters for each physical camera
            ical = props_physical[i]['android.lens.intrinsicCalibration']
            assert len(ical) == 5, 'android.lens.instrisicCalibration incorrect.'
            k[i] = np.array([[ical[0], ical[4], ical[2]],
                             [0, ical[1], ical[3]],
                             [0, 0, 1]])
            if j == 0:
                print 'Camera %s' % i
                print ' k:', k[i]

            rotation = np.array(props_physical[i]['android.lens.poseRotation'])
            if j == 0:
                print ' rotation:', rotation
            assert len(rotation) == 4, 'poseRotation has wrong # of params.'
            r[i] = rotation_matrix(rotation)

            t[i] = np.array(props_physical[i]['android.lens.poseTranslation'])
            if j == 0:
                print ' translation:', t[i]
            assert len(t[i]) == 3, 'poseTranslation has wrong # of params.'
            if (t[i] == TRANS_REF_MATRIX).all():
                cam_reference[i] = True
            else:
                cam_reference[i] = False

            # API spec defines poseTranslation as the world coordinate p_w_cam of
            # optics center. When applying [R|t] to go from world coordinates to
            # camera coordinates, we need -R*p_w_cam of the coordinate reported in
            # metadata.
            # ie. for a camera with optical center at world coordinate (5, 4, 3)
            # and identity rotation, to convert a world coordinate into the
            # camera's coordinate, we need a translation vector of [-5, -4, -3]
            # so that: [I|[-5, -4, -3]^T] * [5, 4, 3]^T = [0,0,0]^T
            t[i] = -1.0 * np.dot(r[i], t[i])
            if debug and j == 1:
                print 't:', t[i]
                print 'r:', r[i]

            # Correct lens distortion to image (if available)
            if its.caps.distortion_correction(props_physical[i]) and fmt == 'raw':
                distort = np.array(props_physical[i]['android.lens.distortion'])
                assert len(distort) == 5, 'distortion has wrong # of params.'
                cv2_distort = np.array([distort[0], distort[1],
                                        distort[3], distort[4],
                                        distort[2]])
                print ' cv2 distortion params:', cv2_distort
                its.image.write_image(img/255.0, '%s_%s_%s.jpg' % (
                        NAME, fmt, i))
                img = cv2.undistort(img, k[i], cv2_distort)
                its.image.write_image(img/255.0, '%s_%s_correct_%s.jpg' % (
                        NAME, fmt, i))

            # Find the circles in grayscale image
            circle[i] = find_circle(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY),
                                    '%s_%s_gray_%s.jpg' % (NAME, fmt, i))
            print "Circle radius ", i, ": ", circle[i][2]

            # Undo zoom to image (if applicable). Assume that the maximum
            # physical YUV image size is close to active array size.
            if fmt == 'yuv':
                ar = props_physical[i]['android.sensor.info.activeArraySize']
                arw = ar['right'] - ar['left']
                arh = ar['bottom'] - ar['top']
                cr = caps[(fmt, i)]['metadata']['android.scaler.cropRegion'];
                crw = cr['right'] - cr['left']
                crh = cr['bottom'] - cr['top']
                # Assume pixels remain square after zoom, so use same zoom
                # ratios for x and y.
                zoom_ratio = min(1.0 * arw / crw, 1.0 * arh / crh);
                circle[i][0] = cr['left'] + circle[i][0] / zoom_ratio
                circle[i][1] = cr['top'] + circle[i][1] / zoom_ratio
                circle[i][2] = circle[i][2] / zoom_ratio

            # Find focal length & sensor size
            fl[i] = props_physical[i]['android.lens.info.availableFocalLengths'][0]
            sensor_diag[i] = math.sqrt(size[i][0] ** 2 + size[i][1] ** 2)

        i_ref, i_2nd = define_reference_camera(pose_reference, cam_reference)
        print 'reference camera: %s, secondary camera: %s' % (i_ref, i_2nd)

        # Convert circle centers to real world coordinates
        x_w = {}
        y_w = {}
        if props['android.lens.facing']:
            # API spec defines +z is pointing out from screen
            print 'lens facing BACK'
            chart_distance *= -1
        for i in [i_ref, i_2nd]:
            x_w[i], y_w[i] = convert_to_world_coordinates(
                    circle[i][0], circle[i][1], r[i], t[i], k[i], chart_distance)

        # Back convert to image coordinates for sanity check
        x_p = {}
        y_p = {}
        x_p[i_2nd], y_p[i_2nd] = convert_to_image_coordinates(
                [x_w[i_ref], y_w[i_ref], chart_distance],
                r[i_2nd], t[i_2nd], k[i_2nd])
        x_p[i_ref], y_p[i_ref] = convert_to_image_coordinates(
                [x_w[i_2nd], y_w[i_2nd], chart_distance],
                r[i_ref], t[i_ref], k[i_ref])

        # Summarize results
        for i in [i_ref, i_2nd]:
            print ' Camera: %s' % i
            print ' x, y (pixels): %.1f, %.1f' % (circle[i][0], circle[i][1])
            print ' x_w, y_w (mm): %.2f, %.2f' % (x_w[i]*1.0E3, y_w[i]*1.0E3)
            print ' x_p, y_p (pixels): %.1f, %.1f' % (x_p[i], y_p[i])

        # Check center locations
        err = np.linalg.norm(np.array([x_w[i_ref], y_w[i_ref]]) -
                             np.array([x_w[i_2nd], y_w[i_2nd]]))
        print 'Center location err (mm): %.2f' % (err*1E3)
        msg = 'Center locations %s <-> %s too different!' % (i_ref, i_2nd)
        msg += ' val=%.2fmm, THRESH=%.fmm' % (err*1E3, ALIGN_TOL_MM*1E3)
        assert err < ALIGN_TOL, msg

        # Check projections back into pixel space
        for i in [i_ref, i_2nd]:
            err = np.linalg.norm(np.array([circle[i][0], circle[i][1]]) -
                                 np.array([x_p[i], y_p[i]]))
            print 'Camera %s projection error (pixels): %.1f' % (i, err)
            tol = ALIGN_TOL * sensor_diag[i]
            msg = 'Camera %s project locations too different!' % i
            msg += ' diff=%.2f, TOL=%.2f' % (err, tol)
            assert err < tol, msg

        # Check focal length and circle size if more than 1 focal length
        if len(avail_fls) > 1:
            print 'Circle radii (pixels); ref: %.1f, 2nd: %.1f' % (
                    circle[i_ref][2], circle[i_2nd][2])
            print 'Focal lengths (diopters); ref: %.2f, 2nd: %.2f' % (
                    fl[i_ref], fl[i_2nd])
            print 'Sensor diagonals (pixels); ref: %.2f, 2nd: %.2f' % (
                    sensor_diag[i_ref], sensor_diag[i_2nd])
            msg = 'Circle size scales improperly! RTOL=%.1f' % CIRCLE_RTOL
            msg += '\nMetric: radius/focal_length*sensor_diag should be equal.'
            assert np.isclose(circle[i_ref][2]/fl[i_ref]*sensor_diag[i_ref],
                              circle[i_2nd][2]/fl[i_2nd]*sensor_diag[i_2nd],
                              rtol=CIRCLE_RTOL), msg

if __name__ == '__main__':
    main()
