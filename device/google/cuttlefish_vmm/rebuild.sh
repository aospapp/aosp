#!/bin/bash

# Common code to build a host image on GCE

# INTERNAL_extra_source may be set to a directory containing the source for
# extra package to build.

# INTERNAL_IP can be set to --internal-ip run on a GCE instance
# The instance will need --scope compute-rw

source "${ANDROID_BUILD_TOP}/external/shflags/src/shflags"

DEFINE_string build_instance \
  "${USER}-build" "Instance name to create for the build" "i"
DEFINE_string build_user cuttlefish_crosvm_builder \
  "User name to use on GCE when doing the build"
DEFINE_string project "$(gcloud config get-value project)" "Project to use" "p"
DEFINE_string source_image_family debian-9 "Image familty to use as the base" \
  "s"
DEFINE_string source_image_project debian-cloud \
  "Project holding the base image" "m"
DEFINE_string zone "$(gcloud config get-value compute/zone)" "Zone to use" "z"

SSH_FLAGS=(${INTERNAL_IP})

wait_for_instance() {
  alive=""
  while [[ -z "${alive}" ]]; do
    sleep 5
    alive="$(gcloud compute ssh "${SSH_FLAGS[@]}" "$@" -- uptime || true)"
  done
}

main() {
  set -o errexit
  set -x
  fail=0
  source_files=(rebuild_gce.sh)
  if [[ -z "${FLAGS_project}" ]]; then
    echo Must specify project 1>&2
    fail=1
  fi
  if [[ -z "${FLAGS_zone}" ]]; then
    echo Must specify zone 1>&2
    fail=1
  fi
  if [[ "${fail}" -ne 0 ]]; then
    exit "${fail}"
  fi
  project_zone_flags=(--project="${FLAGS_project}" --zone="${FLAGS_zone}")
  delete_instances=("${FLAGS_build_instance}")
  gcloud compute instances delete -q \
    "${project_zone_flags[@]}" \
    "${delete_instances[@]}" || \
      echo Not running
  gcloud compute instances create \
    "${project_zone_flags[@]}" \
    --machine-type=n1-standard-4 \
    --image-family="${FLAGS_source_image_family}" \
    --image-project="${FLAGS_source_image_project}" \
    "${FLAGS_build_instance}"
  wait_for_instance "${FLAGS_build_instance}"
  # beta for the --internal-ip flag that may be passed via SSH_FLAGS
  gcloud beta compute scp "${SSH_FLAGS[@]}" \
    "${project_zone_flags[@]}" \
    "${source_files[@]}" \
    "${FLAGS_build_user}@${FLAGS_build_instance}:"
  gcloud compute ssh "${SSH_FLAGS[@]}" \
    "${project_zone_flags[@]}" \
    "${FLAGS_build_user}@${FLAGS_build_instance}" -- \
      ./rebuild_gce.sh
  gcloud beta compute scp --recurse "${SSH_FLAGS[@]}" \
    "${project_zone_flags[@]}" \
    "${FLAGS_build_user}@${FLAGS_build_instance}":x86_64 \
    "${ANDROID_BUILD_TOP}/device/google/cuttlefish_vmm"
  gcloud compute disks describe \
    "${project_zone_flags[@]}" "${FLAGS_build_instance}" | \
      grep ^sourceImage: > x86_64/builder_image.txt
  exit 0
  gcloud compute instances delete -q \
    "${project_zone_flags[@]}" \
    "${FLAGS_build_instance}"
}

FLAGS "$@" || exit 1
main "${FLAGS_ARGV[@]}"
