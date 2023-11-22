# Examples of using simpleperf to profile Android applications

## Table of Contents

- [Introduction](#introduction)
- [Profiling Java application](#profiling-java-application)
- [Profiling Java/C++ application](#profiling-javac-application)
- [Profiling Kotlin application](#profiling-kotlin-application)

## Introduction

Simpleperf is a native profiler used on Android platform. It can be used to profile Android
applications. It's document is at [here](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/README.md).
Instructions of preparing your Android application for profiling are [here](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/README.md#Android-application-profiling).
This directory is to show examples of using simpleperf to profile Android applications. The
meaning of each directory is as below:

    ../scripts/                  -- contain simpleperf binaries and scripts.
    SimpleperfExamplePureJava/   -- contains an Android Studio project using only Java code.
    SimpleperfExampleWithNative/ -- contains an Android Studio project using both Java and C++ code.
    SimpleperfExampleOfKotlin/   -- contains an Android Studio project using Kotlin code.

It can be downloaded as below:

    $ git clone https://android.googlesource.com/platform/system/extras
    $ cd extras/simpleperf/demo

## Profiling Java application

    Android Studio project: SimpleExamplePureJava
    test device: Android O (Google Pixel XL)
    test device: Android N (Google Nexus 5X)

steps:
1. Build and install app:
```
# Open SimpleperfExamplesPureJava project with Android Studio,
# and build this project sucessfully, otherwise the `./gradlew` command below will fail.
$ cd SimpleperfExamplePureJava

# On windows, use "gradlew" instead.
$ ./gradlew clean assemble
$ adb install -r app/build/outputs/apk/app-profiling.apk
```

2. Record profiling data:
```
$ cd ../../scripts/
$ python app_profiler.py -p com.example.simpleperf.simpleperfexamplepurejava
```

3. Show profiling data:
```
a. show call graph in txt mode
    # On windows, use "bin\windows\x86\simpleperf" instead.
    $ bin/linux/x86_64/simpleperf report -g | more
        If on other hosts, use corresponding simpleperf binary.
b. show call graph in gui mode
    $ python report.py -g
c. show samples in source code
    $ python annotate.py -s ../demo/SimpleperfExamplePureJava
    $ gvim annotated_files/java/com/example/simpleperf/simpleperfexamplepurejava/MainActivity.java
        check the annoated source file MainActivity.java.
```

## Profiling Java/C++ application

    Android Studio project: SimpleExampleWithNative
    test device: Android O (Google Pixel XL)
    test device: Android N (Google Nexus 5X)

steps:
1. Build and install app:
```
# Open SimpleperfExamplesWithNative project with Android Studio,
# and build this project sucessfully, otherwise the `./gradlew` command below will fail.
$ cd SimpleperfExampleWithNative

# On windows, use "gradlew" instead.
$ ./gradlew clean assemble
$ adb install -r app/build/outputs/apk/app-profiling.apk
```

2. Record profiling data:
```
$ cd ../../scripts/
$ python app_profiler.py -p com.example.simpleperf.simpleperfexamplewithnative
    It runs the application and collects profiling data in perf.data, binaries on device in binary_cache/.
```

3. Show profiling data:
```
a. show call graph in txt mode
    # On windows, use "bin\windows\x86\simpleperf" instead.
    $ bin/linux/x86_64/simpleperf report -g | more
        If on other hosts, use corresponding simpleperf binary.
b. show call graph in gui mode
    $ python report.py -g
c. show samples in source code
    $ python annotate.py -s ../demo/SimpleperfExampleWithNative
    $ find . -name "native-lib.cpp" | xargs gvim
        check the annoated source file native-lib.cpp.
```

## Profiling Kotlin application

    Android Studio project: SimpleExampleOfKotlin
    test device: Android O (Google Pixel XL)
    test device: Android N (Google Nexus 5X)

steps:
1. Build and install app:
```
# Open SimpleperfExamplesOfKotlin project with Android Studio,
# and build this project sucessfully, otherwise the `./gradlew` command below will fail.
$ cd SimpleperfExampleOfKotlin

# On windows, use "gradlew" instead.
$ ./gradlew clean assemble
$ adb install -r app/build/outputs/apk/profiling/app-profiling.apk
```

2. Record profiling data:
```
$ cd ../../scripts/
$ python app_profiler.py -p com.example.simpleperf.simpleperfexampleofkotlin
    It runs the application and collects profiling data in perf.data, binaries on device in binary_cache/.
```

3. Show profiling data:
```
a. show call graph in txt mode
    # On windows, use "bin\windows\x86\simpleperf" instead.
    $ bin/linux/x86_64/simpleperf report -g | more
        If on other hosts, use corresponding simpleperf binary.
b. show call graph in gui mode
    $ python report.py -g
c. show samples in source code
    $ python annotate.py -s ../demo/SimpleperfExampleOfKotlin
    $ find . -name "MainActivity.kt" | xargs gvim
        check the annoated source file MainActivity.kt.
```
