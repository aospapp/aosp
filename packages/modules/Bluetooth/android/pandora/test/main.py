import site

site.main()

import argparse
import logging
import os
import sys

from argparse import Namespace
from mobly import suite_runner
from typing import List, Tuple

_BUMBLE_BTSNOOP_FMT = 'bumble_btsnoop_{pid}_{instance}.log'

# Import test modules.
import asha_test
import classic_ssp_test
import example
import gatt_test
import le_advertising_test
import smp_test

_TEST_CLASSES_LIST = [
    example.ExampleTest,
    asha_test.ASHATest,
    gatt_test.GattTest,
    le_advertising_test.LeAdvertisingTest,
    smp_test.SmpTest,
    classic_ssp_test.ClassicSspTest,
]


def _parse_cli_args() -> Tuple[Namespace, List[str]]:
    parser = argparse.ArgumentParser(description='Avatar test runner.')
    parser.add_argument('-o', '--log_path', type=str, metavar='<PATH>', help='Path to the test configuration file.')
    return parser.parse_known_args()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)

    # This is a hack for `tradefed` because of `b/166468397`.
    if '--' in sys.argv:
        index = sys.argv.index('--')
        sys.argv = sys.argv[:1] + sys.argv[index + 1 :]

    # Enable bumble snoop logger.
    ns, argv = _parse_cli_args()
    if ns.log_path:
        os.environ.setdefault('BUMBLE_SNOOPER', f'btsnoop:file:{ns.log_path}/{_BUMBLE_BTSNOOP_FMT}')

    # Run the test suite.
    suite_runner.run_suite(_TEST_CLASSES_LIST, argv)  # type: ignore
