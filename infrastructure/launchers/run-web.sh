#!/usr/bin/env sh
# Starts the web UI from any working directory. Extra JVM flags: JAVA_OPTS. Extra arguments are passed on (--port, --bind).
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/../../build/libs/casc-generator.jar"
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR (run ./gradlew jar first)" >&2
    exit 2
fi
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$JAR" web "$@"
