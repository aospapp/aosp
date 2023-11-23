# Car test apps

This repository is only for car test applications.

## Building

If you are not contributing to the repo, you can clone the repo via `git clone sso://googleplex-android/platform/packages/apps/Car/tests --branch pi-car-dev --single-branch`. Otherwise, see [workstation setup](#workstation-setup).

Install [Android Studio](go/install-android-studio). Then import the `tests` Gradle project into Android Studio.

### TestMediaApp

TestMediaApp should be one of the run configurations. The green Run button should build and install the app on your phone.

To see TestMediaApp in Android Auto Projected:

1. Open Android Auto on phone
2. Click hamburger icon at top left -> Settings
3. Scroll to Version at bottom and tap ~10 times to unlock Developer Mode
4. Click kebab icon at top right -> Developer settings
5. Scroll to bottom and enable "Unknown sources"
6. Exit and re-open Android Auto
7. TestMediaApp should now be visible (click headphones icon in phone app to see app picker)

### RotaryPlayground

RotaryPlayground is a test and reference application for the AAOS Rotary framework to use with an external rotary input device.

To buid and install RotaryPlayground into an AAOS device:

```
$ make RotaryPlayground
$ adb install -r -g out/target/[path]/system/app/RotaryPlayground/RotaryPlayground.apk
```

## Contributing

### Workstation setup

Install [repo](https://source.android.com/setup/build/downloading#installing-repo) command line tool. Then run:

```
sudo apt-get install gitk
sudo apt-get install git-gui
mkdir WORKING_DIRECTORY_FOR_GIT_REPO
cd WORKING_DIRECTORY_FOR_GIT_REPO
repo init -u persistent-https://googleplex-android.git.corp.google.com/platform/manifest -b pi-car-dev -g name:platform/tools/repohooks,name:platform/packages/apps/Car/tests --depth=1
repo sync
```

### Making a change

```
repo start BRANCH_NAME .
# Make some changes
git gui &
# Use GUI to create a CL. Check amend box to update a work-in-progress CL
repo upload .
```

