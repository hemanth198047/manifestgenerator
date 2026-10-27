# Manual Recreation Handbook (Phase 3)

The project has no libraries, so recreating it needs only a JDK, Gradle and an editor.
Do not type generated files: `gradlew`, `gradlew.bat` and `gradle/wrapper/*` come from `gradle wrapper`; `build/` and `.gradle/` are output.

## Readiness
OS (Windows or Linux) | approved JDK 17+ with `jdk.httpserver` (standard) | approved Gradle | an editor | permission to write the project directory and run `java` and `gradle`.
No repositories, plugins or libraries are needed. Linux: use a UTF-8 locale for non-ASCII file names.

## File inventory (create in this order; about 7395 lines in 65 files)
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
| 18 | `backend/src/main/java/com/example/casc/ingest/Csv.java` | 129 | Common |
| 19 | `backend/src/main/java/com/example/casc/ingest/DelimitedStrategy.java` | 181 | Common |
| 20 | `backend/src/main/java/com/example/casc/ingest/Dictionary.java` | 149 | Common |
| 21 | `backend/src/main/java/com/example/casc/ingest/DraftBuilder.java` | 92 | Common |
| 22 | `backend/src/main/java/com/example/casc/ingest/IngestOptions.java` | 111 | Common |
| 23 | `backend/src/main/java/com/example/casc/ingest/IngestRequest.java` | 79 | Common |
| 24 | `backend/src/main/java/com/example/casc/ingest/InputFiles.java` | 118 | Common |
| 25 | `backend/src/main/java/com/example/casc/ingest/RawMetadataBundle.java` | 187 | Common |
| 26 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionService.java` | 151 | Common |
| 27 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionStrategy.java` | 13 | Common |
| 28 | `backend/src/main/java/com/example/casc/ingest/SourceInput.java` | 37 | Common |
| 29 | `backend/src/main/java/com/example/casc/ingest/SqlStrategy.java` | 801 | Common |
| 30 | `backend/src/main/java/com/example/casc/ingest/SqlTokenizer.java` | 190 | Common |
| 31 | `backend/src/main/java/com/example/casc/ingest/TextStrategy.java` | 185 | Common |
| 32 | `backend/src/main/java/com/example/casc/app/Capability.java` | 17 | Common |
| 33 | `backend/src/main/java/com/example/casc/app/CapabilityService.java` | 47 | Common |
| 34 | `backend/src/main/java/com/example/casc/app/CapabilityUnavailableException.java` | 10 | Common |
| 35 | `backend/src/main/java/com/example/casc/app/DraftPipeline.java` | 46 | Common |
| 36 | `backend/src/main/java/com/example/casc/app/ExportResult.java` | 8 | Common |
| 37 | `backend/src/main/java/com/example/casc/app/ExportService.java` | 91 | Common |
| 38 | `frontend/resources/static/css/app.css` | 27 | Common |
| 39 | `frontend/resources/static/js/app.js` | 63 | Common |
| 40 | `frontend/resources/static/samples/sample-dictionary.csv` | 5 | Common |
| 41 | `frontend/resources/static/samples/sample-manifest.json` | 76 | Common |
| 42 | `frontend/resources/static/samples/sample-notes.md` | 15 | Common |
| 43 | `frontend/resources/static/samples/sample-schema.sql` | 17 | Common |
| 44 | `frontend/resources/templates/home.html` | 11 | Common |
| 45 | `frontend/resources/templates/layout.html` | 26 | Common |
| 46 | `frontend/resources/templates/sources.html` | 20 | Common |
| 47 | `frontend/resources/templates/validate.html` | 18 | Common |
| 48 | `backend/src/main/java/com/example/casc/web/Html.java` | 24 | Common |
| 49 | `backend/src/main/java/com/example/casc/web/Templates.java` | 57 | Common |
| 50 | `backend/src/main/java/com/example/casc/web/WebServer.java` | 775 | Common |
| 51 | `backend/src/main/java/com/example/casc/Main.java` | 20 | Common |
| 52 | `backend/src/main/java/com/example/casc/cli/Cli.java` | 407 | Common |
| 53 | `backend/src/test/java/com/example/casc/TestKit.java` | 107 | Common |
| 54 | `backend/src/test/java/com/example/casc/AppTests.java` | 224 | Common |
| 55 | `backend/src/test/java/com/example/casc/DomainTests.java` | 85 | Common |
| 56 | `backend/src/test/java/com/example/casc/IngestAppTests.java` | 239 | Common |
| 57 | `backend/src/test/java/com/example/casc/OutputTests.java` | 107 | Common |
| 58 | `backend/src/test/java/com/example/casc/TestRunner.java` | 32 | Common |
| 59 | `backend/src/test/java/com/example/casc/ValidatorTests.java` | 122 | Common |
| 60 | `backend/src/test/java/com/example/casc/ingest/IngestTests.java` | 451 | Common |
| 61 | `infrastructure/README.md` | 34 | Common |
| 62 | `infrastructure/launchers/run-cli.bat` | 10 | Windows only (CRLF) |
| 63 | `infrastructure/launchers/run-cli.sh` | 10 | Linux only (LF, executable) |
| 64 | `infrastructure/launchers/run-web.bat` | 10 | Windows only (CRLF) |
| 65 | `infrastructure/launchers/run-web.sh` | 10 | Linux only (LF, executable) |

`docs/` and `README.md` are optional for the build. The three sample sources under `frontend/resources/static/samples/` are used by the tests, so type them too.

## Typing method
1. Type one file at a time, in the order above, in chunks of 30-60 lines. Do not compile until a file is complete.
2. Checkpoints:
   - After the Java files up to and including `app/`: compile them (`javac -d out -encoding UTF-8 <files>` or `gradle compileJava`); it must print nothing.
   - After `web/`, `cli/` and `Main.java`: the full main compile must print nothing.
   - After the tests and the frontend files: run the tests. Expect `64 passed` (63 passed and 1 skipped without a UTF-8 locale).
3. Common typing mistakes: a missing `}` closing a `switch` or class; `->` typed as `-` `>`; curly quotes instead of straight quotes;
   `{{` `}}` in HTML templates must stay exactly as written; a `.sh` file saved with CRLF endings; the Unicode escapes `\u2192` and `\u2013` in `TextStrategy` typed literally.

## Commands
Linux: `gradle wrapper && ./gradlew jar unitTests && java -jar build/libs/casc-generator.jar web`
Windows: `gradle.bat wrapper` then `gradlew.bat jar unitTests` then `java -jar build\libs\casc-generator.jar web`
Without Gradle (compile and test directly): see `docs/VERIFICATION.md`.

## Recovery
- Wrong Java: set `JAVA_HOME` to the approved JDK; `options.release = 17` needs JDK 17 or newer.
- `unitTests` task not found: `build.gradle` was typed incompletely; the task is registered near the end of the file.
- A test fails after a typo: the FAIL line names the area; fix the first compile error before looking at test failures.
- Resume after a failed checkpoint: keep the files already typed and fix the first compile error; do not retype earlier files.
- Page shows "No value for {{x}}": a template token was mistyped or a value is missing in `WebServer`.
- Port in use: `--port 9090` or `CASC_PORT`.
- Linux permission denied on launchers: `chmod +x infrastructure/launchers/*.sh`. `bad interpreter` means CRLF endings; convert with `sed -i 's/\r$//' file`.
- Windows file locking: close editors or viewers holding the output file; exports use a temp file then move.
- Paths with spaces: quote them (`--input-files "C:\my files\specs"`).
- If your organization later requires internal repositories, add them to `settings.gradle` (`pluginManagement`) and `build.gradle` only when you add a dependency.
