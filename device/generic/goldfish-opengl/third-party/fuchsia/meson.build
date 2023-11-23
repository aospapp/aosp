# Copyright 2023 Android Open Source Project
# SPDX-License-Identifier: MIT

inc_fuchsia_magma = include_directories('magma/include')

# TODO(b/274956288): Make magma include paths consistent.
# The encoder uses the SDK-based mapping of lib/magma/*, but
# dependent drivers (i.e. mesa) still expect the headers at magma/*.
inc_fuchsia_magma_nolib = include_directories('magma/include/lib')

fuchsia_magma_dep = declare_dependency(
   include_directories: [inc_fuchsia_magma],
)
