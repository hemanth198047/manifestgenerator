# Manual Recreation Handbook (Phase 2)

Everything is plain text and the project has no libraries, so recreating it needs only a JDK, Gradle and an editor.
Do not type generated files: `gradlew`, `gradlew.bat` and `gradle/wrapper/*` come from `gradle wrapper`; `build/` and `.gradle/` are output.

## Readiness
OS (Windows or Linux) | approved JDK 17+ with `jdk.httpserver` (standard) | approved Gradle | an editor | permission to write the project directory and run `java` and `gradle`.
No repositories, plugins or libraries are needed. Linux: UTF-8 locale for non-ASCII file names.

## File inventory (create in this order; about 3705 lines in total)
| Seq | Path | Lines | OS |
|-----|------|-------|----|
| 1 | `settings.gradle` | 3 | Common |
| 2 | `build.gradle` | 44 | Common |
| 3 | `.gitignore` | 33 | Common |
| 4 | `.gitattributes` | 5 | Common |
| 5 | `backend/src/main/java/com/example/casc/domain/CasCManifest.java` | 57 | Common |
| 6 | `backend/src/main/java/com/example/casc/domain/Enums.java` | 56 | Common |
| 7 | `backend/src/main/java/com/example/casc/profile/SchemaProfile.java` | 18 | Common |
| 8 | `backend/src/main/java/com/example/casc/json/Json.java` | 385 | Common |
| 9 | `backend/src/main/java/com/example/casc/json/JsonException.java` | 9 | Common |
| 10 | `backend/src/main/java/com/example/casc/validation/CasCValidatorService.java` | 327 | Common |
| 11 | `backend/src/main/java/com/example/casc/validation/Finding.java` | 30 | Common |
| 12 | `backend/src/main/java/com/example/casc/validation/Severity.java` | 3 | Common |
| 13 | `backend/src/main/java/com/example/casc/validation/ValidationReport.java` | 45 | Common |
| 14 | `backend/src/main/java/com/example/casc/manifest/ManifestMapper.java` | 564 | Common |
| 15 | `backend/src/main/java/com/example/casc/output/JsonManifestSerializer.java` | 18 | Common |
| 16 | `backend/src/main/java/com/example/casc/output/ManifestSerializer.java` | 15 | Common |
| 17 | `backend/src/main/java/com/example/casc/output/YamlManifestSerializer.java` | 139 | Common |
| 18 | `backend/src/main/java/com/example/casc/app/Capability.java` | 17 | Common |
| 19 | `backend/src/main/java/com/example/casc/app/CapabilityService.java` | 41 | Common |
| 20 | `backend/src/main/java/com/example/casc/app/CapabilityUnavailableException.java` | 10 | Common |
| 21 | `backend/src/main/java/com/example/casc/app/ExportResult.java` | 8 | Common |
| 22 | `backend/src/main/java/com/example/casc/app/ExportService.java` | 91 | Common |
| 23 | `frontend/resources/static/css/app.css` | 21 | Common |
| 24 | `frontend/resources/static/js/app.js` | 22 | Common |
| 25 | `frontend/resources/static/samples/sample-manifest.json` | 76 | Common |
| 26 | `frontend/resources/templates/home.html` | 11 | Common |
| 27 | `frontend/resources/templates/layout.html` | 25 | Common |
| 28 | `frontend/resources/templates/validate.html` | 18 | Common |
| 29 | `backend/src/main/java/com/example/casc/web/Html.java` | 24 | Common |
| 30 | `backend/src/main/java/com/example/casc/web/Templates.java` | 57 | Common |
| 31 | `backend/src/main/java/com/example/casc/web/WebServer.java` | 486 | Common |
| 32 | `backend/src/main/java/com/example/casc/cli/Cli.java` | 285 | Common |
| 33 | `backend/src/main/java/com/example/casc/Main.java` | 20 | Common |
| 34 | `backend/src/test/java/com/example/casc/AppTests.java` | 224 | Common |
| 35 | `backend/src/test/java/com/example/casc/DomainTests.java` | 85 | Common |
| 36 | `backend/src/test/java/com/example/casc/OutputTests.java` | 107 | Common |
| 37 | `backend/src/test/java/com/example/casc/TestKit.java` | 107 | Common |
| 38 | `backend/src/test/java/com/example/casc/TestRunner.java` | 30 | Common |
| 39 | `backend/src/test/java/com/example/casc/ValidatorTests.java` | 122 | Common |
| 40 | `infrastructure/README.md` | 27 | Common |
| 41 | `infrastructure/launchers/run-cli.bat` | 10 | Windows only (CRLF) |
| 42 | `infrastructure/launchers/run-cli.sh` | 10 | Linux only (LF, executable) |
| 43 | `infrastructure/launchers/run-web.bat` | 10 | Windows only (CRLF) |
| 44 | `infrastructure/launchers/run-web.sh` | 10 | Linux only (LF, executable) |

Documentation under `docs/` and `README.md` are optional for the build.

## Typing method
1. Type one file at a time, in the order above, in chunks of 30-60 lines. Do not compile until a file is complete.
2. Checkpoints:
   - After the Java files under `domain`, `profile`, `json`, `validation`, `manifest`, `output` and `app`:
     `javac -d out -encoding UTF-8 <those files>` (or `gradle compileJava` once the build files exist) must print nothing.
   - After `web`, `cli` and `Main.java`: the full compile must print nothing.
   - After the tests: run the test runner (below). Expect `31 passed` (30 passed and 1 skipped without a UTF-8 locale).
3. Common typing mistakes: a missing `}` closing a `switch` or class; `->` typed as `-` `>`; straight quotes pasted as curly quotes;
   `{{` `}}` in HTML templates must stay exactly as written; a `.sh` file saved with CRLF endings.

## Commands
Linux: `gradle wrapper && ./gradlew jar unitTests && java -jar build/libs/casc-generator.jar web`
Windows: `gradle.bat wrapper` then `gradlew.bat jar unitTests` then `java -jar build\libs\casc-generator.jar web`

Without Gradle (compile and test directly): see `docs/VERIFICATION.md`.

## Recovery
- Wrong Java: set `JAVA_HOME` to the approved JDK; `options.release = 17` needs JDK 17 or newer.
- `unitTests` task not found: `build.gradle` was typed incompletely; the task is registered near the end of the file.
- Test failures after a typo: the test name printed with FAIL identifies the area; rerun only the compile step first.
- Resume after a failed checkpoint: keep the files already typed; fix the first compile error reported; do not retype earlier files.
- Port in use: `--port 9090` or `CASC_PORT`.
- Linux permission denied on launchers: `chmod +x infrastructure/launchers/*.sh`. `bad interpreter` means CRLF endings; convert with `sed -i 's/\r$//' file`.
- Windows file locking: close editors or viewers holding the output file; exports use a temp file then move.
- Paths with spaces: quote them (`--input "C:\\my files\\m.json"`).
- If your organization later requires internal repositories, add them to `settings.gradle` (`pluginManagement`) and `build.gradle` only when you add a dependency.
