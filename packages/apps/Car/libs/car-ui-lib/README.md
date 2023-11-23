# Android Automotive 'Chassis' library
Components and resources designed to increase Automotive UI consistency between
GAS (Google Automotive Services) apps, system-apps and OEM developed apps.

See: go/aae-carui

## Content

Components and resources designed to be configured by means of RRO (Runtime
Resource Overlays) by OEMs.

## Updating

This library is developed in Gerrit and copied as source to Google3 using
Copybara (go/copybara).

Source: /packages/apps/Car/libs/car-ui-lib
Target: //google3/third_party/java/android_libs/android_car_chassis_lib

Here is the process for updating this library:

1. Develop, test and upload changes to Gerrit
2. On Google3, run './update.sh review <cl>' (with <cl> being your Gerrit CL #) and test your changes
3. Repeat #1 and #2 until your changes look okay on both places.
4. Back on Gerrit, submit your CL.
5. Back on Google3, run './update.sh manual' submit

TODO: Automate this process using CaaS (in progress)
