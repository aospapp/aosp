#!/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

import argparse
import json
import logging
import os
import pyaudio
import wave

RECORD_FILE_TEMPLATE = 'recorded_audio_%s.wav'


class DeviceNotFound(Exception):
    """Raises exception if audio capture device is not found."""


class AudioCapture:

    def __init__(self, test_params, path):
        """Creates object to pyaudio and defines audio parameters.

        Args:
            test_params: Audio parameters fetched from config.
            path: Result path.
        """
        self.audio = pyaudio.PyAudio()
        self.audio_format = pyaudio.paInt16
        self.audio_params = test_params
        self.channels = self.audio_params["channel"]
        self.chunk = self.audio_params["chunk"]
        self.sample_rate = self.audio_params["sample_rate"]
        self.file_counter = 0
        self.__input_device = None
        self.record_file_template = os.path.join(path, RECORD_FILE_TEMPLATE)
        if not self.audio_params["ssh_config"]:
            self.__input_device = self.__get_input_device()

    @property
    def name(self):
        try:
            return self.audio_params["ssh_config"]["host"]
        except KeyError:
            return self.__input_device["name"]

    def __get_input_device(self):
        """Checks for the audio capture device."""
        if self.__input_device is None:
            for i in range(self.audio.get_device_count()):
                device_info = self.audio.get_device_info_by_index(i)
                if self.audio_params['input_device'] in device_info['name']:
                    self.__input_device = device_info
                    break
            else:
                logging.error("Audio Capture device {} not found.".format(
                    self.audio_params["input_device"]))
                raise DeviceNotFound("Audio Capture Input device not found")
        return self.__input_device

    def capture_and_store_audio(self, trim_beginning=0, trim_end=0):
        """Records the A2DP streaming.

        Args:
            trim_beginning: how many seconds to trim from the beginning
            trim_end: how many seconds to trim from the end
        """
        if self.audio_params['ssh_config']:
            self.__input_device = self.__get_input_device()
        stream = self.audio.open(
            format=self.audio_format,
            channels=self.channels,
            rate=self.sample_rate,
            input=True,
            frames_per_buffer=self.chunk,
            input_device_index=self.__input_device['index'])
        frames = []
        b_chunks = trim_beginning * (self.sample_rate // self.chunk)
        e_chunks = trim_end * (self.sample_rate // self.chunk)
        total_chunks = self.sample_rate // self.chunk * self.audio_params[
            'record_duration']
        for i in range(total_chunks):
            try:
                data = stream.read(self.chunk)
            except IOError as ex:
                logging.error("Cannot record audio :{}".format(ex))
                return False
            if b_chunks <= i < total_chunks - e_chunks:
                frames.append(data)

        stream.stop_stream()
        stream.close()
        status = self.write_record_file(frames)
        return status

    def last_fileno(self):
        return self.next_fileno() - 1

    def next_fileno(self):
        counter = 0
        while os.path.exists(self.record_file_template % counter):
            counter += 1
        return counter

    def write_record_file(self, frames):
        """Writes the recorded audio into the file.

        Args:
            frames: Recorded audio frames.
        """
        file_name = self.record_file_template % self.next_fileno()
        logging.info('writing to %s' % file_name)
        wf = wave.open(file_name, 'wb')
        wf.setnchannels(self.channels)
        wf.setsampwidth(self.audio.get_sample_size(self.audio_format))
        wf.setframerate(self.sample_rate)
        wf.writeframes(b''.join(frames))
        wf.close()
        return True

    def terminate_audio(self):
        """Terminates the pulse audio instance."""
        self.audio.terminate()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '-p',
        '--path',
        type=str,
        help="Contains path where the recorded files to be stored")
    parser.add_argument(
        '-t',
        '--test_params',
        type=json.loads,
        help="Contains sample rate, channels,"
        " chunk and device index for recording.")
    args = parser.parse_args()
    audio = AudioCapture(args.test_params, args.path)
    audio.capture_and_store_audio(args.test_params['trim_beginning'],
                                  args.test_params['trim_end'])
    audio.terminate_audio()
