#!/usr/bin/env python3.4
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
from __future__ import print_function

import logging
import os
import re
import sys

from acts.libs.proc import job

GLOBAL_KEYWORDS_FILEPATH = 'vendor/google_testing/comms/framework/etc/' \
                           'commit_keywords'
LOCAL_KEYWORDS_FILEPATH = '~/.repo_acts_commit_keywords'

FIND_COMMIT_KEYWORDS = 'git log @{u}..| grep -i %s'
GET_EMAIL_ADDRESS = 'git log --format=%ce -1'


def main(argv):
    file_path = os.path.join(
        os.path.dirname(__file__), "../../../../%s" % GLOBAL_KEYWORDS_FILEPATH)

    if not os.path.isfile(file_path):
        file_path = os.path.expanduser(LOCAL_KEYWORDS_FILEPATH)
        if not os.path.exists(file_path) or not os.path.isfile(file_path):
            result = job.run(GET_EMAIL_ADDRESS)
            if result.stdout.endswith('@google.com'):
                logging.error(
                    'You do not have the necessary file %s. Please run '
                    'tools/ignore_commit_keywords.sh, or link it with the '
                    'following command:\n ln -sf <internal_master_root>/%s %s'
                    % (LOCAL_KEYWORDS_FILEPATH, GLOBAL_KEYWORDS_FILEPATH,
                       LOCAL_KEYWORDS_FILEPATH))
                exit(1)
            return

    with open(file_path) as file:
        # Places every line within quotes with -e before them.
        # i.e. '-e "line1" -e "line2" -e "line3"'
        grep_args = re.sub('^(.+?)$\n?', ' -e "\\1"',
                           file.read(), 0, re.MULTILINE)

    result = job.run(FIND_COMMIT_KEYWORDS % grep_args, ignore_status=True)

    if result.stdout:
        logging.error('Your commit message contains at least one keyword.')
        logging.error('Keyword(s) found in the following line(s):')
        logging.error(result.stdout)
        logging.error('Please fix/remove these before committing.')
        exit(1)


if __name__ == '__main__':
    main(sys.argv[1:])
