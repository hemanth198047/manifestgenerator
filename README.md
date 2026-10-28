# CasC Multi-Source Manifest Generator

Generates and validates CasC manifests for the Firmwide Data Catalog. The application uses the JDK only:
no Spring, no Jackson, no JavaFX, no libraries of any kind. It runs on Windows and Linux from one jar.

Current phase: 7 of 7 (complete; verification on Gradle and Windows is still yours to run) (validation, JSON and YAML export, browser UI, CLI, and ingestion of CSV, TSV, TXT, Markdown and a SQL subset
into an unreviewed draft manifest; OpenAPI JSON and allowlisted URL sources, and a five-step wizard with reconciliation and project files, see `docs/WIZARD.md`; agent tool contracts, skill files and CI, see `docs/INTEGRATION.md`). Documents (DOCX, PDF), spreadsheets, HTML and YAML need libraries that are not part of this build. See `docs/CAPABILITIES.md` and `docs/INGESTION.md`.

## Layout

| Directory | Contents |
|-----------|----------|
| `backend/` | Java sources (`src/main/java`) and tests (`src/test/java`) |
| `frontend/resources/` | HTML templates, CSS, JavaScript and the sample manifest, packaged onto the classpath |
| `infrastructure/` | Launchers (`.sh`, `.bat`) and deployment notes |
| `docs/` | Decision log, dependency inventory, capability matrix, verification ledger, handbook |

Packages under `com.example.casc`: `domain`, `profile`, `json`, `manifest`, `validation`, `output`, `app`, `web`, `cli`.
The web UI, REST API and CLI all call the same `app` and `validation` code.

## Build and run

Requires an approved JDK 21 and Gradle (Groovy DSL). No repositories are configured because nothing is downloaded.

```
gradle wrapper                      # once, with your approved Gradle (Windows: gradle.bat wrapper)
./gradlew jar unitTests             # Windows: gradlew.bat jar unitTests
java -jar build/libs/casc-generator.jar web            # http://127.0.0.1:8080/
java -jar build/libs/casc-generator.jar validate --input manifest.json
java -jar build/libs/casc-generator.jar export --input manifest.json --output out.yaml --format yaml
java -jar build/libs/casc-generator.jar ingest --input-files ./specs --db-name TRADE_DB --default-schema trade --output ./manifest.json
java -jar build/libs/casc-generator.jar ingest --url https://wiki.corp.example/api/trade.json --url-allow wiki.corp.example --interface-direction DISTRIBUTION --output ./manifest.json
```

Exit codes: 0 ok, 1 validation errors, 2 usage error, 3 I/O or parse failure, 4 internal error.

## Where to look
`docs/FINAL_REPORT.md` (status, final tables, blockers), `docs/BUILD.md` (build and operations), `docs/WINDOWS_VERIFICATION.md`, `docs/HANDBOOK.md` (retyping), `docs/DECISION_LOG.md`.

## Honest status

- Validation is **LOCAL** against a **provisional** profile. It is not checked against the official CasC schema and nothing
  is claimed about acceptance by the FDC service.
- YAML export is a narrow emitter for the manifest structure. YAML cannot be read.
- Gradle itself has not been run on this code yet; see `docs/VERIFICATION.md` for exactly what was executed.
