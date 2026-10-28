# Manual Recreation Handbook (Phase 6)

The project has no libraries, so recreating it needs only a JDK, Gradle and an editor.
Do not type generated files: `gradlew`, `gradlew.bat` and `gradle/wrapper/*` come from `gradle wrapper`; `build/` and `.gradle/` are output.

## Readiness
OS (Windows or Linux) | approved JDK 17+ with `jdk.httpserver` and `java.net.http` (standard) | approved Gradle | an editor | permission to write the project directory and run `java` and `gradle`.
No repositories, plugins or libraries are needed. Linux: use a UTF-8 locale for non-ASCII file names. URL features additionally need network access to the allowlisted hosts and,
for internal certificate authorities, an approved truststore. The CI scripts need Bash (Git Bash on Windows) and `sha256sum`.

## Size and a recommended way to type it
About 13632 lines in 106 files, of which roughly half are tests. Type in this order and stop at each checkpoint:
1. Build files and the `domain`, `profile`, `json`, `validation`, `manifest` and `output` packages: the core. Checkpoint A: compile.
2. `ingest`, `net`, `wizard`, `agent` and `app`: the engines. Checkpoint B: compile.
3. `web`, `cli`, `Main.java` and the `frontend/` files. Checkpoint C: compile and start the web UI.
4. Tests last, in the order listed. Checkpoint D: run the test runner.
5. Optional: `infrastructure/` scripts, integration files and CI. The agent-contract and CI-hash tests read `infrastructure/integrations/` and `infrastructure/ci/`; they report SKIP when those are absent.
You can run the application after checkpoint C without the tests, and add tests file by file (the runner registers them in `TestRunner.java`; leave a test class out and comment out its `register()` line).

## File inventory (create in this order)
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
| 21 | `backend/src/main/java/com/example/casc/ingest/DraftBuilder.java` | 150 | Common |
| 22 | `backend/src/main/java/com/example/casc/ingest/IngestOptions.java` | 111 | Common |
| 23 | `backend/src/main/java/com/example/casc/ingest/IngestRequest.java` | 105 | Common |
| 24 | `backend/src/main/java/com/example/casc/ingest/InputFiles.java` | 128 | Common |
| 25 | `backend/src/main/java/com/example/casc/ingest/OpenApiStrategy.java` | 318 | Common |
| 26 | `backend/src/main/java/com/example/casc/ingest/RawMetadataBundle.java` | 247 | Common |
| 27 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionService.java` | 156 | Common |
| 28 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionStrategy.java` | 13 | Common |
| 29 | `backend/src/main/java/com/example/casc/ingest/SourceInput.java` | 37 | Common |
| 30 | `backend/src/main/java/com/example/casc/ingest/SqlStrategy.java` | 801 | Common |
| 31 | `backend/src/main/java/com/example/casc/ingest/SqlTokenizer.java` | 190 | Common |
| 32 | `backend/src/main/java/com/example/casc/ingest/TextStrategy.java` | 185 | Common |
| 33 | `backend/src/main/java/com/example/casc/net/HttpUrlFetcher.java` | 246 | Common |
| 34 | `backend/src/main/java/com/example/casc/net/UrlFetcher.java` | 36 | Common |
| 35 | `backend/src/main/java/com/example/casc/net/UrlPolicy.java` | 85 | Common |
| 36 | `backend/src/main/java/com/example/casc/net/UrlSources.java` | 98 | Common |
| 37 | `backend/src/main/java/com/example/casc/wizard/AssetKeys.java` | 27 | Common |
| 38 | `backend/src/main/java/com/example/casc/wizard/ManifestAssembler.java` | 199 | Common |
| 39 | `backend/src/main/java/com/example/casc/wizard/NameSuggester.java` | 57 | Common |
| 40 | `backend/src/main/java/com/example/casc/wizard/PiiHints.java` | 31 | Common |
| 41 | `backend/src/main/java/com/example/casc/wizard/ProjectFile.java` | 320 | Common |
| 42 | `backend/src/main/java/com/example/casc/wizard/Reconciler.java` | 160 | Common |
| 43 | `backend/src/main/java/com/example/casc/wizard/SessionStore.java` | 100 | Common |
| 44 | `backend/src/main/java/com/example/casc/wizard/SystemKeys.java` | 29 | Common |
| 45 | `backend/src/main/java/com/example/casc/wizard/WizardService.java` | 111 | Common |
| 46 | `backend/src/main/java/com/example/casc/wizard/WizardState.java` | 177 | Common |
| 47 | `backend/src/main/java/com/example/casc/agent/AgentTools.java` | 152 | Common |
| 48 | `backend/src/main/java/com/example/casc/agent/BundleReader.java` | 127 | Common |
| 49 | `backend/src/main/java/com/example/casc/agent/Fields.java` | 90 | Common |
| 50 | `backend/src/main/java/com/example/casc/agent/ToolException.java` | 16 | Common |
| 51 | `backend/src/main/java/com/example/casc/agent/UserInputs.java` | 247 | Common |
| 52 | `backend/src/main/java/com/example/casc/app/Capability.java` | 17 | Common |
| 53 | `backend/src/main/java/com/example/casc/app/CapabilityService.java` | 52 | Common |
| 54 | `backend/src/main/java/com/example/casc/app/CapabilityUnavailableException.java` | 10 | Common |
| 55 | `backend/src/main/java/com/example/casc/app/DraftPipeline.java` | 66 | Common |
| 56 | `backend/src/main/java/com/example/casc/app/ExportResult.java` | 8 | Common |
| 57 | `backend/src/main/java/com/example/casc/app/ExportService.java` | 91 | Common |
| 58 | `frontend/resources/static/css/app.css` | 37 | Common |
| 59 | `frontend/resources/static/js/app.js` | 82 | Common |
| 60 | `frontend/resources/static/samples/sample-dictionary.csv` | 5 | Common |
| 61 | `frontend/resources/static/samples/sample-manifest.json` | 76 | Common |
| 62 | `frontend/resources/static/samples/sample-notes.md` | 15 | Common |
| 63 | `frontend/resources/static/samples/sample-openapi.json` | 51 | Common |
| 64 | `frontend/resources/static/samples/sample-schema.sql` | 17 | Common |
| 65 | `frontend/resources/templates/home.html` | 11 | Common |
| 66 | `frontend/resources/templates/layout.html` | 27 | Common |
| 67 | `frontend/resources/templates/sources.html` | 21 | Common |
| 68 | `frontend/resources/templates/validate.html` | 18 | Common |
| 69 | `frontend/resources/templates/wizard.html` | 3 | Common |
| 70 | `backend/src/main/java/com/example/casc/Main.java` | 20 | Common |
| 71 | `backend/src/main/java/com/example/casc/web/Html.java` | 24 | Common |
| 72 | `backend/src/main/java/com/example/casc/web/Templates.java` | 57 | Common |
| 73 | `backend/src/main/java/com/example/casc/web/WebServer.java` | 925 | Common |
| 74 | `backend/src/main/java/com/example/casc/web/WebSupport.java` | 16 | Common |
| 75 | `backend/src/main/java/com/example/casc/web/Wizard.java` | 1009 | Common |
| 76 | `backend/src/main/java/com/example/casc/cli/Cli.java` | 551 | Common |
| 77 | `backend/src/test/java/com/example/casc/TestKit.java` | 107 | Common |
| 78 | `backend/src/test/java/com/example/casc/TestServer.java` | 89 | Common |
| 79 | `backend/src/test/java/com/example/casc/AppTests.java` | 224 | Common |
| 80 | `backend/src/test/java/com/example/casc/DomainTests.java` | 85 | Common |
| 81 | `backend/src/test/java/com/example/casc/IngestAppTests.java` | 362 | Common |
| 82 | `backend/src/test/java/com/example/casc/OutputTests.java` | 107 | Common |
| 83 | `backend/src/test/java/com/example/casc/TestRunner.java` | 37 | Common |
| 84 | `backend/src/test/java/com/example/casc/ToolTests.java` | 370 | Common |
| 85 | `backend/src/test/java/com/example/casc/ValidatorTests.java` | 122 | Common |
| 86 | `backend/src/test/java/com/example/casc/WizardWebTests.java` | 350 | Common |
| 87 | `backend/src/test/java/com/example/casc/ingest/IngestTests.java` | 453 | Common |
| 88 | `backend/src/test/java/com/example/casc/ingest/OpenApiTests.java` | 143 | Common |
| 89 | `backend/src/test/java/com/example/casc/net/NetTests.java` | 233 | Common |
| 90 | `backend/src/test/java/com/example/casc/wizard/WizardTests.java` | 308 | Common |
| 91 | `.github/workflows/ci.yml` | 64 | Common |
| 92 | `infrastructure/README.md` | 61 | Common |
| 93 | `infrastructure/ci/README.md` | 24 | Common |
| 94 | `infrastructure/ci/build-without-gradle.sh` | 27 | Linux/Git Bash (LF, executable) |
| 95 | `infrastructure/ci/expected-manifest.sha256` | 1 | Common |
| 96 | `infrastructure/ci/verify.sh` | 36 | Linux/Git Bash (LF, executable) |
| 97 | `infrastructure/integrations/claude/casc-manifest-generator/SKILL.md` | 49 | Common |
| 98 | `infrastructure/integrations/copilot/copilot-instructions.md` | 19 | Common |
| 99 | `infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md` | 11 | Common |
| 100 | `infrastructure/integrations/examples/generate_casc_manifest.request.json` | 103 | Common |
| 101 | `infrastructure/integrations/examples/ingest_sources.request.json` | 9 | Common |
| 102 | `infrastructure/integrations/examples/validate_manifest_payload.request.json` | 30 | Common |
| 103 | `infrastructure/launchers/run-cli.bat` | 10 | Windows only (CRLF) |
| 104 | `infrastructure/launchers/run-cli.sh` | 10 | Linux/Git Bash (LF, executable) |
| 105 | `infrastructure/launchers/run-web.bat` | 10 | Windows only (CRLF) |
| 106 | `infrastructure/launchers/run-web.sh` | 10 | Linux/Git Bash (LF, executable) |

`docs/` and `README.md` are optional for the build. The four sample sources under `frontend/resources/static/samples/` and the three example requests under `infrastructure/integrations/examples/` are used by tests, so type them too.
`infrastructure/ci/expected-manifest.sha256` is generated: after everything works, run the `ingest` command shown in `infrastructure/ci/verify.sh` and record its SHA-256.

## Typing method
1. Type one file at a time, in the order above, in chunks of 30-60 lines. Do not compile until a file is complete.
2. Checkpoints: A, B and C are a clean compile (`javac -d out -encoding UTF-8 <files>` or `gradle compileJava`) that prints nothing. D: run the tests; expect `115 passed` (114 passed and 1 skipped without a UTF-8 locale).
3. Common typing mistakes: a missing `}` closing a `switch` or class; `->` typed as `-` `>`; curly quotes instead of straight quotes;
   `{{` `}}` in HTML templates must stay exactly as written; a `.sh` file saved with CRLF endings; the Unicode escapes `\u2192` and `\u2013` in `TextStrategy` typed literally;
   HTML in `Wizard.java` is built from many short strings, so check each quote and `+`; JSON request files must be valid JSON (the tests parse them).

## Commands
Linux: `gradle wrapper && ./gradlew jar unitTests && java -jar build/libs/casc-generator.jar web`
Windows: `gradle.bat wrapper` then `gradlew.bat jar unitTests` then `java -jar build\libs\casc-generator.jar web`
Without Gradle: `bash infrastructure/ci/build-without-gradle.sh`, then `bash infrastructure/ci/verify.sh` with `CASC_JAR=build/plain/casc-generator.jar`.

## Recovery
- Wrong Java: set `JAVA_HOME` to the approved JDK; `options.release = 17` needs JDK 17 or newer.
- `unitTests` task not found: `build.gradle` was typed incompletely; the task is registered near the end of the file.
- A test fails after a typo: the FAIL line names the area; fix the first compile error before looking at test failures.
- Resume after a failed checkpoint: keep the files already typed and fix the first compile error; do not retype earlier files.
- Page shows "No value for {{x}}": a template token was mistyped or a value is missing in `WebServer` or `Wizard`.
- Wizard POST returns 403: the page was opened in another session or the form lacks the hidden `csrf` field.
- `tool` returns INVALID_REQUEST: read the path in the message; unknown fields are rejected on purpose.
- Port in use: `--port 9090` or `CASC_PORT`.
- URL fetch refused: read the message. "not in the URL allowlist" means add the host to `--url-allow` or `CASC_URL_ALLOW`; "prohibited address" cannot be allowed;
  "TLS handshake failed" means the certificate is not trusted, so configure the approved truststore; never disable certificate checks.
- Linux permission denied on launchers or CI scripts: `chmod +x infrastructure/launchers/*.sh infrastructure/ci/*.sh`. `bad interpreter` means CRLF endings; convert with `sed -i 's/\r$//' file`.
- CI hash mismatch: the manifest output changed or a sample source was saved with CRLF. Fix line endings first; only then re-record the hash.
- Windows file locking: close editors or viewers holding the output file; exports use a temp file then move.
- Paths with spaces: quote them (`--input-files "C:\my files\specs"`).
- If your organization later requires internal repositories, add them to `settings.gradle` (`pluginManagement`) and `build.gradle` only when you add a dependency.
