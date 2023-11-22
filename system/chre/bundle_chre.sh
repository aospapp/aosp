!/bin/bash
#
# Generates a bundle of this project to share with partners. It produces a file
# with the name chre-$HEAD_HASH.bundle where $HEAD_HASH is the current ToT
# commit hash.
#
# Usage:
#     ./bundle_chre.sh

BRANCH=goog/master
HEAD_HASH=`git describe --always --long $BRANCH`
git bundle create chre-$HEAD_HASH.bundle $BRANCH
