# syntax=docker/dockerfile:1
# Builds the Surfer Android app. Everything Android/Java lives only in this image.

FROM eclipse-temurin:17-jdk-noble

ARG CMDLINE_TOOLS=16111833
ARG CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
ARG GRADLE_VERSION=9.8.2
ARG GRADLE_SHA256=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607
ARG PLATFORM=android-36
ARG BUILD_TOOLS=36.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$GRADLE_HOME/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA1}  /tmp/tools.zip" | sha1sum -c - \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" \
 && chmod -R a+rX $ANDROID_HOME \
 && rm -rf /root/.android

RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-${GRADLE_VERSION} $GRADLE_HOME \
 && rm /tmp/gradle.zip

COPY build.sh /usr/local/bin/build-apk
RUN chmod +x /usr/local/bin/build-apk \
 && mkdir -p /keystore /work /home/ubuntu/.gradle \
 && chown -R ubuntu:ubuntu /keystore /work /home/ubuntu/.gradle

# uid 1000, same as the server's user, so it can write /data/apk.
USER ubuntu
WORKDIR /work
CMD ["build-apk"]
