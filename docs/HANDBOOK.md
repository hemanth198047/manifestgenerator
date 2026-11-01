# Manual Recreation Handbook (final)

The project has no libraries, so recreating it needs only a JDK, Gradle and an editor.
Do not type generated files: `gradlew`, `gradlew.bat` and `gradle/wrapper/*` come from `gradle wrapper` (see `docs/BUILD.md`); `build/` and `.gradle/` are output.

## Readiness checklist
| Item | Needed |
|------|--------|
| OS | Windows or Linux; no administrator or root rights |
| JDK | Approved JDK 21 with `java.net.http` and `jdk.httpserver` (standard). Check `java -version` and `JAVA_HOME` |
| Gradle | Approved 8.x distribution (or use the JDK-only scripts) |
| Internal repositories, plugins, libraries | None needed |
| Editor | Any that saves UTF-8 with LF endings |
| Network | None to build; allowlisted hosts only for URL features |
| Directory permissions | Write access to the project folder and its `build/` |
| Allowed commands | `java`, `gradle`; Bash and `sha256sum` (or PowerShell) for the CI scripts |
| Transfer restrictions | Everything is plain text typed by hand: no Base64, archives or downloads |

## Size and a recommended way to type it
128 files and about 16302 lines: roughly 10149 lines of main Java, 3538 of test Java and 1303 of optional adapter code and tests, the rest are frontend files, docs, scripts and samples. Plan for several sessions.
Type in this order and stop at each checkpoint:
1. Build files and the `domain`, `profile`, `json`, `validation`, `manifest` and `output` packages: the core. Checkpoint A: compile.
2. `ingest`, `net`, `wizard`, `agent` and `app`: the engines. Checkpoint B: compile.
3. `web`, `cli`, `Main.java` and the `frontend/` files. Checkpoint C: compile and start the web UI.
4. Tests last, in the order listed. Checkpoint D: run the test runner.
5. Optional: `infrastructure/` scripts, integration files and CI.
6. Optional and last: the four adapters under `backend/src/adapters/` (they are compiled only when enabled, see `docs/ADAPTERS.md`; the default build never needs them). Type one adapter at a time with its tests; `build.gradle` itself is part of step 1 and already contains the adapter switch. Tests that read `infrastructure/` report SKIP when it is absent.
You can run the application after checkpoint C without the tests, and add tests file by file (the runner registers them in `TestRunner.java`; leave a test class out and comment out its `register()` line).
A small generator script was considered and rejected: the files are not repetitive enough for it to save typing, and a second tool would need its own review.

## File inventory (create in this order)
| Seq | Path | Lines | OS |
|-----|------|-------|----|
| 1 | `settings.gradle` | 3 | Common |
| 2 | `build.gradle` | 179 | Common |
| 3 | `.gitignore` | 33 | Common |
| 4 | `.gitattributes` | 5 | Common |
| 5 | `.editorconfig` | 11 | Common |
| 6 | `backend/src/main/java/com/example/casc/domain/CasCManifest.java` | 57 | Common |
| 7 | `backend/src/main/java/com/example/casc/domain/Enums.java` | 56 | Common |
| 8 | `backend/src/main/java/com/example/casc/profile/SchemaProfile.java` | 18 | Common |
| 9 | `backend/src/main/java/com/example/casc/json/Json.java` | 385 | Common |
| 10 | `backend/src/main/java/com/example/casc/json/JsonException.java` | 9 | Common |
| 11 | `backend/src/main/java/com/example/casc/validation/CasCValidatorService.java` | 327 | Common |
| 12 | `backend/src/main/java/com/example/casc/validation/Finding.java` | 30 | Common |
| 13 | `backend/src/main/java/com/example/casc/validation/Severity.java` | 3 | Common |
| 14 | `backend/src/main/java/com/example/casc/validation/ValidationReport.java` | 45 | Common |
| 15 | `backend/src/main/java/com/example/casc/manifest/ManifestMapper.java` | 564 | Common |
| 16 | `backend/src/main/java/com/example/casc/output/JsonManifestSerializer.java` | 18 | Common |
| 17 | `backend/src/main/java/com/example/casc/output/ManifestSerializer.java` | 15 | Common |
| 18 | `backend/src/main/java/com/example/casc/output/YamlManifestSerializer.java` | 139 | Common |
| 19 | `backend/src/main/java/com/example/casc/ingest/ConfluenceJson.java` | 41 | Common |
| 20 | `backend/src/main/java/com/example/casc/ingest/Csv.java` | 143 | Common |
| 21 | `backend/src/main/java/com/example/casc/ingest/DelimitedStrategy.java` | 32 | Common |
| 22 | `backend/src/main/java/com/example/casc/ingest/Dictionary.java` | 155 | Common |
| 23 | `backend/src/main/java/com/example/casc/ingest/DraftBuilder.java` | 150 | Common |
| 24 | `backend/src/main/java/com/example/casc/ingest/IngestOptions.java` | 114 | Common |
| 25 | `backend/src/main/java/com/example/casc/ingest/IngestRequest.java` | 113 | Common |
| 26 | `backend/src/main/java/com/example/casc/ingest/InputFiles.java` | 130 | Common |
| 27 | `backend/src/main/java/com/example/casc/ingest/OpenApiStrategy.java` | 318 | Common |
| 28 | `backend/src/main/java/com/example/casc/ingest/RawMetadataBundle.java` | 247 | Common |
| 29 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionService.java` | 236 | Common |
| 30 | `backend/src/main/java/com/example/casc/ingest/SourceIngestionStrategy.java` | 13 | Common |
| 31 | `backend/src/main/java/com/example/casc/ingest/SourceInput.java` | 65 | Common |
| 32 | `backend/src/main/java/com/example/casc/ingest/SqlDmlAnalyzer.java` | 20 | Common |
| 33 | `backend/src/main/java/com/example/casc/ingest/SqlStrategy.java` | 880 | Common |
| 34 | `backend/src/main/java/com/example/casc/ingest/SqlTokenizer.java` | 201 | Common |
| 35 | `backend/src/main/java/com/example/casc/ingest/Tabular.java` | 170 | Common |
| 36 | `backend/src/main/java/com/example/casc/ingest/TextStrategy.java` | 220 | Common |
| 37 | `backend/src/main/java/com/example/casc/net/HttpUrlFetcher.java` | 247 | Common |
| 38 | `backend/src/main/java/com/example/casc/net/UrlFetcher.java` | 36 | Common |
| 39 | `backend/src/main/java/com/example/casc/net/UrlPolicy.java` | 86 | Common |
| 40 | `backend/src/main/java/com/example/casc/net/UrlSources.java` | 99 | Common |
| 41 | `backend/src/main/java/com/example/casc/wizard/AssetKeys.java` | 27 | Common |
| 42 | `backend/src/main/java/com/example/casc/wizard/ManifestAssembler.java` | 199 | Common |
| 43 | `backend/src/main/java/com/example/casc/wizard/NameSuggester.java` | 57 | Common |
| 44 | `backend/src/main/java/com/example/casc/wizard/PiiHints.java` | 31 | Common |
| 45 | `backend/src/main/java/com/example/casc/wizard/ProjectFile.java` | 326 | Common |
| 46 | `backend/src/main/java/com/example/casc/wizard/Reconciler.java` | 160 | Common |
| 47 | `backend/src/main/java/com/example/casc/wizard/SessionStore.java` | 100 | Common |
| 48 | `backend/src/main/java/com/example/casc/wizard/SystemKeys.java` | 29 | Common |
| 49 | `backend/src/main/java/com/example/casc/wizard/WizardService.java` | 111 | Common |
| 50 | `backend/src/main/java/com/example/casc/wizard/WizardState.java` | 177 | Common |
| 51 | `backend/src/main/java/com/example/casc/agent/AgentTools.java` | 152 | Common |
| 52 | `backend/src/main/java/com/example/casc/agent/BundleReader.java` | 127 | Common |
| 53 | `backend/src/main/java/com/example/casc/agent/Fields.java` | 90 | Common |
| 54 | `backend/src/main/java/com/example/casc/agent/ToolException.java` | 16 | Common |
| 55 | `backend/src/main/java/com/example/casc/agent/UserInputs.java` | 247 | Common |
| 56 | `backend/src/main/java/com/example/casc/app/Capability.java` | 17 | Common |
| 57 | `backend/src/main/java/com/example/casc/app/CapabilityService.java` | 82 | Common |
| 58 | `backend/src/main/java/com/example/casc/app/CapabilityUnavailableException.java` | 10 | Common |
| 59 | `backend/src/main/java/com/example/casc/app/DraftPipeline.java` | 66 | Common |
| 60 | `backend/src/main/java/com/example/casc/app/ExportResult.java` | 8 | Common |
| 61 | `backend/src/main/java/com/example/casc/app/ExportService.java` | 91 | Common |
| 62 | `backend/src/adapters/jsoup/java/com/example/casc/adapters/jsoup/HtmlStrategy.java` | 158 | Common |
| 63 | `backend/src/adapters/jsqlparser/java/com/example/casc/adapters/jsqlparser/JSqlParserAnalyzer.java` | 95 | Common |
| 64 | `backend/src/adapters/pdfbox/java/com/example/casc/adapters/pdfbox/PdfStrategy.java` | 107 | Common |
| 65 | `backend/src/adapters/poi/java/com/example/casc/adapters/poi/DocxExtractor.java` | 86 | Common |
| 66 | `backend/src/adapters/poi/java/com/example/casc/adapters/poi/DocxModel.java` | 19 | Common |
| 67 | `backend/src/adapters/poi/java/com/example/casc/adapters/poi/DocxReader.java` | 83 | Common |
| 68 | `backend/src/adapters/poi/java/com/example/casc/adapters/poi/PoiStrategy.java` | 221 | Common |
| 69 | `backend/src/adapters/jsoup/test/java/com/example/casc/adapters/jsoup/HtmlTests.java` | 86 | Common |
| 70 | `backend/src/adapters/jsqlparser/test/java/com/example/casc/adapters/jsqlparser/JSqlParserTests.java` | 66 | Common |
| 71 | `backend/src/adapters/pdfbox/test/java/com/example/casc/adapters/pdfbox/PdfTests.java` | 100 | Common |
| 72 | `backend/src/adapters/poi/test/java/com/example/casc/adapters/poi/PoiDocxTests.java` | 46 | Common |
| 73 | `backend/src/adapters/poi/test/java/com/example/casc/adapters/poi/PoiTests.java` | 236 | Common |
| 74 | `frontend/resources/static/css/app.css` | 37 | Common |
| 75 | `frontend/resources/static/js/app.js` | 113 | Common |
| 76 | `frontend/resources/static/samples/sample-dictionary.csv` | 5 | Common |
| 77 | `frontend/resources/static/samples/sample-manifest.json` | 76 | Common |
| 78 | `frontend/resources/static/samples/sample-notes.md` | 15 | Common |
| 79 | `frontend/resources/static/samples/sample-openapi.json` | 51 | Common |
| 80 | `frontend/resources/static/samples/sample-schema.sql` | 17 | Common |
| 81 | `frontend/resources/templates/home.html` | 13 | Common |
| 82 | `frontend/resources/templates/layout.html` | 27 | Common |
| 83 | `frontend/resources/templates/sources.html` | 22 | Common |
| 84 | `frontend/resources/templates/validate.html` | 18 | Common |
| 85 | `frontend/resources/templates/wizard.html` | 3 | Common |
| 86 | `backend/src/main/java/com/example/casc/Main.java` | 20 | Common |
| 87 | `backend/src/main/java/com/example/casc/web/Html.java` | 24 | Common |
| 88 | `backend/src/main/java/com/example/casc/web/Templates.java` | 57 | Common |
| 89 | `backend/src/main/java/com/example/casc/web/WebServer.java` | 941 | Common |
| 90 | `backend/src/main/java/com/example/casc/web/WebSupport.java` | 16 | Common |
| 91 | `backend/src/main/java/com/example/casc/web/Wizard.java` | 1009 | Common |
| 92 | `backend/src/main/java/com/example/casc/cli/Cli.java` | 577 | Common |
| 93 | `backend/src/test/java/com/example/casc/TestKit.java` | 107 | Common |
| 94 | `backend/src/test/java/com/example/casc/TestServer.java` | 94 | Common |
| 95 | `backend/src/test/java/com/example/casc/AccessAudit.java` | 177 | Common |
| 96 | `backend/src/test/java/com/example/casc/AccessTests.java` | 50 | Common |
| 97 | `backend/src/test/java/com/example/casc/AppTests.java` | 232 | Common |
| 98 | `backend/src/test/java/com/example/casc/DomainTests.java` | 85 | Common |
| 99 | `backend/src/test/java/com/example/casc/FuzzTests.java` | 138 | Common |
| 100 | `backend/src/test/java/com/example/casc/IngestAppTests.java` | 406 | Common |
| 101 | `backend/src/test/java/com/example/casc/OutputTests.java` | 107 | Common |
| 102 | `backend/src/test/java/com/example/casc/SplitLoaderRunner.java` | 44 | Common |
| 103 | `backend/src/test/java/com/example/casc/TestRunner.java` | 60 | Common |
| 104 | `backend/src/test/java/com/example/casc/ToolTests.java` | 412 | Common |
| 105 | `backend/src/test/java/com/example/casc/ValidatorTests.java` | 122 | Common |
| 106 | `backend/src/test/java/com/example/casc/WizardWebTests.java` | 348 | Common |
| 107 | `backend/src/test/java/com/example/casc/ingest/IngestTests.java` | 467 | Common |
| 108 | `backend/src/test/java/com/example/casc/ingest/OpenApiTests.java` | 143 | Common |
| 109 | `backend/src/test/java/com/example/casc/net/NetTests.java` | 242 | Common |
| 110 | `backend/src/test/java/com/example/casc/wizard/WizardTests.java` | 308 | Common |
| 111 | `.github/workflows/ci.yml` | 64 | Common |
| 112 | `infrastructure/README.md` | 66 | Common |
| 113 | `infrastructure/ci/README.md` | 24 | Common |
| 114 | `infrastructure/ci/build-without-gradle.ps1` | 88 | Windows (PowerShell) |
| 115 | `infrastructure/ci/build-without-gradle.sh` | 98 | Linux/Git Bash (LF, executable) |
| 116 | `infrastructure/ci/expected-manifest.sha256` | 1 | Common |
| 117 | `infrastructure/ci/verify.ps1` | 46 | Windows (PowerShell) |
| 118 | `infrastructure/ci/verify.sh` | 36 | Linux/Git Bash (LF, executable) |
| 119 | `infrastructure/integrations/claude/casc-manifest-generator/SKILL.md` | 49 | Common |
| 120 | `infrastructure/integrations/copilot/copilot-instructions.md` | 19 | Common |
| 121 | `infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md` | 11 | Common |
| 122 | `infrastructure/integrations/examples/generate_casc_manifest.request.json` | 103 | Common |
| 123 | `infrastructure/integrations/examples/ingest_sources.request.json` | 9 | Common |
| 124 | `infrastructure/integrations/examples/validate_manifest_payload.request.json` | 30 | Common |
| 125 | `infrastructure/launchers/run-cli.bat` | 10 | Windows only (CRLF) |
| 126 | `infrastructure/launchers/run-cli.sh` | 10 | Linux/Git Bash (LF, executable) |
| 127 | `infrastructure/launchers/run-web.bat` | 10 | Windows only (CRLF) |
| 128 | `infrastructure/launchers/run-web.sh` | 10 | Linux/Git Bash (LF, executable) |

Handwritten files are the ones above. Generated or output, not typed: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `build/`, `.gradle/`, and `infrastructure/ci/expected-manifest.sha256`
(record it after everything works with the `ingest` command shown in `infrastructure/ci/verify.sh`). `docs/` and `README.md` are optional for the build. The four sample sources under
`frontend/resources/static/samples/` and the three example requests under `infrastructure/integrations/examples/` are used by tests, so type them too.

## Typing method
1. Type one file at a time, in the order above, in chunks of 30-60 lines (chunk markers are yours: stop at a closed block). Do not type line numbers. Do not compile until a file is complete.
2. Checkpoints A, B and C are a clean compile (`javac -d out -encoding UTF-8 <files>` or `gradlew compileJava`) that prints nothing. D: run the tests; expect `125 passed` (124 passed and 1 skipped without a UTF-8 locale).
3. Common typing mistakes: a missing `}` closing a `switch` or class; `->` typed as `-` `>`; curly quotes instead of straight quotes;
   `{{` `}}` in HTML templates must stay exactly as written; a `.sh` file saved with CRLF endings; the Unicode escapes `\u2192` and `\u2013` in `TextStrategy` typed literally;
   HTML in `Wizard.java` and `WebServer.java` is built from many short strings, so check each quote and `+`; JSON request and sample files must be valid JSON (the tests parse them); records, pattern matching and text blocks need the JDK 21 compiler.
4. Expected results: A, B and C print nothing; D prints one `PASS` line per test and `125 passed, 0 failed`. Then run the same tests with main and test in separate class loaders (`bash infrastructure/ci/build-without-gradle.sh` does both): `SplitLoaderRunner` must also report `125 passed`.
5. `AccessAudit` (a test) lists any class, method or field of the main code that a test uses without it being `public`. If you see `IllegalAccessError` or "is not public in ...; cannot be accessed from outside package", a class or method that tests use is missing `public`: compare it with the repository version.

## Commands
See `docs/BUILD.md` for the full Linux and Windows table. In short:
Linux: `gradle wrapper ... && ./gradlew jar unitTests && java -jar build/libs/casc-generator.jar web`
Windows: `gradle.bat wrapper ...` then `gradlew.bat jar unitTests` then `java -jar build\libs\casc-generator.jar web`
Without Gradle: `bash infrastructure/ci/build-without-gradle.sh` or `powershell -File infrastructure\ci\build-without-gradle.ps1`.

## Recovery
- Wrong Java: set `JAVA_HOME` to the approved JDK; `options.release = 21` needs JDK 21 or newer. A toolchain error means Gradle runs on an older JDK.
- Wrapper unavailable or cannot download: generate it from your approved Gradle with the internal `distributionUrl` and checksum (`docs/BUILD.md`); never point it at an unapproved URL.
- Plugin or dependency resolution errors: there should be none (no repositories are configured). If you see them, `build.gradle` or `settings.gradle` was typed with an extra block.
- `unitTests` task not found, or a Groovy syntax error: compare `build.gradle` with the repository version; every brace and quote must match.
- A test fails: the report shows the file and line; a CLI assertion also shows the exit code with the command's stdout and stderr, and `-Dcasc.test.trace=true` adds the full stack trace. The tests ignore your CASC_* environment variables.
- A test fails after a typo: the FAIL line names the area; fix the first compile error before looking at test failures.
- Resume after a failed checkpoint: keep the files already typed and fix the first compile error; do not retype earlier files. Run only the compile step until it prints nothing.
- Missing optional capability: expected. XLSX, DOCX, PDF, HTML and SQL DML lineage are optional adapters that are off by default; YAML input and MCP are unavailable (`docs/ADAPTERS.md`, `docs/FINAL_REPORT.md`).
- Adapter build errors: the libraries must be on the class path (`-PadapterLibDir` or `ADAPTER_LIB_DIR`); a missing library shows up as an "Adapter problem" in `capabilities`. `PoiDocxTests` needs POI's full OOXML schema classes to compile; leave it out with `SKIP_TEST_FILES` if your jars lack them.
- Corporate proxy or truststore: `JAVA_OPTS` with `-Djavax.net.ssl.trustStore=...` and `-Dhttps.proxyHost=...`; never disable certificate checks.
- Page shows "No value for {{x}}": a template token was mistyped or a value is missing in `WebServer` or `Wizard`.
- Wizard POST returns 403: the page was opened in another session or the form lacks the hidden `csrf` field.
- `tool` returns INVALID_REQUEST: read the path in the message; unknown fields are rejected on purpose.
- Port in use: `--port 9090` or `CASC_PORT`.
- URL fetch refused: "not in the URL allowlist" means add the host to `--url-allow` or `CASC_URL_ALLOW`; "prohibited address" cannot be allowed; "TLS handshake failed" means configure the approved truststore.
- File casing: class names must match file names exactly (`WebServer.java`); Linux is case-sensitive.
- Linux permission denied on launchers or CI scripts: `chmod +x infrastructure/launchers/*.sh infrastructure/ci/*.sh`. `bad interpreter` means CRLF endings; convert with `sed -i 's/\r$//' file`.
- A docs or CI-hash test fails only on Windows: files were saved with CRLF. The tests tolerate CRLF and a byte-order mark and the application normalizes line breaks, but keep LF anyway (`.editorconfig` and `.gitattributes` ask for it).
- CRLF in shell scripts or samples after a Windows checkout: `.gitattributes` forces LF; if you typed files by hand, save them with LF.
- CI hash mismatch: the manifest output changed or a sample source was saved with CRLF. Fix line endings first; only then re-record the hash.
- Windows file locking: close editors or viewers holding the output file; exports use a temp file then move.
- Paths with spaces: quote them (`--input-files "C:\my files\specs"`).
- Port conflicts, firewall prompts: the server binds to 127.0.0.1 by default; choose another `--port`.
- If your organization later requires internal repositories, add them to `settings.gradle` (`pluginManagement`) and `build.gradle` only when you add a dependency (`docs/BUILD.md`).
