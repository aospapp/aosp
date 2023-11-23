# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ATEST_REL_DIR="tools/asuite/atest"

# This function returns devices recognised by adb.
_fetch_adb_devices() {
    while read dev; do echo $dev | awk '{print $1}'; done < <(adb devices | egrep -v "^List|^$"||true)
}

# This function returns all paths contain TEST_MAPPING.
_fetch_test_mapping_files() {
    [[ -z $ANDROID_BUILD_TOP ]] && return 0
    find -maxdepth 5 -type f -name TEST_MAPPING |sed 's/^.\///g'| xargs dirname 2>/dev/null
}

function _pip_install() {
    if ! which $1 >/dev/null; then
        install_cmd="pip3 install --user $1"
        echo "${FUNCNAME[1]} requires $1 but not found. Installing..."
        eval $install_cmd >/dev/null
    fi
}

# The main tab completion function.
_atest() {
    COMPREPLY=()
    local cmd=$(which $1)
    local cur="${COMP_WORDS[COMP_CWORD]}"
    local prev="${COMP_WORDS[COMP_CWORD-1]}"
    _get_comp_words_by_ref -n : cur prev || true

    if [[ "$cmd" == *prebuilts/asuite/atest/linux-x86/atest ]]; then
        # prebuilts/asuite/atest/linux-x86/atest is shell script wrapper around
        # atest-py3, which is what we should actually use.
        cmd=$ANDROID_BUILD_TOP/prebuilts/asuite/atest/linux-x86/atest-py3
    fi

    case "$cur" in
        -*)
            COMPREPLY=($(compgen -W "$(unzip -p $cmd atest/atest_flag_list_for_completion.txt)" -- $cur))
            ;;
        */*)
            ;;
        *)
            # Use grep instead of compgen -W because compgen -W is very slow. It takes
            # ~0.7 seconds for compgen to read the all_modules.txt file.
            # TODO(b/256228056) This fails if $cur has special characters in it
            COMPREPLY=($(ls | grep "^$cur"; grep "^$cur" $ANDROID_PRODUCT_OUT/all_modules.txt 2>/dev/null))
            ;;
    esac

    case "$prev" in
        --iterations|--retry-any-failure|--rerun-until-failure)
            COMPREPLY=(10) ;;
        --list-modules|-L)
            # TODO: genetate the list automately when the API is available.
            COMPREPLY=($(compgen -W "cts vts" -- $cur)) ;;
        --serial|-s)
            local adb_devices="$(_fetch_adb_devices)"
            if [ -n "$adb_devices" ]; then
                COMPREPLY=($(compgen -W "$(_fetch_adb_devices)" -- $cur))
            else
                # Don't complete files/dirs when there'is no devices.
                compopt -o nospace
                COMPREPLY=("")
            fi ;;
        --test-mapping|-p)
            local mapping_files="$(_fetch_test_mapping_files)"
            if [ -n "$mapping_files" ]; then
                COMPREPLY=($(compgen -W "$mapping_files" -- $cur))
            else
                # Don't complete files/dirs when TEST_MAPPING wasn't found.
                compopt -o nospace
                COMPREPLY=("")
            fi ;;
    esac
    __ltrim_colon_completions "$cur" "$prev" || true
    return 0
}

function _atest_main() {
    # Only use this in interactive mode.
    # Warning: below check must be "return", not "exit". "exit" won't break the
    # build in interactive shell(e.g VM), but will result in build breakage in
    # non-interactive shell(e.g docker container); therefore, using "return"
    # adapts both conditions.
    [[ ! $- =~ 'i' ]] && return 0

    local T="$(gettop)"

    # Complete file/dir name first by using option "nosort".
    # BASH version <= 4.3 doesn't have nosort option.
    # Note that nosort has no effect for zsh.
    local _atest_comp_options="-o default -o nosort"
    local _atest_executables=(atest atest-dev atest-py3)
    for exec in "${_atest_executables[*]}"; do
        complete -F _atest $_atest_comp_options $exec 2>/dev/null || \
        complete -F _atest -o default $exec
    done

    function atest-src() {
        echo "atest-src is deprecated, use m atest && atest-dev instead" >&2
        return 1
    }

    # Use prebuilt python3 for atest-dev
    function atest-dev() {
        atest_dev="$ANDROID_SOONG_HOST_OUT/bin/atest-dev"
        if [ ! -f $atest_dev ]; then
            echo "Cannot find atest-dev. Run 'm atest' to generate one."
            return 1
        fi
        PREBUILT_TOOLS_DIR="$ANDROID_BUILD_TOP/prebuilts/build-tools/path/linux-x86"
        PATH=$PREBUILT_TOOLS_DIR:$PATH $atest_dev "$@"
    }

    # pyinstrument profiler
    function _atest_profile_cli() {
        local T="$(gettop)"
        profile="$HOME/.atest/$(date +'%FT%H-%M-%S').pyisession"
        _pip_install pyinstrument
        if [ "$?" -eq 0 ]; then
            m atest && \
                python3 $T/tools/asuite/atest/profiler.py pyinstrument $profile $ANDROID_SOONG_HOST_OUT/bin/atest-dev "$@" && \
                python3 -m pyinstrument -t --show-all --load $profile && \
                echo "$(tput setaf 3)$profile$(tput sgr0) saved."
        fi
    }

    # cProfile profiler + snakeviz visualization
    function _atest_profile_web() {
        local T="$(gettop)"
        profile="$HOME/.atest/$(date +'%F_%H-%M-%S').pstats"
        m atest && \
            python3 $T/tools/asuite/atest/profiler.py cProfile $profile $ANDROID_SOONG_HOST_OUT/bin/atest-dev "$@" && \
            echo "$profile saved." || return 1

        _pip_install snakeviz
        if [ "$?" -eq 0 ]; then
            run_cmd="snakeviz -H $HOSTNAME $profile >/dev/null 2>&1"
            echo "$(tput bold)Use Ctrl-C to stop.$(tput sgr0)"
            eval $run_cmd
            echo
            echo "To permanently start a web server, please run:"
            echo $(tput setaf 3)"nohup $run_cmd &"$(tput sgr0)
            echo "and share $(tput setaf 3)http://$HOSTNAME:8080/snakeviz/$profile$(tput sgr0)."
        fi
    }
}

_atest_main
