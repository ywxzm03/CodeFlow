#!/bin/bash

# CodeWarp 启动脚本

CONFIG_FILE="$HOME/.codewarp/settings.json"
JAVA_21_HOME="${JAVA_21_HOME:-}"

if [ -z "$JAVA_21_HOME" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi

if [ -z "$JAVA_21_HOME" ] && [ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
    JAVA_21_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "$JAVA_21_HOME" ]; then
    export JAVA_HOME="$JAVA_21_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# 先安静生成启动脚本，再把当前进程替换成真实应用进程。
# 这样终端交互不会被 Gradle 的进度输出和控制台托管干扰。
./gradlew --quiet installDist
exec ./build/install/CodeWarp/bin/CodeWarp
