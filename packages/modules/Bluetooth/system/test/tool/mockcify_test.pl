#!/usr/bin/perl
##
## Copyright 2019 The Android Open Source Project
##
## Licensed under the Apache License, Version 2.0 (the "License");
## you may not use this file except in compliance with the License.
## You may obtain a copy of the License at
##
##      http://www.apache.org/licenses/LICENSE-2.0
##
## Unless required by applicable law or agreed to in writing, software
## distributed under the License is distributed on an "AS IS" BASIS,
## WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
## See the License for the specific language governing permissions and
## limitations under the License.

my $MOCKCIFY="./test/tool/mockcify.pl";

my @tests=(
    "osi/src/allocator.cc",
    "osi/src/list.cc",
    "osi/src/mutex.cc",
);

print;
foreach (@tests) {
    print(STDOUT "\33[2K\r$_\r");
    my $cmd = "$MOCKCIFY TEST < $_";
    my $rc = system("$cmd > /dev/null 2&>1");
    if ($rc != 0) {
        print(STDERR "\nFAILED \'$_\' cmd:\'$cmd\'\n");
        exit 1;
    }
}
print(STDERR "\33[2K\rPASSED\n");
