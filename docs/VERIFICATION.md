# Verification Ledger

## Executed (authoring sandbox)
- Platform: Linux x86_64 (Ubuntu 24.04), OpenJDK 21.0.10. Compiled with the JDK's `jdk.compiler` module, not Gradle.
- Test runner: 31 tests; 30 pass and 1 is skipped under the POSIX locale (Unicode file names need a UTF-8 locale); under `LC_ALL=C.UTF-8` all 31 pass.
- Packaged jar built with the JDK `jar` tool; run: `version`, `validate`, `export` (JSON/YAML), usage errors (exit 2), port conflict (exit 3), web start, `curl` against `/`, `/validate`, `/api/export`, Origin rejection (403), Host rejection (403).
- Launchers `run-cli.sh` and `run-web.sh` run from another working directory with a path containing spaces; exit codes 0, 1 and 3 propagated.

## Supplied but NOT executed
- Gradle build (`jar`, `unitTests`, wrapper generation). Gradle and its distribution were not available in the sandbox.
- `build.gradle` has not been parsed by Gradle. Treat the first `gradlew unitTests` on your side as the real check.
- `run-web.bat`, `run-cli.bat` and anything on Windows.
- Windows paths and drive letters, file locking, CRLF launcher behaviour.
- Browser rendering of the pages and the JavaScript file loader (no browser was used).

## Not available
- Official CasC schema validation and FDC acceptance evidence.

## Equivalent commands used here (no Gradle)
```
java -m jdk.compiler/com.sun.tools.javac.Main -d out -encoding UTF-8 $(find backend/src -name '*.java')
cp -r frontend/resources/* out/
java -Dfile.encoding=UTF-8 -cp out com.example.casc.TestRunner
```
