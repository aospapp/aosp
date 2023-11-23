#!/bin/bash

# Quit if any command produces an error.
set -e

# Parse variables
MAJOR_VERSION=$1
: ${MAJOR_VERSION:?
    "You must specify the major version of the API to be archived."
    "Usage ./archive_chre_api.sh <major_version> <minor_version>"}

MINOR_VERSION=$2
: ${MINOR_VERSION:?
    "You must specify the minor version of the API to be archived."
    "Usage ./archive_chre_api.sh <major_version> <minor_version>"}

DIRECTORY=v${MAJOR_VERSION}_${MINOR_VERSION}

mkdir legacy/$DIRECTORY
cp -r include/chre_api/* legacy/$DIRECTORY
