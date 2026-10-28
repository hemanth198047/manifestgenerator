#!/usr/bin/env bash
# Verifies a built jar the same way on every OS: version, capabilities, the agent tool examples, and that the draft
# manifest generated from the sample sources is byte-identical to the recorded hash (equivalent output on Windows and Linux).
# Overrides: CASC_JAR (default build/libs/casc-generator.jar), JAVA (default java).
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR="${CASC_JAR:-build/libs/casc-generator.jar}"
JAVA="${JAVA:-java}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
[ -f "$JAR" ] || { echo "Jar not found: $JAR" >&2; exit 2; }

"$JAVA" -jar "$JAR" version
"$JAVA" -jar "$JAR" capabilities --json > "$OUT/capabilities.json"
grep -q '"UNAVAILABLE"' "$OUT/capabilities.json"          # unavailable capabilities must be reported, not hidden

"$JAVA" -jar "$JAR" ingest --input-files frontend/resources/static/samples --db-name TRADE_DB --db-type Oracle \
    --default-schema trade --output "$OUT/manifest.json" > "$OUT/ingest.txt"
actual="$(sha256sum "$OUT/manifest.json" | cut -d' ' -f1)"
expected="$(cut -d' ' -f1 infrastructure/ci/expected-manifest.sha256)"
if [ "$actual" != "$expected" ]; then
    echo "Draft manifest differs from the recorded hash: $actual != $expected" >&2
    exit 1
fi

for t in ingest_sources validate_manifest_payload generate_casc_manifest; do
    "$JAVA" -jar "$JAR" tool "$t" --request "infrastructure/integrations/examples/$t.request.json" --fail-on-errors > "$OUT/$t.json"
    grep -q '"ok": true' "$OUT/$t.json"
done

set +e
"$JAVA" -jar "$JAR" validate --input "$OUT/missing.json" > /dev/null 2>&1
code=$?
set -e
[ "$code" = "3" ] || { echo "expected exit code 3 for a missing input, got $code" >&2; exit 1; }
echo "verify: all checks passed ($actual)"
