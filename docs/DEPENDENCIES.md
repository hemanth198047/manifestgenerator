# Dependency Inventory (final)

Approval status and internal availability are UNKNOWN until you supply them. Nothing here is a vulnerability scan or a license review.

| Item | Version | Purpose | Required | Transitives | Availability | Approval |
|------|---------|---------|----------|-------------|--------------|----------|
| JDK (modules `java.base`, `java.net.http`, `jdk.httpserver`) | 21 (chosen) | Compile and run | Yes | n/a | Unknown | Unknown |
| Gradle | 8.x (provisional) | Build | Yes (or use the JDK-only scripts) | n/a | Unknown | Unknown |
| Gradle `java` plugin | built in | Compile, jar | Yes | none | Part of Gradle | Follows Gradle |
| Bash and `sha256sum`, or PowerShell | any | CI verification scripts only | No | n/a | Unknown | Unknown |

The **default build** has no libraries, no third-party Gradle plugins, no annotation processors, no native code, no repositories and no external services. `build.gradle` configures a repository or a jar directory only when you enable an adapter, and only from your approved sources.
Dependency locking and verification metadata are therefore not needed; see `docs/BUILD.md` for what to do if a library is ever added.
The optional CI workflow uses no marketplace actions; it relies on the runner image's JDK and Gradle.

## Optional libraries (adapters; none is in the default build)
Each is enabled only by `-Padapters=...` or `ADAPTERS=...` (see `docs/ADAPTERS.md`, which lists transitive libraries and what was verified). Approval status is unknown for all of them; licenses need your review.
| Capability | Library | Adapter | Provisional version | Status |
|------------|---------|---------|---------------------|--------|
| XLSX, XLS, DOCX | Apache POI | `poi` | 5.2.5 | Optional; .xls verified against 4.0.1, .xlsx/.docx not run |
| PDF text | Apache PDFBox | `pdfbox` | 3.0.2 | Optional; verified against 2.0.29 |
| HTML, Confluence | jsoup | `jsoup` | 1.17.2 | Optional; verified against 1.15.3 |
| SQL DML lineage (table level) | JSqlParser | `jsqlparser` | 4.9 | Optional; run against JSqlParser 4.9 built from the `jsqlparser-4.9` source tag with javacc 7.0.12 (the published artifact was not obtainable); 5.x not run |

## Capabilities that still need something not supplied
| Capability | Needs | Status |
|------------|-------|--------|
| YAML parsing (OpenAPI YAML, YAML manifests) | A YAML parser such as Jackson YAML | Not supplied |
| Official schema validation | A schema validator and the official schema | Not supplied |
| JavaFX desktop UI | OpenJFX plus native libraries | Not supplied |
| MCP server | An approved MCP host or SDK | Not supplied |
