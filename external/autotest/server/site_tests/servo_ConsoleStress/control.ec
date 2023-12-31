# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from autotest_lib.server import utils

AUTHOR = "mruthven"
NAME = "servo_ConsoleStress.ec"
PURPOSE = "Verify ec console."
TIME = "SHORT"
TEST_TYPE = "server"
DEPENDENCIES = "servo_state:WORKING"
PY_VERSION = 3

DOC = """Run the control a bunch. Make sure the output doesn't change

This can be used to verify the console the control uses. The more output the
control gets, the faster this test will detect errors.
"""
if 'args_dict' not in locals():
    args_dict = {}

args_dict.update(utils.args_to_dict(args))
servo_args = hosts.CrosHost.get_servo_arguments(args_dict)

def run(machine):
    host = hosts.create_host(machine, servo_args=servo_args)

    iterations = int(args_dict.get("iterations", 1))
    attempts = int(args_dict.get("attempts", 1000))
    cmd_type = args_dict.get("cmd_type", "ec")
    cmd = args_dict.get("cmd", "help")

    job.run_test("servo_ConsoleStress", host=host, cmdline_args=args,
                 full_args=args_dict, iterations=iterations,
                 attempts=attempts, cmd_type=cmd_type, cmd=cmd)

parallel_simple(run, machines)
