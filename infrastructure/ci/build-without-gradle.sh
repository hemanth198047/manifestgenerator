#!/usr/bin/env bash
# Builds, tests and packages with only the JDK tools (no Gradle). Works on Linux and on Windows with Git Bash.
# Overrides: JAVAC, JAR_TOOL, JAVA (commands), JAVAC_FLAGS (default "--release 21"), LC_ALL for a UTF-8 locale.
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVAC="${JAVAC:-javac}"
JAR_TOOL="${JAR_TOOL:-jar}"
JAVA="${JAVA:-java}"
JAVAC_FLAGS="${JAVAC_FLAGS---release 21}"
OUT=build/plain
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"

# shellcheck disable=SC2046,SC2086
$JAVAC -d "$OUT/main" -encoding UTF-8 $JAVAC_FLAGS $(find backend/src/main -name '*.java')
cp -r frontend/resources/. "$OUT/main/"
# shellcheck disable=SC2046,SC2086
$JAVAC -d "$OUT/test" -cp "$OUT/main" -encoding UTF-8 $JAVAC_FLAGS $(find backend/src/test -name '*.java')

SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT/test${SEP}$OUT/main" com.example.casc.TestRunner

printf 'Main-Class: com.example.casc.Main\nImplementation-Version: plain\n' > "$OUT/manifest.mf"
# shellcheck disable=SC2086
$JAR_TOOL --create --file "$OUT/casc-generator.jar" --manifest "$OUT/manifest.mf" -C "$OUT/main" .
echo "Built $OUT/casc-generator.jar"
