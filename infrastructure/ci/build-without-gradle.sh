#!/usr/bin/env bash
# Builds, tests and packages with only the JDK tools (no Gradle). Works on Linux and on Windows with Git Bash.
# Overrides: JAVAC, JAR_TOOL, JAVA (commands), JAVAC_FLAGS (default "--release 21"), LC_ALL for a UTF-8 locale.
# Optional reader adapters (off by default, see docs/ADAPTERS.md):
#   ADAPTERS=poi,pdfbox,jsoup,jsqlparser      (or "all") which adapters to compile and test
#   ADAPTER_LIB_DIR=/approved/jars            directory of approved jars, or
#   ADAPTER_CP="a.jar:b.jar"                  an explicit class path (';' separated on Windows)
#   SKIP_TEST_FILES='PoiDocxTests'            extended regex of adapter test files to leave out (when your jars cannot compile them)
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVAC="${JAVAC:-javac}"
JAR_TOOL="${JAR_TOOL:-jar}"
JAVA="${JAVA:-java}"
JAVAC_FLAGS="${JAVAC_FLAGS---release 21}"
ADAPTERS="${ADAPTERS:-}"
ADAPTER_CP="${ADAPTER_CP:-}"
ADAPTER_LIB_DIR="${ADAPTER_LIB_DIR:-}"
SKIP_TEST_FILES="${SKIP_TEST_FILES:-}"
OUT=build/plain

SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac

[ "$ADAPTERS" = "all" ] && ADAPTERS="poi,pdfbox,jsoup,jsqlparser"
if [ -n "$ADAPTER_LIB_DIR" ]; then
    ADAPTER_CP="$(find "$ADAPTER_LIB_DIR" -name '*.jar' | sort | tr '\n' "$SEP")${ADAPTER_CP}"
fi
if [ -n "$ADAPTERS" ] && [ -z "$ADAPTER_CP" ]; then
    echo "ADAPTERS=$ADAPTERS needs ADAPTER_LIB_DIR or ADAPTER_CP (approved jars). Nothing is downloaded." >&2
    exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"

MAIN_SOURCES="$(find backend/src/main -name '*.java')"
TEST_SOURCES="$(find backend/src/test -name '*.java')"
STRATEGIES=""
ANALYZERS=""
IFS=',' read -ra LIST <<< "$ADAPTERS"
for a in "${LIST[@]}"; do
    [ -z "$a" ] && continue
    case "$a" in
        poi)        STRATEGIES="$STRATEGIES com.example.casc.adapters.poi.PoiStrategy" ;;
        pdfbox)     STRATEGIES="$STRATEGIES com.example.casc.adapters.pdfbox.PdfStrategy" ;;
        jsoup)      STRATEGIES="$STRATEGIES com.example.casc.adapters.jsoup.HtmlStrategy" ;;
        jsqlparser) ANALYZERS="$ANALYZERS com.example.casc.adapters.jsqlparser.JSqlParserAnalyzer" ;;
        *) echo "Unknown adapter '$a'. Known: poi, pdfbox, jsoup, jsqlparser" >&2; exit 2 ;;
    esac
    MAIN_SOURCES="$MAIN_SOURCES
$(find "backend/src/adapters/$a/java" -name '*.java')"
    ADAPTER_TESTS="$(find "backend/src/adapters/$a/test/java" -name '*.java')"
    if [ -n "$SKIP_TEST_FILES" ]; then ADAPTER_TESTS="$(echo "$ADAPTER_TESTS" | grep -Ev "$SKIP_TEST_FILES" || true)"; fi
    TEST_SOURCES="$TEST_SOURCES
$ADAPTER_TESTS"
done

CP_MAIN=()
[ -n "$ADAPTER_CP" ] && CP_MAIN=(-cp "$ADAPTER_CP")
# shellcheck disable=SC2086
$JAVAC -d "$OUT/main" -encoding UTF-8 $JAVAC_FLAGS "${CP_MAIN[@]}" $MAIN_SOURCES
cp -r frontend/resources/. "$OUT/main/"

# ServiceLoader registration for the enabled adapters (the same files Gradle generates)
if [ -n "$STRATEGIES$ANALYZERS" ]; then
    mkdir -p "$OUT/main/META-INF/services"
    for c in $STRATEGIES; do echo "$c" >> "$OUT/main/META-INF/services/com.example.casc.ingest.SourceIngestionStrategy"; done
    for c in $ANALYZERS; do echo "$c" >> "$OUT/main/META-INF/services/com.example.casc.ingest.SqlDmlAnalyzer"; done
fi

# shellcheck disable=SC2086
$JAVAC -d "$OUT/test" -cp "$OUT/main${ADAPTER_CP:+$SEP$ADAPTER_CP}" -encoding UTF-8 $JAVAC_FLAGS $TEST_SOURCES

"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT/test${SEP}$OUT/main${ADAPTER_CP:+$SEP$ADAPTER_CP}" com.example.casc.TestRunner
# Same suite with main and test classes in separate class loaders: catches package-private access across them.
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT/test" com.example.casc.SplitLoaderRunner "$OUT/main${ADAPTER_CP:+$SEP$ADAPTER_CP}" "$OUT/test"

# Manifest: Main-Class, and the adapter libraries (copied to lib/ next to the jar) in Class-Path. Lines are wrapped at 70 bytes.
CLASSPATH_ATTR=""
if [ -n "$ADAPTER_CP" ]; then
    mkdir -p "$OUT/lib"
    IFS="$SEP" read -ra JARS <<< "$ADAPTER_CP"
    for j in "${JARS[@]}"; do
        [ -z "$j" ] && continue
        cp "$j" "$OUT/lib/"
        CLASSPATH_ATTR="$CLASSPATH_ATTR lib/$(basename "$j")"
    done
fi
{
    echo "Main-Class: com.example.casc.Main"
    echo "Implementation-Version: plain"
    if [ -n "$CLASSPATH_ATTR" ]; then
        echo "Class-Path:${CLASSPATH_ATTR}" | awk '{ s=$0; print substr(s,1,70); s=substr(s,71); while (length(s)>0) { print " " substr(s,1,69); s=substr(s,70) } }'
    fi
} > "$OUT/manifest.mf"
# shellcheck disable=SC2086
$JAR_TOOL --create --file "$OUT/casc-generator.jar" --manifest "$OUT/manifest.mf" -C "$OUT/main" .
echo "Built $OUT/casc-generator.jar${ADAPTERS:+ with adapters: $ADAPTERS}"
