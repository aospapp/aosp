#!/usr/bin/env python
#
# Copyright (C) 2017 The Android Open Source Project
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

import argparse
import importlib
import os
import subprocess
import sys


class ExternalModules(object):
    """This class imports modules dynamically and keeps them as attributes.

    Assume the user runs this script in the source directory. The VTS modules
    are outside the search path and thus have to be imported dynamically.

    Attribtues:
        elf_parser: The elf_parser module.
        vtable_parser: The vtable_parser module.
    """
    @classmethod
    def ImportParsers(cls, import_dir):
        """Imports elf_parser and vtable_parser.

        Args:
            import_dir: The directory containing vts.utils.python.library.*.
        """
        sys.path.append(import_dir)
        cls.elf_parser = importlib.import_module(
                "vts.utils.python.library.elf_parser")
        cls.vtable_parser = importlib.import_module(
                "vts.utils.python.library.vtable_parser")


def GetBuildVariable(build_top_dir, var):
    """Gets value of a variable from build config.

    Args:
        build_top_dir: The path to root directory of Android source.
        var: The name of the variable.

    Returns:
        A string which is the value of the variable.
    """
    build_core = os.path.join(build_top_dir, "build", "core")
    env = dict(os.environ)
    env["CALLED_FROM_SETUP"] = "true"
    env["BUILD_SYSTEM"] = build_core
    cmd = ["make", "--no-print-directory",
           "-f", os.path.join(build_core, "config.mk"),
           "dumpvar-" + var]
    proc = subprocess.Popen(cmd, env=env, cwd=build_top_dir,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = proc.communicate()
    if stderr:
        sys.exit("Cannot get variable: cmd=%s\nstdout=%s\nstderr=%s" % (
                 cmd, stdout, stderr))
    return stdout.strip()


def FindBinary(file_name):
    """Finds an executable binary in environment variable PATH.

    Args:
        file_name: The file name to find.

    Returns:
        A string which is the path to the binary.
    """
    cmd = ["which", file_name]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = proc.communicate()
    if proc.returncode:
        sys.exit("Cannot find file: cmd=%s\nstdout=%s\nstderr=%s" % (
                 cmd, stdout, stderr))
    return stdout


def DumpSymbols(lib_path, dump_path):
    """Dump symbols from a library to a dump file.

    The dump file is a sorted list of symbols. Each line contains one symbol.

    Args:
        lib_path: The path to the library.
        dump_path: The path to the dump file.

    Returns:
        A string which is the description about the result.

    Raises:
        elf_parser.ElfError if fails to load the library.
        IOError if fails to write to the dump.
    """
    elf_parser = ExternalModules.elf_parser
    parser = None
    try:
        parser = elf_parser.ElfParser(lib_path)
        symbols = parser.ListGlobalDynamicSymbols()
    finally:
        if parser:
            parser.Close()
    if not symbols:
        return "No symbols"
    symbols.sort()
    with open(dump_path, "w") as dump_file:
        dump_file.write("\n".join(symbols) + "\n")
    return "Output: " + dump_path


def DumpVtables(lib_path, dump_path, dumper_dir):
    """Dump vtables from a library to a dump file.

    The dump file is the raw output of vndk-vtable-dumper.

    Args:
        lib_path: The path to the library.
        dump_path: The path to the text file.
        dumper_dir: The path to the directory containing the dumper executable
                    and library.

    Returns:
        A string which is the description about the result.

    Raises:
        vtable_parser.VtableError if fails to load the library.
        IOError if fails to write to the dump.
    """
    vtable_parser = ExternalModules.vtable_parser
    parser = vtable_parser.VtableParser(dumper_dir)
    vtables = parser.CallVtableDumper(lib_path)
    if not vtables:
        return "No vtables"
    with open(dump_path, "w+") as dump_file:
        dump_file.write(vtables)
    return "Output: " + dump_path


def GetSystemLibDirByArch(product_dir, arch_name):
    """Returns the directory containing libraries for specific architecture.

    Args:
        product_dir: The path to the product output directory in Android source.
        arch_name: The name of the CPU architecture.

    Returns:
        The path to the directory containing the libraries.
    """
    if arch_name in ("arm", "x86", "mips"):
        src_dir = os.path.join(product_dir, "system", "lib")
    elif arch_name in ("arm64", "x86_64", "mips64"):
        src_dir = os.path.join(product_dir, "system", "lib64")
    else:
        sys.exit("Unknown target arch " + str(target_arch))
    return src_dir


def DumpAbi(output_dir, input_files, product_dir, archs, dumper_dir):
    """Generates dump from libraries.

    Args:
        output_dir: The output directory of dump files.
        input_files: A list of strings. Each element can be .so file or a text
                     file which contains list of libraries.
        product_dir: The path to the product output directory in Android source.
        archs: A list of strings which are the CPU architectures of the
               libraries.
        dumper_dir: The path to the directory containing the vtable dumper
                    executable and library.
    """
    # Get names of the libraries to dump
    lib_names = []
    for input_file in input_files:
        if input_file.endswith(".so"):
            lib_names.append(input_file)
        else:
            with open(input_file, "r") as lib_list:
                lib_names.extend(line.strip() for line in lib_list
                                 if line.strip())
    # Create the dumps
    for arch in archs:
        lib_dir = GetSystemLibDirByArch(product_dir, arch)
        dump_dir = os.path.join(output_dir, arch)
        if not os.path.exists(dump_dir):
            os.makedirs(dump_dir)
        for lib_name in lib_names:
            lib_path = os.path.join(lib_dir, lib_name)
            symbol_dump_path = os.path.join(dump_dir, lib_name + "_symbol.dump")
            vtable_dump_path = os.path.join(dump_dir, lib_name + "_vtable.dump")
            print(lib_path)
            print(DumpSymbols(lib_path, symbol_dump_path))
            print(DumpVtables(lib_path, vtable_dump_path, dumper_dir))
            print("")


def main():
    # Parse arguments
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument("file", nargs="*",
                            help="the library to dump. Can be .so file or a"
                                 "text file containing list of libraries.")
    arg_parser.add_argument("--dumper-dir", "-d", action="store",
                            help="the path to the directory containing "
                                 "bin/vndk-vtable-dumper.")
    arg_parser.add_argument("--import-path", "-i", action="store",
                            help="the directory for VTS python modules. "
                                 "Default value is $ANDROID_BUILD_TOP/test")
    arg_parser.add_argument("--output", "-o", action="store", required=True,
                            help="output directory for ABI reference dump.")
    args = arg_parser.parse_args()

    # Get product directory
    product_dir = os.getenv("ANDROID_PRODUCT_OUT")
    if not product_dir:
        sys.exit("env var ANDROID_PRODUCT_OUT is not set")
    print("ANDROID_PRODUCT_OUT=" + product_dir)

    # Get target architectures
    build_top_dir = os.getenv("ANDROID_BUILD_TOP")
    if not build_top_dir:
        sys.exit("env var ANDROID_BUILD_TOP is not set")
    target_arch = GetBuildVariable(build_top_dir, "TARGET_ARCH")
    target_2nd_arch = GetBuildVariable(build_top_dir, "TARGET_2ND_ARCH")
    print("TARGET_ARCH=" + target_arch)
    print("TARGET_2ND_ARCH=" + target_2nd_arch)
    archs = [target_arch]
    if target_2nd_arch:
        archs.append(target_2nd_arch)

    # Import elf_parser and vtable_parser
    ExternalModules.ImportParsers(args.import_path if args.import_path else
                                  os.path.join(build_top_dir, "test"))

    # Find vtable dumper
    if args.dumper_dir:
        dumper_dir = args.dumper_dir
    else:
        dumper_path = FindBinary(vtable_parser.VtableParser.VNDK_VTABLE_DUMPER)
        dumper_dir = os.path.dirname(os.path.dirname(dumper_path))
    print("DUMPER_DIR=" + dumper_dir)

    DumpAbi(args.output, args.file, product_dir, archs, dumper_dir)


if __name__ == "__main__":
    main()
