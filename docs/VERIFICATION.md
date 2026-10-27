# Verification Ledger

## Executed (authoring sandbox)
- Platform: Linux x86_64 (Ubuntu 24.04), OpenJDK 21.0.10. Compiled with the JDK's `jdk.compiler` module, not Gradle.
- Test runner: 64 tests. Under `LC_ALL=C.UTF-8` all 64 pass. Under the POSIX locale 63 pass and 1 is skipped (Unicode file names need a UTF-8 locale).
- Packaged jar built with the JDK `jar` tool and run: web start, `/sources`, static assets, `/api/draft`, CLI `ingest` on the sample folder with a draft written.
- Scale checks: 3,000 CREATE TABLE statements ingested in about 0.7 s; a 200,000 row CSV sampled in about 0.6 s and read fully in about 1.8 s.
- Launchers from Phase 2 were run from another working directory with spaces in the path (Phase 2 check; not repeated in Phase 3).

## Supplied but NOT executed
- Gradle build (`jar`, `unitTests`, wrapper generation). Gradle was not available in the sandbox; `build.gradle` has never been parsed by Gradle.
- `run-web.bat`, `run-cli.bat` and anything on Windows: drive-letter paths, CRLF launchers, file locking, Windows symbolic-link behaviour.
- Symbolic-link tests ran on Linux only.
- Browser rendering of the Sources page and its JavaScript (file loader, sample button). Server-rendered output was checked with HTTP requests only.
- Dialect coverage is one synthetic sample per dialect; no real-world scripts from these databases were tried.

## Not available
- Official CasC schema validation and FDC acceptance evidence.
- Native XLSX, DOCX, PDF, HTML and YAML input, and SQL DML analysis (they need libraries that are not part of this build).

## Equivalent commands used here (no Gradle)
```
java -m jdk.compiler/com.sun.tools.javac.Main -d out -encoding UTF-8 $(find backend/src -name '*.java')
cp -r frontend/resources/* out/
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp out com.example.casc.TestRunner
```
