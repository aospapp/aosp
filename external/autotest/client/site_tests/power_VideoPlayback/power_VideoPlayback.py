# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
import logging
import os
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import file_utils
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.audio import audio_helper
from autotest_lib.client.cros.input_playback import keyboard
from autotest_lib.client.cros.power import power_test


class power_VideoPlayback(power_test.power_Test):
    """class for power_VideoPlayback test.
    """
    version = 1

    _BASE_URL='http://commondatastorage.googleapis.com/chromiumos-test-assets-public/tast/cros/video/perf/'

    # list of video name and url.
    _VIDEOS = [
        ('h264_1080_30fps',
         _BASE_URL + 'h264/1080p_30fps_300frames_20181225.h264.mp4'
        ),
        ('h264_1080_60fps',
         _BASE_URL + 'h264/1080p_60fps_600frames_20181225.h264.mp4'
        ),
        ('h264_4k_30fps',
         _BASE_URL + 'h264/2160p_30fps_300frames_20181225.h264.mp4'
        ),
        ('h264_4k_60fps',
         _BASE_URL + 'h264/2160p_60fps_600frames_20181225.h264.mp4'
        ),
        ('vp8_1080_30fps',
         _BASE_URL + 'vp8/1080p_30fps_300frames_20181225.vp8.webm'
        ),
        ('vp8_1080_60fps',
         _BASE_URL + 'vp8/1080p_60fps_600frames_20181225.vp8.webm'
        ),
        ('vp8_4k_30fps',
         _BASE_URL + 'vp8/2160p_30fps_300frames_20181225.vp8.webm'
        ),
        ('vp8_4k_60fps',
         _BASE_URL + 'vp8/2160p_60fps_600frames_20181225.vp8.webm'
        ),
        ('vp9_1080_30fps',
         _BASE_URL + 'vp9/1080p_30fps_300frames_20181225.vp9.webm'
        ),
        ('vp9_1080_60fps',
         _BASE_URL + 'vp9/1080p_60fps_600frames_20181225.vp9.webm'
        ),
        ('vp9_4k_30fps',
         _BASE_URL + 'vp9/2160p_30fps_300frames_20181225.vp9.webm'
        ),
        ('vp9_4k_60fps',
         _BASE_URL + 'vp9/2160p_60fps_600frames_20181225.vp9.webm'
        ),
        ('av1_720_30fps',
         _BASE_URL + 'av1/720p_30fps_300frames_20190305.av1.mp4'
        ),
        ('av1_720_60fps',
         _BASE_URL + 'av1/720p_60fps_600frames_20190305.av1.mp4'
        ),
        ('av1_1080_30fps',
         _BASE_URL + 'av1/1080p_30fps_300frames_20190305.av1.mp4'
        ),
        ('av1_1080_60fps',
         _BASE_URL + 'av1/1080p_60fps_600frames_20190305.av1.mp4'
        ),
    ]

    # Ram disk location to download video file.
    # We use ram disk to avoid power hit from network / disk usage.
    _RAMDISK = '/tmp/ramdisk'

    # Time in seconds to wait after set up before starting test.
    _WAIT_FOR_IDLE = 10

    # Time in seconds to measure power per video file.
    _MEASUREMENT_DURATION = 30

    # Chrome arguemnts to disable HW video decode
    _DISABLE_HW_VIDEO_DECODE_ARGS = '--disable-accelerated-video-decode'

    def initialize(self, pdash_note='', seconds_period=5):
        """Create and mount ram disk to download video."""
        super(power_VideoPlayback, self).initialize(
                seconds_period=seconds_period, pdash_note=pdash_note)
        utils.run('mkdir -p %s' % self._RAMDISK)
        # Don't throw an exception on errors.
        result = utils.run('mount -t ramfs -o context=u:object_r:tmpfs:s0 '
                           'ramfs %s' % self._RAMDISK, ignore_status=True)
        if result.exit_status:
            logging.info('cannot mount ramfs with context=u:object_r:tmpfs:s0,'
                         ' trying plain mount')
            # Try again without selinux options.  This time fail on error.
            utils.run('mount -t ramfs ramfs %s' % self._RAMDISK)
        audio_helper.set_volume_levels(10, 10)

    def _play_video(self, cr, local_path):
        """Opens the video and plays it.

        @param cr: Autotest Chrome instance.
        @param local_path: path to the local video file to play.
        """
        tab = cr.browser.tabs[0]
        tab.Navigate(cr.browser.platform.http_server.UrlOf(local_path))
        tab.WaitForDocumentReadyStateToBeComplete()
        tab.EvaluateJavaScript("document.getElementsByTagName('video')[0]."
                               "loop=true")

    def _calculate_dropped_frame_percent(self, tab):
        """Calculate percent of dropped frame.

        @param tab: tab object that played video in Autotest Chrome instance.
        """
        decoded_frame_count = tab.EvaluateJavaScript(
                "document.getElementsByTagName"
                "('video')[0].webkitDecodedFrameCount")
        dropped_frame_count = tab.EvaluateJavaScript(
                "document.getElementsByTagName"
                "('video')[0].webkitDroppedFrameCount")
        if decoded_frame_count != 0:
            dropped_frame_percent = \
                    100.0 * dropped_frame_count / decoded_frame_count
        else:
            logging.error("No frame is decoded. Set drop percent to 100.")
            dropped_frame_percent = 100.0

        logging.info("Decoded frames=%d, dropped frames=%d, percent=%f",
                decoded_frame_count, dropped_frame_count, dropped_frame_percent)
        return dropped_frame_percent

    def run_once(self, videos=None, secs_per_video=_MEASUREMENT_DURATION,
                 use_hw_decode=True):
        """run_once method.

        @param videos: list of tuple of tagname and video url to test.
        @param secs_per_video: time in seconds to play video and measure power.
        @param use_hw_decode: if False, disable hw video decoding.
        """
        videos_local = []
        loop = 0

        if not videos:
            videos = self._VIDEOS

        # Download video to ramdisk
        for name, url in videos:
            local_path = os.path.join(self._RAMDISK, os.path.basename(url))
            logging.info('Downloading %s to %s', url, local_path)
            file_utils.download_file(url, local_path)
            videos_local.append((name, local_path))

        extra_browser_args = []
        if not use_hw_decode:
            extra_browser_args.append(self._DISABLE_HW_VIDEO_DECODE_ARGS)

        with chrome.Chrome(extra_browser_args=extra_browser_args,
                           init_network_controller=True) as self.cr:
            self.cr.browser.platform.SetHTTPServerDirectories(self._RAMDISK)
            tab = self.cr.browser.tabs.New()
            tab.Activate()

            # Just measure power in full-screen.
            fullscreen = tab.EvaluateJavaScript('document.webkitIsFullScreen')
            if not fullscreen:
                with keyboard.Keyboard() as keys:
                    keys.press_key('f4')

            time.sleep(self._WAIT_FOR_IDLE)
            self.start_measurements()

            for name, url in videos_local:
                logging.info('Playing video: %s', name)
                self._play_video(self.cr, url)
                tagname = '%s_%s' % (self.tagged_testname, name)
                loop_start = time.time()
                self.loop_sleep(loop, secs_per_video)
                self.keyvals[name + '_dropped_frame_percent'] = \
                        self._calculate_dropped_frame_percent(tab)
                self.checkpoint_measurements(tagname, loop_start)
                loop += 1

    def cleanup(self):
        """Unmount ram disk."""
        utils.run('umount %s' % self._RAMDISK)
        super(power_VideoPlayback, self).cleanup()
