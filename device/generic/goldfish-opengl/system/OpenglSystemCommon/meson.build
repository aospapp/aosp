# Copyright 2022 Android Open Source Project
# SPDX-License-Identifier: MIT

files_lib_stream = files(
  'AddressSpaceStream.cpp',
  'HostConnection.cpp',
  'ProcessPipe.cpp',
  'QemuPipeStream.cpp',
  'ThreadInfo.cpp',
  'VirtioGpuPipeStream.cpp',
)

lib_stream = static_library(
   'stream',
   files_lib_stream,
   cpp_args: cpp_args,
   include_directories: [inc_host, inc_opengl_codec, inc_android_emu,
                         inc_render_enc, inc_android_compat,
                         inc_qemu_pipe, inc_qemu_pipe_types, inc_gralloc,
                         inc_vulkan_enc, inc_goldfish_address_space,
                         inc_system, inc_platform],
   link_with: [lib_codec_common, lib_goldfish_address_space, lib_qemu_pipe,
               lib_render_control_enc, lib_platform],
   dependencies: drm_dep
)
