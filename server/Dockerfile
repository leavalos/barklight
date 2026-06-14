FROM eclipse-temurin:17-jdk-jammy AS builder

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_VERSION=8.2 \
    ANDROID_PLATFORM=android-30 \
    ANDROID_BUILD_TOOLS=30.0.3 \
    DEBIAN_FRONTEND=noninteractive

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}"

RUN apt-get update -q && apt-get install -y --no-install-recommends \
        curl unzip zip wget ca-certificates && rm -rf /var/lib/apt/lists/*

# Gradle
RUN wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -O /tmp/gradle.zip && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle

# Android SDK command-line tools — necesitamos android.jar y d8 (build-tools)
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
             -O /tmp/ct.zip && unzip -q /tmp/ct.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/ct.zip /tmp/ct

# Android 11 = API 30. build-tools incluye d8 (compilador a DEX)
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}"

WORKDIR /project
COPY . .

# Copiar android.jar para compilar contra las APIs de Android (incluye SurfaceControl)
RUN mkdir -p android-stubs \
    && cp ${ANDROID_SDK_ROOT}/platforms/${ANDROID_PLATFORM}/android.jar android-stubs/

# 1. Compilar .java -> .class (jar normal de Java)
RUN gradle jar --no-daemon --quiet && echo "JAR Java compilado"

# 2. Convertir .class -> classes.dex con d8
#    ART (runtime de Android) NO puede ejecutar .class de Java directamente,
#    necesita bytecode DEX. d8 es el compilador oficial de Android para esto.
RUN mkdir -p dex-build && cd dex-build \
    && unzip -o /project/build/libs/barklight-server.jar -d extracted \
    && d8 --output . \
        --lib ${ANDROID_SDK_ROOT}/platforms/${ANDROID_PLATFORM}/android.jar \
        $(find extracted -name "*.class") \
    && echo "DEX generado"

# 3. Empaquetar classes.dex en un JAR (app_process puede ejecutar JARs que
#    contengan classes.dex directamente)
RUN cd dex-build \
    && mkdir -p final/META-INF \
    && cp classes.dex final/ \
    && printf 'Manifest-Version: 1.0\nMain-Class: com.barklight.server.BarkLightServer\n' > final/META-INF/MANIFEST.MF \
    && cd final && zip -r /project/barklight-server-dex.jar . \
    && echo "JAR final con DEX listo"

FROM alpine:3.19 AS output
COPY --from=builder /project/barklight-server-dex.jar /jar/barklight-server.jar

RUN printf '#!/bin/sh\ncp /jar/barklight-server.jar /output/barklight-server.jar\necho "JAR (DEX) listo en ./output/barklight-server.jar"\n' \
    > /entrypoint.sh && chmod +x /entrypoint.sh

VOLUME ["/output"]
ENTRYPOINT ["/entrypoint.sh"]
