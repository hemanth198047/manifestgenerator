# CasC Multi-Source Manifest Generator

Generates and validates CasC manifests for the Firmwide Data Catalog. The application uses the JDK only:
no Spring, no Jackson, no JavaFX, no libraries of any kind. It runs on Windows and Linux from one jar.

Current phase: 2 of 7 (validation, JSON and YAML export, browser UI, CLI). Ingestion of SQL, CSV, documents and URLs
arrives in later phases. See `docs/CAPABILITIES.md`.

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

Requires an approved JDK 17+ and Gradle (Groovy DSL). No repositories are configured because nothing is downloaded.

```
gradle wrapper                      # once, with your approved Gradle (Windows: gradle.bat wrapper)
./gradlew jar unitTests             # Windows: gradlew.bat jar unitTests
java -jar build/libs/casc-generator.jar web            # http://127.0.0.1:8080/
java -jar build/libs/casc-generator.jar validate --input manifest.json
java -jar build/libs/casc-generator.jar export --input manifest.json --output out.yaml --format yaml
```

Exit codes: 0 ok, 1 validation errors, 2 usage error, 3 I/O or parse failure, 4 internal error.

## Honest status

- Validation is **LOCAL** against a **provisional** profile. It is not checked against the official CasC schema and nothing
  is claimed about acceptance by the FDC service.
- YAML export is a narrow emitter for the manifest structure. YAML cannot be read.
- Gradle itself has not been run on this code yet; see `docs/VERIFICATION.md` for exactly what was executed.
