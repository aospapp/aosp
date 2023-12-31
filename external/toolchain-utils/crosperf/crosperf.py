#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2011 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""The driver script for running performance benchmarks on ChromeOS."""


import argparse
import atexit
import os
import signal
import sys

# This import causes pylint to warn about "No name 'logger' in module
# 'cros_utils'". I do not understand why. The import works fine in python.
# pylint: disable=no-name-in-module
from cros_utils import logger
from experiment_factory import ExperimentFactory
from experiment_file import ExperimentFile
from experiment_runner import ExperimentRunner
from experiment_runner import MockExperimentRunner
from settings_factory import GlobalSettings
import test_flag


HAS_FAILURE = 1
ALL_FAILED = 2


def SetupParserOptions(parser):
    """Add all options to the parser."""
    parser.add_argument(
        "--dry_run",
        dest="dry_run",
        help=("Parse the experiment file and " "show what will be done"),
        action="store_true",
        default=False,
    )
    # Allow each of the global fields to be overridden by passing in
    # options. Add each global field as an option.
    option_settings = GlobalSettings("")
    for field_name in option_settings.fields:
        field = option_settings.fields[field_name]
        parser.add_argument(
            "--%s" % field.name,
            dest=field.name,
            help=field.description,
            action="store",
        )


def ConvertOptionsToSettings(options):
    """Convert options passed in into global settings."""
    option_settings = GlobalSettings("option_settings")
    for option_name in options.__dict__:
        if (
            options.__dict__[option_name] is not None
            and option_name in option_settings.fields
        ):
            option_settings.SetField(option_name, options.__dict__[option_name])
    return option_settings


def Cleanup(experiment):
    """Handler function which is registered to the atexit handler."""
    experiment.Cleanup()


def CallExitHandler(signum, _):
    """Signal handler that transforms a signal into a call to exit.

    This is useful because functionality registered by "atexit" will
    be called. It also means you can "catch" the signal by catching
    the SystemExit exception.
    """
    sys.exit(128 + signum)


def RunCrosperf(argv):
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--noschedv2",
        dest="noschedv2",
        default=False,
        action="store_true",
        help=("Do not use new scheduler. " "Use original scheduler instead."),
    )
    parser.add_argument(
        "-l",
        "--log_dir",
        dest="log_dir",
        default="",
        help="The log_dir, default is under <crosperf_logs>/logs",
    )

    SetupParserOptions(parser)
    options, args = parser.parse_known_args(argv)

    # Convert the relevant options that are passed in into a settings
    # object which will override settings in the experiment file.
    option_settings = ConvertOptionsToSettings(options)
    log_dir = os.path.abspath(os.path.expanduser(options.log_dir))
    logger.GetLogger(log_dir)

    if len(args) == 2:
        experiment_filename = args[1]
    else:
        parser.error("Invalid number arguments.")

    working_directory = os.getcwd()
    if options.dry_run:
        test_flag.SetTestMode(True)

    experiment_file = ExperimentFile(
        open(experiment_filename, encoding="utf-8"), option_settings
    )
    if not experiment_file.GetGlobalSettings().GetField("name"):
        experiment_name = os.path.basename(experiment_filename)
        experiment_file.GetGlobalSettings().SetField("name", experiment_name)
    experiment = ExperimentFactory().GetExperiment(
        experiment_file, working_directory, log_dir
    )

    json_report = experiment_file.GetGlobalSettings().GetField("json_report")

    signal.signal(signal.SIGTERM, CallExitHandler)
    atexit.register(Cleanup, experiment)

    if options.dry_run:
        runner = MockExperimentRunner(experiment, json_report)
    else:
        runner = ExperimentRunner(
            experiment, json_report, using_schedv2=(not options.noschedv2)
        )

    ret = runner.Run()
    if ret == HAS_FAILURE:
        raise RuntimeError("One or more benchmarks failed.")
    if ret == ALL_FAILED:
        raise RuntimeError("All benchmarks failed to run.")


def Main(argv):
    try:
        RunCrosperf(argv)
    except Exception:
        # Flush buffers before exiting to avoid out of order printing
        sys.stdout.flush()
        # Raise exception prints out traceback
        raise


if __name__ == "__main__":
    Main(sys.argv)
