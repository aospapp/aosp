#!/bin/bash

function is_policy_file() {
  [[ "${1##*.}" == "policy" ]] && return 0
  return 1
}

function inline() {
  #
  # assumptions are:
  #  1. policy files may include "/usr/share/policy/crosvm/common_device.policy"
  #  2. we replace the line with the contents of the file
  #
  # this aspect of crosvm may change
  #
  input="$1"
  output="$2"
  contents="$3"

  if ! [[ -f $contents ]]; then
    echo "the contents file in $0 is not a file or does not exist."
    exit 14
  fi

  # clean up the outfile
  cat /dev/null > $output
  while IFS= read -r line
  do
    if echo "$line" | egrep "@include[[:space:]]+/usr/share/policy/crosvm/common_device.policy" > /dev/null; then
      cat $contents | egrep "^[a-zA-Z0-9_-]+:" >> $output
      continue
    fi
    echo $line >> $output
  done < "$input"
}

need_help="false"

#
# -p for crosvm seccomp policy directory
# -o for output directory where the processed policies land
# -c for contents file
#
while getopts ":p:o:c:h" op; do
  case "$op" in
    p ) policy_dir=${OPTARG}
        ;;
    o ) output_dir=${OPTARG}
        ;;
    c ) contents_file=${OPTARG}
        ;;
    h ) need_help="true"
        ;;
    ? ) need_help="true"
        ;;
  esac
done

if [ $OPTIND -eq 1 ]; then
  need_help="true"
fi

function help_n_exit() {
  echo "must provide all the -o, -c, and -p options"
  echo "-p for crosvm seccomp policy directory"
  echo "-o for output directory where the processed policies land"
  echo "-c for contents file"
  exit 10
}

function rstrip_slash() {
  if  [[ "${1: -1}" != "/" ]] || [[ $1 == "/" ]]; then
    echo $1
  else
    echo "${1::-1}"
  fi
}

stripped_policy_dir=$(rstrip_slash $policy_dir)
stripped_output_dir=$(rstrip_slash $output_dir)

if [[ $need_help == "true" ]]; then
  help_n_exit
fi

for i in $(ls -1 $policy_dir); do
  if is_policy_file $i; then
    inline $stripped_policy_dir/$i $stripped_output_dir/$i $stripped_policy_dir/common_device.policy
  fi
done

