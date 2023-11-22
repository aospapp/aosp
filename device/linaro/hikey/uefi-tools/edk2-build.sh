#!/bin/bash

#
# edk2-build.sh: evolution of uefi-build.sh for edk2-platforms
#

unset MAKEFLAGS  # BaseTools not safe to build parallel, prevent env overrides

TOOLS_DIR="`dirname $0`"
TOOLS_DIR="`readlink -f \"$TOOLS_DIR\"`"
export TOOLS_DIR
. "$TOOLS_DIR"/common-functions
PLATFORM_CONFIG="-c $TOOLS_DIR/edk2-platforms.config"
ARCH=
VERBOSE=0                  # Override with -v
ATF_DIR=
TOS_DIR=
TOOLCHAIN="gcc"            # Override with -T
WORKSPACE=
EDK2_DIR=
PLATFORMS_DIR=
NON_OSI_DIR=
IMPORT_OPENSSL=TRUE
OPENSSL_CONFIGURED=FALSE

# Number of threads to use for build
export NUM_THREADS=$((`getconf _NPROCESSORS_ONLN` + `getconf _NPROCESSORS_ONLN`))

function do_build
{
	PLATFORM_ARCH=`echo $board | cut -s -d: -f2`
	if [ -n "$PLATFORM_ARCH" ]; then
		board=`echo $board | cut -d: -f1`
	else
		PLATFORM_ARCH="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o arch`"
	fi
	PLATFORM_NAME="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o longname` ($PLATFORM_ARCH)"
	if [ -z "$PLATFORM_ARCH" ]; then
		echo "Unknown target architecture - aborting!" >&2
		return 1
	fi
	PLATFORM_PREBUILD_CMDS="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o prebuild_cmds`"
	PLATFORM_BUILDFLAGS="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o buildflags`"
	PLATFORM_BUILDFLAGS="$PLATFORM_BUILDFLAGS ${EXTRA_OPTIONS[@]}"
	PLATFORM_BUILDCMD="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o buildcmd`"
	PLATFORM_DSC="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o dsc`"
	PLATFORM_PACKAGES_PATH=""
	COMPONENT_INF="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o inf`"

	TEMP_PACKAGES_PATH="$GLOBAL_PACKAGES_PATH:`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o packages_path`"
	IFS=:
	for path in "$TEMP_PACKAGES_PATH"; do
		case "$path" in
			/*)
				PLATFORM_PACKAGES_PATH="$PLATFORM_PACKAGES_PATH:$path"
			;;
			*)
				PLATFORM_PACKAGES_PATH="$PLATFORM_PACKAGES_PATH:$PWD/$path"
			;;
	        esac
	done
	unset IFS

	if [ $VERBOSE -eq 1 ]; then
		echo "Setting build parallellism to $NUM_THREADS processes"
		echo "PLATFORM_NAME=$PLATFORM_NAME"
		echo "PLATFORM_PREBUILD_CMDS=$PLATFORM_PREBUILD_CMDS"
		echo "PLATFORM_BUILDFLAGS=$PLATFORM_BUILDFLAGS"
		echo "PLATFORM_BUILDCMD=$PLATFORM_BUILDCMD"
		echo "PLATFORM_DSC=$PLATFORM_DSC"
		echo "PLATFORM_ARCH=$PLATFORM_ARCH"
		echo "PLATFORM_PACKAGES_PATH=$PLATFORM_PACKAGES_PATH"
	fi

	set_cross_compile
	CROSS_COMPILE="$TEMP_CROSS_COMPILE"

	echo "Building $PLATFORM_NAME - $PLATFORM_ARCH"
	echo "CROSS_COMPILE=\"$TEMP_CROSS_COMPILE\""
	echo "$board"_BUILDFLAGS="'$PLATFORM_BUILDFLAGS'"

	if [ "$TARGETS" == "" ]; then
		TARGETS=( RELEASE )
	fi

	case $TOOLCHAIN in
		"gcc")
			PLATFORM_TOOLCHAIN=`get_gcc_version "$CROSS_COMPILE"gcc`
			;;
		"clang")
			PLATFORM_TOOLCHAIN=`get_clang_version clang`
			;;
		*)
			# Use command-line specified profile directly
			PLATFORM_TOOLCHAIN=$TOOLCHAIN
			;;
	esac
	echo "PLATFORM_TOOLCHAIN is ${PLATFORM_TOOLCHAIN}"

	export ${PLATFORM_TOOLCHAIN}_${PLATFORM_ARCH}_PREFIX=$CROSS_COMPILE
	echo "Toolchain prefix: ${PLATFORM_TOOLCHAIN}_${PLATFORM_ARCH}_PREFIX=$CROSS_COMPILE"

	export PACKAGES_PATH="$PLATFORM_PACKAGES_PATH"
	for target in "${TARGETS[@]}" ; do
		if [ X"$PLATFORM_PREBUILD_CMDS" != X"" ]; then
			echo "Run pre-build commands:"
			if [ $VERBOSE -eq 1 ]; then
				echo "  ${PLATFORM_PREBUILD_CMDS}"
			fi
			eval ${PLATFORM_PREBUILD_CMDS}
		fi

		if [ -n "$COMPONENT_INF" ]; then
			# Build a standalone component
			if [ $VERBOSE -eq 1 ]; then
				echo "build -n $NUM_THREADS -a \"$PLATFORM_ARCH\" -t ${PLATFORM_TOOLCHAIN} -p \"$PLATFORM_DSC\"" \
					"-m \"$COMPONENT_INF\" -b "$target" ${PLATFORM_BUILDFLAGS}"
			fi
			build -n $NUM_THREADS -a "$PLATFORM_ARCH" -t ${PLATFORM_TOOLCHAIN} -p "$PLATFORM_DSC" \
				-m "$COMPONENT_INF" -b "$target" ${PLATFORM_BUILDFLAGS}
		else
			# Build a platform
			if [ $VERBOSE -eq 1 ]; then
				echo "build -n $NUM_THREADS -a \"$PLATFORM_ARCH\" -t ${PLATFORM_TOOLCHAIN} -p \"$PLATFORM_DSC\"" \
					"-b "$target" ${PLATFORM_BUILDFLAGS}"
			fi
			build -n $NUM_THREADS -a "$PLATFORM_ARCH" -t ${PLATFORM_TOOLCHAIN} -p "$PLATFORM_DSC" \
				-b "$target" ${PLATFORM_BUILDFLAGS}
		fi

		RESULT=$?
		if [ $RESULT -eq 0 ]; then
			if [ X"$TOS_DIR" != X"" ]; then
				pushd $TOS_DIR >/dev/null
				if [ $VERBOSE -eq 1 ]; then
					echo "$TOOLS_DIR/tos-build.sh -e "$EDK2_DIR" -t "$target"_${PLATFORM_TOOLCHAIN} $board"
				fi
				$TOOLS_DIR/tos-build.sh -e "$EDK2_DIR" -t "$target"_${PLATFORM_TOOLCHAIN} $board
				RESULT=$?
				popd >/dev/null
			fi
		fi
		if [ $RESULT -eq 0 ]; then
			if [ X"$ATF_DIR" != X"" ]; then
				pushd $ATF_DIR >/dev/null
				if [ $VERBOSE -eq 1 ]; then
					echo "$TOOLS_DIR/atf-build.sh -e "$EDK2_DIR" -t "$target"_${PLATFORM_TOOLCHAIN} $board"
				fi
				$TOOLS_DIR/atf-build.sh -e "$EDK2_DIR" -t "$target"_${PLATFORM_TOOLCHAIN} $board
				RESULT=$?
				popd >/dev/null
			fi
		fi
		result_log $RESULT "$PLATFORM_NAME $target"
	done
	unset PACKAGES_PATH
}


function configure_paths
{
	WORKSPACE="$PWD"

	# Check to see if we are in a UEFI repository
	# refuse to continue if we aren't
	if [ ! -d "$EDK2_DIR"/BaseTools ]
	then
		if [ -d "$PWD"/edk2/BaseTools ]; then
			EDK2_DIR="$PWD"/edk2
		else
			echo "ERROR: can't locate the edk2 directory" >&2
			echo "       please specify -e/--edk2-dir" >&2
			exit 1
		fi
	fi

	GLOBAL_PACKAGES_PATH="$EDK2_DIR"

	# locate edk2-platforms
	if [ -z "$PLATFORMS_DIR" -a -d "$PWD"/edk2-platforms ]; then
		PLATFORMS_DIR="$PWD"/edk2-platforms
	fi
	if [ -n "$PLATFORMS_DIR" ]; then
		GLOBAL_PACKAGES_PATH="$GLOBAL_PACKAGES_PATH:$PLATFORMS_DIR"
	fi

	# locate edk2-non-osi
	if [ -z "$NON_OSI_DIR" -a -d "$PWD"/edk2-non-osi ]; then
		NON_OSI_DIR="$PWD"/edk2-non-osi
	fi
	if [ -n "$NON_OSI_DIR" ]; then
		GLOBAL_PACKAGES_PATH="$GLOBAL_PACKAGES_PATH:$NON_OSI_DIR"
	fi

	# locate arm-trusted-firmware
	if [ -z "$ATF_DIR" -a -d "$PWD"/arm-trusted-firmware ]; then
		ATF_DIR="$PWD"/arm-trusted-firmware
	fi

	export WORKSPACE
}


function prepare_build
{
	get_build_arch
	export ARCH=$BUILD_ARCH

	export ARCH
	cd $EDK2_DIR
	PACKAGES_PATH=$GLOBAL_PACKAGES_PATH . edksetup.sh --reconfig
	if [ $? -ne 0 ]; then
		echo "Sourcing edksetup.sh failed!" >&2
		exit 1
	fi
	if [ $VERBOSE -eq 1 ]; then
		echo "Building BaseTools"
	fi
	make -C BaseTools
	RET=$?
	cd -
	if [ $RET -ne 0 ]; then
		echo " !!! BaseTools failed to build !!! " >&2
		exit 1
	fi

	if [ "$IMPORT_OPENSSL" = "TRUE" ]; then
		cd $EDK2_DIR
		import_openssl
		if [ $? -ne 0 ]; then
			echo "Importing OpenSSL failed - aborting!" >&2
			echo "  specify --no-openssl to attempt build anyway." >&2
			exit 1
		fi
		cd $WORKSPACE
	fi
}


function usage
{
	echo "usage:"
	echo -n "uefi-build.sh [-b DEBUG | RELEASE] [ all "
	for board in "${boards[@]}" ; do
	    echo -n "| $board "
	done
	echo "]"
	printf "%8s\tbuild %s\n" "all" "all supported platforms"
	for board in "${boards[@]}" ; do
		PLATFORM_NAME="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG -p $board get -o longname`"
		printf "%8s\tbuild %s\n" "$board" "${PLATFORM_NAME}"
	done
}

#
# Since we do a command line validation on whether specified platforms exist or
# not, do a first pass of command line to see if there is an explicit config
# file there to read valid platforms from.
#
commandline=( "$@" )
i=0
for arg;
do
	if [ $arg == "-c" ]; then
		FILE_ARG=${commandline[i + 1]}
		if [ ! -f "$FILE_ARG" ]; then
			echo "ERROR: configuration file '$FILE_ARG' not found" >&2
			exit 1
		fi
		case "$FILE_ARG" in
			/*)
				PLATFORM_CONFIG="-c $FILE_ARG"
			;;
			*)
				PLATFORM_CONFIG="-c `readlink -f \"$FILE_ARG\"`"
			;;
		esac
		echo "Platform config file: '$FILE_ARG'"
	fi
	i=$(($i + 1))
done

export PLATFORM_CONFIG

builds=()
boards=()
boardlist="`$TOOLS_DIR/parse-platforms.py $PLATFORM_CONFIG shortlist`"
for board in $boardlist; do
    boards=(${boards[@]} $board)
done

NUM_TARGETS=0

while [ "$1" != "" ]; do
	case $1 in
		-1)     # Disable build parallellism
			NUM_THREADS=1
			;;
		-a | --arm-tf-dir)
			shift
			ATF_DIR="`readlink -f $1`"
			;;
		-c)     # Already parsed above - skip this + option
			shift
			;;
		-b | --build-target)
			shift
			echo "Adding Build target: $1"
			TARGETS=(${TARGETS[@]} $1)
			;;
		-D)     # Pass through as -D option to 'build'
			shift
			echo "Adding Macro: -D $1"
			EXTRA_OPTIONS=(${EXTRA_OPTIONS[@]} "-D" $1)
			;;
		-e | --edk2-dir)
			shift
			export EDK2_DIR="`readlink -f $1`"
			;;
		-h | --help)
			usage
			exit
			;;
		--no-openssl)
			IMPORT_OPENSSL=FALSE
			;;
		-n | --non-osi-dir)
			shift
			NON_OSI_DIR="`readlink -f $1`"
			;;
		-p | --platforms-dir)
			shift
			PLATFORMS_DIR="`readlink -f $1`"
			;;
		-s | --tos-dir)
			shift
			export TOS_DIR="`readlink -f $1`"
			;;
		-T)     # Set specific toolchain tag, or clang/gcc for autoselection
			shift
			echo "Setting toolchain tag to '$1'"
			TOOLCHAIN="$1"
			;;
		-v)
			VERBOSE=1
			;;
		all)    # Add all targets in configuration file to list
			builds=(${boards[@]})
			NUM_TARGETS=$(($NUM_TARGETS + 1))
			;;
		*)      # Try to match target in configuration file, add to list
			MATCH=0
			for board in "${boards[@]}" ; do
				if [ "`echo $1 | cut -d: -f1`" == $board ]; then
					MATCH=1
					builds=(${builds[@]} "$1")
					break
				fi
			done

			if [ $MATCH -eq 0 ]; then
				echo "unknown arg $1"
				usage
				exit 1
			fi
			NUM_TARGETS=$(($NUM_TARGETS + 1))
			;;
	esac
	shift
done

if [ $NUM_TARGETS -le  0 ]; then
	echo "No targets specified - exiting!" >&2
	exit 0
fi

export VERBOSE

configure_paths

prepare_build

if [[ "${EXTRA_OPTIONS[@]}" != *"FIRMWARE_VER"* ]]; then
	if test -d .git && head=`git rev-parse --verify --short HEAD 2>/dev/null`; then
		FIRMWARE_VER=`git rev-parse --short HEAD`
		if ! git diff-index --quiet HEAD --; then
			FIRMWARE_VER="${FIRMWARE_VER}-dirty"
		fi
		EXTRA_OPTIONS=( ${EXTRA_OPTIONS[@]} "-D" FIRMWARE_VER=$FIRMWARE_VER )
		if [ $VERBOSE -eq 1 ]; then
			echo "FIRMWARE_VER=$FIRMWARE_VER"
			echo "EXTRA_OPTIONS=$EXTRA_OPTIONS"
		fi
	fi
fi

for board in "${builds[@]}" ; do
	do_build
done

result_print
