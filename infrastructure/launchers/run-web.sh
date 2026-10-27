#!/usr/bin/env sh
# Starts the web application from any working directory. Extra JVM flags: JAVA_OPTS.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/../../build/libs/casc-generator.jar"
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR (run ./gradlew bootJar first)" >&2
    exit 2
fi
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$JAR" "$@"
