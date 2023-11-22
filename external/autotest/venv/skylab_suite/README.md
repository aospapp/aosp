# Suite running in Skylab

[TOC]

This is the package of Skylab suite. See the [design
doc](http://goto.google.com/chromeos-skylab-suites).

The package of Autotest suite is in
https://chromium.googlesource.com/chromiumos/third_party/autotest/+/master/server/cros/dynamic_suite/

## Overview

Skylab suite package provides two commands:

- `bin/run_suite_skylab`
- `bin/abort_suite_skylab`

`run_suite_skylab` is a script to kick off a suite running in Skylab.
`abort_suite_skylab`is a script to abort a suite running in Skylab.

## Development

To run unittests, in the autotest repository root, run:

    $ bin/test_skylab_suite

To test locally, some example commands are:

    $ bin/run_suite_skylab --pool=suites --board=nyan_blaze --suite_name=sanity
      --build=nyan_blaze-release/R69-10763.0.0 --priority 215 --do_nothing

    $ bin/run_suite_skylab --pool=cq --board=nyan_blaze --suite_name=dummy
      --build=nyan_blaze-release/R69-10763.0.0 --priority 80 --timeout_mins 30
      --test_retry --max_retries 5

    $ bin/run_suite_skylab --build nyan_blaze-release/R69-10763.0.0 --board nyan_blaze
      --suite_name provision --pool suites --priority 50 --timeout_min 30 --test_retry
      --max_retries 5 --suite_args "{u'num_required': 1} --create_and_return

To trigger a suite to staging lab, in the chromite repository root, run:

   $ ./third_party/swarming.client/swarming.py run --auth-service-account-json [secret_file]
     --swarming https://chromium-swarm-dev.appspot.com --raw-cmd
     --dimension pool ChromeOSSkylab-suite --dimension id [cros-skylab-staging-2-*]
     '--tags=luci_project:chromiumos' --tags='build:nyan_blaze-release/R70-11012.0.0' --
     /usr/local/autotest/bin/run_suite_skylab --build nyan_blaze-release/R70-11012.0.0
     [omit other parameters...] --create_and_return --use_fallback --pre_check
