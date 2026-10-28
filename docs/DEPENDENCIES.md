# Dependency Inventory (final)

Approval status and internal availability are UNKNOWN until you supply them. Nothing here is a vulnerability scan or a license review.

| Item | Version | Purpose | Required | Transitives | Availability | Approval |
|------|---------|---------|----------|-------------|--------------|----------|
| JDK (modules `java.base`, `java.net.http`, `jdk.httpserver`) | 17 or newer (provisional) | Compile and run | Yes | n/a | Unknown | Unknown |
| Gradle | 8.x (provisional) | Build | Yes (or use the JDK-only scripts) | n/a | Unknown | Unknown |
| Gradle `java` plugin | built in | Compile, jar | Yes | none | Part of Gradle | Follows Gradle |
| Bash and `sha256sum`, or PowerShell | any | CI verification scripts only | No | n/a | Unknown | Unknown |

There are no libraries, no third-party Gradle plugins, no annotation processors, no native code, no repositories and no external services. `settings.gradle` and `build.gradle` configure none.
Dependency locking and verification metadata are therefore not needed; see `docs/BUILD.md` for what to do if a library is ever added.
The optional CI workflow uses no marketplace actions; it relies on the runner image's JDK and Gradle.

## Libraries that would enable blocked features (none is in the build)
| Capability | Library | Why the JDK is not enough | Status |
|------------|---------|---------------------------|--------|
| XLSX, DOCX | Apache POI (with its transitives) | OOXML needs a full reader; raw ZIP/XML scraping is not equivalent | Not approved / not used |
| PDF text | Apache PDFBox | PDF text extraction is not feasible with the JDK | Not approved / not used |
| HTML, Confluence pages | JSoup or an equivalent | A reliable HTML parser is a large component | Not approved / not used |
| YAML parsing (OpenAPI YAML, YAML manifests) | A YAML parser such as Jackson YAML | General YAML is complex; only a narrow emitter was written | Not approved / not used |
| General SQL, DML lineage | JSqlParser or an equivalent | A restricted DDL reader was written instead | Not approved / not used |
| Official schema validation | A JSON Schema validator | The official schema has not been supplied | Not approved / not used |
| JavaFX desktop UI | OpenJFX plus native libraries | Not part of the JDK; needs a desktop session | Not approved / not used |
