# Copyright 2020 - The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the',  help='License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an',  help='AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Script that makes it easy to have the docker build correspond to a particular
# gfxstream Android build id

import os
import subprocess
import sys
import lxml.etree as etree

gfxstream_manifest_filename = sys.argv[1]
target_manifest_filename = sys.argv[2]

# Don't need to check out the entire emulator repo to build gfxstream
gfxstream_projects = set([
  "device/generic/goldfish-opengl",
  "device/generic/vulkan-cereal",
  "platform/external/angle",
  "platform/external/astc-codec",
  "platform/external/boringssl",
  "platform/external/c-ares",
  "platform/external/curl",
  "platform/external/deqp",
  "platform/external/ffmpeg",
  "platform/external/googletest",
  "platform/external/google-benchmark",
  "platform/external/google-breakpad",
  "platform/external/grpc-grpc",
  "platform/external/libffi",
  "platform/external/libvpx",
  "platform/external/libyuv",
  "platform/external/libpng",
  "platform/external/lz4",
  "platform/external/protobuf",
  "platform/external/qemu",
  "platform/external/tinyobjloader",
  "platform/external/nasm",
  "platform/external/zlib",
  "platform/prebuilts/android-emulator-build/common",
  "platform/prebuilts/android-emulator-build/curl",
  "platform/prebuilts/android-emulator-build/mesa",
  "platform/prebuilts/android-emulator-build/mesa-deps",
  "platform/prebuilts/android-emulator-build/protobuf",
  "platform/prebuilts/android-emulator-build/qemu-android-deps",
  "platform/prebuilts/clang/host/linux-x86",
  "platform/prebuilts/cmake/linux-x86",
  "platform/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8",
  "platform/prebuilts/ninja/linux-x86",
])

def generate_filtered_gfxstream_projects(filename):
    outs = []

    out = etree.Element("manifest")

    t = etree.parse(filename)
    r = t.getroot()

    for e in r.findall("project"):
        if e.attrib["name"] in gfxstream_projects:
            outp = etree.SubElement(out, "project")
            outs.append(outp)
            outp.set("name", e.attrib["name"])
            outp.set("path", e.attrib["path"])
            outp.set("revision", e.attrib["revision"])
            outp.set("clone-depth", "1")

    return dict(map(lambda e: (e.attrib["name"], e), outs))

def update_projects(current_gfxstream_projects, target_manifest_filename):
    target_root = etree.parse( \
        target_manifest_filename,
        etree.XMLParser(remove_blank_text=True)).getroot()

    found_projects = []

    for e in target_root.findall("project"):
        if e.attrib["name"] in gfxstream_projects:
            e.set("revision", current_gfxstream_projects[e.attrib["name"]].attrib["revision"])
            e.set("clone-depth", "1")
            found_projects.append(e.attrib["name"])

    projects_to_add = gfxstream_projects - set(found_projects)

    for p in projects_to_add:
        project_element = current_gfxstream_projects[p]
        outp = etree.SubElement(target_root, "project")
        outp.set("name", project_element.attrib["name"])
        outp.set("path", project_element.attrib["path"])
        outp.set("revision", project_element.attrib["revision"])
        outp.set("clone-depth", "1")

    return target_root

print("Generating...")
output_string = etree.tostring( \
    update_projects(
        generate_filtered_gfxstream_projects(gfxstream_manifest_filename),
        target_manifest_filename),
    pretty_print=True,
    xml_declaration=True,encoding="utf-8")

print("Result: ")
print(output_string.decode())
print("Writing result to %s" % target_manifest_filename)

fh = open(target_manifest_filename, 'w')
fh.write(output_string)
fh.close()
