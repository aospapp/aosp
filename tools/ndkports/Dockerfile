FROM gcr.io/cloud-builders/javac:8

RUN apt-get update && apt-get install -y \
    cmake \
    curl \
    ninja-build \
    python3-pip
RUN pip3 install meson
RUN curl -L -o platform-tools.zip \
    https://dl.google.com/android/repository/platform-tools-latest-linux.zip
RUN unzip platform-tools.zip platform-tools/adb
RUN mv platform-tools/adb /usr/bin/adb
RUN mkdir -m 0750 /.android

WORKDIR /src
ENTRYPOINT ["./gradlew", "--no-daemon", "--gradle-user-home=.gradle_home", "--stacktrace", "-PndkPath=/ndk"]
CMD ["-Prelease", "clean", "release"]
