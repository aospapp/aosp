# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
#
# Copyright 2018 Google Inc.
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
#
################################################################################
# Defines a docker image that can build cras fuzzers.
#
FROM gcr.io/oss-fuzz-base/base-builder-rust

RUN apt-get -y update && \
      apt-get install -y \
      automake \
      build-essential \
      cmake \
      g++ \
      git \
      ladspa-sdk \
      libasound-dev \
      libdbus-1-dev \
      libgtest-dev \
      libncurses5-dev \
      libsbc-dev \
      libsndfile-dev \
      libspeexdsp-dev \
      libsystemd-dev \
      libtool \
      libudev-dev \
      wget \
      vim \
      zip
RUN apt-get clean
RUN cd /tmp && git clone https://github.com/ndevilla/iniparser.git && \
      cd iniparser && \
      make && \
      cp libiniparser.* /usr/local/lib && \
      cp src/dictionary.h src/iniparser.h /usr/local/include && \
      chmod 644 /usr/local/include/dictionary.h /usr/local/include/iniparser.h && \
      chmod 644 /usr/local/lib/libiniparser.a && \
      chmod 755 /usr/local/lib/libiniparser.so.*

RUN cd /tmp && git clone https://github.com/google/googletest.git -b v1.8.x && \
      cd googletest && \
      mkdir build && cd build && \
      cmake .. -DBUILD_SHARED_LIBS=ON \
         -DINSTALL_GTEST=ON \
         -DCMAKE_INSTALL_PREFIX:PATH=/usr && \
      make && make install

# Need to build and install alsa so there is a static lib.
RUN mkdir -p /tmp/alsa-build && cd /tmp/alsa-build && \
      wget ftp://ftp.alsa-project.org/pub/lib/alsa-lib-1.1.4.1.tar.bz2 && \
      bzip2 -f -d alsa-lib-* && \
      tar xf alsa-lib-* && \
      cd alsa-lib-* && \
      ./configure --enable-static --disable-shared && \
      make clean && \
      make -j$(nproc) all && \
      make install


RUN cd $SRC && git clone https://chromium.googlesource.com/chromiumos/third_party/adhd
WORKDIR adhd/cras
COPY build.sh $SRC/
