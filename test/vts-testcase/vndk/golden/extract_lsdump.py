#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
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
import gzip
import json
import os


class LsdumpError(Exception):
    """The exception raised by ParseLsdumpFile."""
    pass


class AttrDict(dict):
    """A dictionary with attribute accessors."""

    def __getattr__(self, key):
        """Returns self[key]."""
        try:
            return self[key]
        except KeyError:
            raise AttributeError('Failed to get attribute: %s' % key)

    def __setattr__(self, key, value):
        """Assigns value to self[key]."""
        self[key] = value


def _OpenFileOrGzipped(file_name):
    """Opens a file that is either in gzip or uncompressed format.

    If file_name ends with '.gz' then return gzip.open(file_name, 'rb'),
    else return open(file_name, 'rb').

    Args:
        file_name: The file name to open.

    Returns:
        A file object.

    Raises:
        IOError if fails to open.
    """
    if file_name.endswith('.gz'):
        return gzip.open(file_name, 'rb')
    return open(file_name, 'rb')


def _ConsumeOffset(tok, beg=0):
    """Consumes a <offset-number> in a thunk symbol."""
    pos = tok.find('_', beg) + 1
    return tok[:pos], tok[pos:]


def _ConsumeCallOffset(tok):
    """Consumes a <call-offset> in a thunk symbol."""
    if tok[:1] == 'h':
        lhs, rhs = _ConsumeOffset(tok, 1)
    elif tok[:1] == 'v':
        lhs, rhs = _ConsumeOffset(tok, 1)
        lhs2, rhs = _ConsumeOffset(rhs)
        if lhs and lhs2:
            lhs = lhs + lhs2
        else:
            lhs, rhs = '', tok
    else:
        lhs, rhs = '', tok
    return lhs, rhs


def _FindThunkTarget(name):
    """Finds thunk symbol's target function.

    <thunk-symbol> ::= _ZT <call-offset> <base-encoding>
                     | _ZTc <call-offset> <call-offset> <base-encoding>
    <call-offset>  ::= h <nv-offset>
                     | v <v-offset>
    <nv-offset>    ::= <offset-number> _
    <v-offset>     ::= <offset-number> _ <offset-number> _

    Args:
        name: A string, the symbol name to resolve.

    Returns:
        A string, symbol name of the nominal target function.
        The input symbol name if it is not a thunk symbol.
    """
    if name.startswith('_ZTh') or name.startswith('_ZTv'):
        lhs, rhs = _ConsumeCallOffset(name[len('_ZT'):])
        if lhs:
            return '_Z' + rhs
    if name.startswith('_ZTc'):
        lhs, rhs = _ConsumeCallOffset(name[len('_ZTc'):])
        lhs2, rhs = _ConsumeCallOffset(rhs)
        if lhs and lhs2:
            return '_Z' + rhs
    return name


def _FilterElfFunctions(lsdump):
    """Finds exported functions and thunks in lsdump.

    Args:
        lsdump: An AttrDict object containing the lsdump.

    Yields:
        The AttrDict objects in lsdump.elf_functions.
    """
    functions = {function.linker_set_key for function in lsdump.functions}

    for elf_function in lsdump.elf_functions:
        if elf_function.name in functions:
            yield elf_function
        elif _FindThunkTarget(elf_function.name) in functions:
            yield elf_function


def _FilterElfObjects(lsdump):
    """Finds exported variables, type info, and vtables in lsdump.

    Args:
        lsdump: An AttrDict object containing the lsdump.

    Yields:
        The AttrDict objects in lsdump.elf_objects.
    """
    global_vars = {global_var.linker_set_key
                   for global_var in lsdump.global_vars}
    record_names = {record_type.unique_id[len('_ZTS'):]
                    for record_type in lsdump.record_types}

    for elf_object in lsdump.elf_objects:
        name = elf_object.name
        if name in global_vars:
            yield elf_object
        elif (name[:len('_ZTS')] in {'_ZTV', '_ZTT', '_ZTI', '_ZTS'} and
                name[len('_ZTS'):] in record_names):
            yield elf_object


def _ParseSymbolsFromLsdump(lsdump, output_dump):
    """Parses symbols from an lsdump.

    Args:
        lsdump: An AttrDict object containing the lsdump.
        output_dump: An AttrDict object containing the output.
    """
    output_dump.elf_functions = list(_FilterElfFunctions(lsdump))
    output_dump.elf_objects = list(_FilterElfObjects(lsdump))


def _ParseVtablesFromLsdump(lsdump, output_dump):
    """Parses vtables from an lsdump.

    Args:
        lsdump: An AttrDict object containing the lsdump.
        output_dump: An AttrDict object containing the output.
    """
    vtable_symbols = {elf_object.name for elf_object in lsdump.elf_objects
                      if elf_object.name.startswith('_ZTV')}

    output_dump.record_types = []
    for lsdump_record_type in lsdump.record_types:
        type_symbol = lsdump_record_type.unique_id
        vtable_symbol = '_ZTV' + type_symbol[len('_ZTS'):]
        if vtable_symbol not in vtable_symbols:
            continue
        record_type = AttrDict()
        record_type.unique_id = lsdump_record_type.unique_id
        record_type.vtable_components = lsdump_record_type.vtable_components
        output_dump.record_types.append(record_type)


def ParseLsdumpFile(input_path, output_path):
    """Converts an lsdump file to a dump file for the ABI test.

    Args:
        input_path: The path to the (gzipped) lsdump file.
        output_path: The path to the output dump file.

    Raises:
        LsdumpError if fails to create the dump file.
    """
    try:
        with _OpenFileOrGzipped(input_path) as lsdump_file:
            lsdump = json.load(lsdump_file, object_hook=AttrDict)
    except (IOError, ValueError) as e:
        raise LsdumpError(e)

    try:
        output_dump = AttrDict()
        _ParseVtablesFromLsdump(lsdump, output_dump)
        _ParseSymbolsFromLsdump(lsdump, output_dump)
    except AttributeError as e:
        raise LsdumpError(e)

    abs_output_path = os.path.abspath(output_path)
    abs_output_dir = os.path.dirname(abs_output_path)

    try:
        if abs_output_dir and not os.path.exists(abs_output_dir):
            os.makedirs(abs_output_dir)
        with open(output_path, 'wb') as output_file:
            json.dump(output_dump, output_file,
                      indent=1, separators=(',', ':'))
    except (IOError, OSError) as e:
        raise LsdumpError(e)


def main():
    arg_parser = argparse.ArgumentParser(
        description='This script converts an lsdump file to a dump file for '
                    'the ABI test in VTS.')
    arg_parser.add_argument('input_path',
                            help='input lsdump file path.')
    arg_parser.add_argument('output_path',
                            help='output dump file path.')
    args = arg_parser.parse_args()

    try:
        ParseLsdumpFile(args.input_path, args.output_path)
    except LsdumpError as e:
        print(e)
        exit(1)


if __name__ == '__main__':
    main()
