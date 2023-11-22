#!/usr/bin/env bash

if [ "x$TARGET_PRODUCT" == "x" ]; then
	echo "WARNING: Its recommended to launch from android build"
	echo "environment to take advantage of product/device-specific"
	echo "functionality."
else
	lisadir="$(gettop)/$(get_build_var BOARD_LISA_TARGET_SCRIPTS)"

	if [ -d $lisadir/targetdev ]; then
		export PYTHONPATH=$lisadir:$PYTHONPATH
		echo "Welcome to LISA $TARGET_PRODUCT environment"
		echo "Target-specific scripts are located in $lisadir"
	else
		echo "LISA scripts don't exist for $TARGET_PRODUCT, skipping"
	fi
fi

if [ -z  "$CATAPULT_HOME" ]; then
        export CATAPULT_HOME=$LISA_HOME/../chromium-trace/catapult/
        echo "Systrace will run from: $(readlink -f $CATAPULT_HOME)"
fi

monsoon_path="$LISA_HOME/../../cts/tools/utils/"
export PATH="$monsoon_path:$PATH"
echo "Monsoon will run from: $(readlink -f $monsoon_path/monsoon.py)"

export PYTHONPATH=$LISA_HOME/../devlib:$PYTHONPATH
export PYTHONPATH=$LISA_HOME/../trappy:$PYTHONPATH
export PYTHONPATH=$LISA_HOME/../bart:$PYTHONPATH
