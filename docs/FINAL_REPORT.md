# Final report

Status in one paragraph: the application is complete to the scope below and runs on JDK 21 with no libraries. It was built, tested and run on **Linux only** (123 tests, plus a 1.2 million input fuzz campaign).
**Gradle was never executed** against `build.gradle`, and **Windows was never executed**; both are for you to verify (`docs/WINDOWS_VERIFICATION.md`). Validation is **LOCAL against a provisional profile**:
nothing in this repository claims validity against the official CasC schema or acceptance by the FDC service.

## Environment actually used
Ubuntu 24.04, x86_64, OpenJDK 21.0.10 (runtime with the `jdk.compiler` and `jdk.jartool` modules; no `javac` or `jar` binaries), no Gradle. Builds used `java -m jdk.compiler/com.sun.tools.javac.Main`
and `java -m jdk.jartool/sun.tools.jar.Main` (the same tools the scripts use through override variables). Locales: `C.UTF-8` (all tests) and POSIX (one test skips). No Windows, no browser, no Gradle, no GitHub runner.

## Acceptance criteria (section 18 of the original prompt)
| Criterion | Status | Evidence and notes |
|-----------|--------|--------------------|
| A working Gradle application | **Not verified** | The application is verified with the JDK compiler and a plain jar. `build.gradle` is short and conventional but has never been run by Gradle. |
| Native Windows and Linux execution | **Linux verified; Windows not** | Cross-platform code paths are reviewed and tested for Windows-style names; nothing was run on Windows. |
| Minimal baseline dependency graph | Met | Zero libraries, zero plugins. |
| Explicit isolation of optional dependencies | Met trivially, scope reduced | There are no optional dependencies; features that needed them are reported as unavailable (see "Blocked features"). |
| Approved internal repository configuration | Met by design | No repository is needed; `docs/BUILD.md` shows how to add internal ones with environment credentials. |
| No mandatory public internet after provisioning | Met | Nothing is downloaded. |
| No mandatory Node, Python, Docker, database or external AI service | Met | |
| JSON manifest generation | Met | Stable order, LF, UTF-8, round-trip checked. |
| YAML capability with stated approval requirements | Met for output | Narrow emitter; YAML cannot be read (needs an approved parser). |
| Supported ingestion capabilities and honest limitations | Met | `docs/INGESTION.md`; unsupported inputs are errors with an alternative. |
| Shared core logic for UI, CLI and agents | Met | One `app`, `wizard` and `agent` layer. |
| Structured validation | Met | Rule code, severity, path, message, correction. |
| Reproducible build instructions | Met on paper | Not run through Gradle. |
| Dependency inventory | Met | `docs/DEPENDENCIES.md`. |
| Cross-platform verification evidence | **Partial** | Linux only; a byte-identical output hash and scripts are in place for you to confirm Windows. |
| Complete manual recreation instructions | Met | `docs/HANDBOOK.md` (file order, line counts, checkpoints, recovery). |

## Table 1: dependency and approval status
| Item | Version | Required | Availability | Approval |
|------|---------|----------|--------------|----------|
| JDK | 21 (chosen by you) | Yes | Unknown | Unknown |
| Gradle | 8.x (provisional) | Yes, or the JDK-only scripts | Unknown | Unknown |
| Gradle `java` plugin | built in | Yes | with Gradle | follows Gradle |
| Libraries, third-party plugins, repositories | none | none | n/a | n/a |
| Bash with `sha256sum`, or PowerShell | any | CI scripts only | Unknown | Unknown |

## Table 2: baseline and optional capabilities
| Capability | Dependency | Status | When unavailable | Verification |
|------------|------------|--------|------------------|--------------|
| JSON manifest import, validation, export | none | Baseline | n/a | Tests (Linux) |
| YAML manifest export | none (narrow emitter) | Baseline | n/a | Tests (Linux); not read back |
| YAML manifest or OpenAPI YAML import | YAML parser | Unavailable | Convert to JSON | n/a |
| CSV, TSV, TXT, Markdown, SQL subset, OpenAPI JSON ingestion | none | Baseline | Findings per source | Tests and fuzzing (Linux) |
| URL sources | none | Baseline, off until hosts are allowlisted | Clear message per URL | Tests (Linux); TLS manually checked |
| XLSX, DOCX | Apache POI | Unavailable | Export to CSV, TSV or text | n/a |
| PDF | Apache PDFBox | Unavailable | Provide a text export | n/a |
| HTML, Confluence pages | HTML parser | Unavailable | Markdown, text or CSV export | n/a |
| SQL DML and transformation lineage | SQL parser | Unavailable | Reported, not analysed | n/a |
| Browser wizard (5 steps), project files | none | Baseline | n/a | Tests (Linux); no real browser |
| Agent tool contracts (CLI and HTTP) | none | Baseline | Strict error codes | Tests (Linux) |
| Claude and Copilot instruction files | none | Supplied | n/a | Consistency tests; not tried in an agent |
| MCP server | approved MCP host or SDK | Not implemented, pending approval | Use CLI or HTTP | n/a |
| JavaFX desktop UI | OpenJFX | Unavailable | Use the browser UI | n/a |
| Official schema validation, FDC acceptance | schema, validator, service | Unavailable | LOCAL rules only | n/a |

## Table 3: Windows and Linux commands and verification
| Task | Linux command | Windows command | Linux | Windows |
|------|---------------|-----------------|-------|---------|
| Build and test with Gradle | `./gradlew jar unitTests` | `gradlew.bat jar unitTests` | **Not run** | **Not run** |
| Build and test without Gradle | `bash infrastructure/ci/build-without-gradle.sh` | `powershell -File infrastructure\ci\build-without-gradle.ps1` | Run: 123 tests pass | **Not run** (script never executed) |
| Verify a jar and the output hash | `bash infrastructure/ci/verify.sh` | `powershell -File infrastructure\ci\verify.ps1` | Run: passed | **Not run** |
| Web UI | `java -jar build/libs/casc-generator.jar web` | `java -jar build\libs\casc-generator.jar web` | Run: pages, wizard, API checked with HTTP | **Not run** |
| CLI draft | `... ingest --input-files ./specs --db-name DB --default-schema s --output m.json` | same with backslashes | Run | **Not run** |
| Launchers | `infrastructure/launchers/run-cli.sh`, `run-web.sh` | `run-cli.bat`, `run-web.bat` | Run (spaces in path, exit codes) | **Not run** |
| Equivalent output | SHA-256 `dc6bdaf5...957806e` | must match | Recorded | **Awaiting** |
| GitHub workflow | n/a | n/a | **Not run** | **Not run** |

## Table 4: remaining blockers
| # | Blocker | Kind | Needed from you |
|---|---------|------|-----------------|
| 1 | Gradle has never run `build.gradle` | Verification | Run `gradlew jar unitTests`; report any error |
| 2 | Windows is unverified | Verification | Run `docs/WINDOWS_VERIFICATION.md`; the PowerShell scripts have never been executed |
| 3 | Approved JDK, Gradle and OS versions are unknown | Approval | JDK 21 is chosen; confirm the exact approved JDK build, Gradle (8.x is a placeholder) and OS versions |
| 4 | Official CasC Technical Reference and accepted examples | Specification | Placement of EON ID and system name; systemKey scope; exact enum spellings; PII catalog; `sourcedFromColumns` shape; database name rules. All are provisional (`docs/DECISION_LOG.md`) |
| 5 | Official schema and an approved validator | Specification | Without them validation stays LOCAL |
| 6 | FDC acceptance evidence | Verification | A real upload to Portal, Train or the CasC Dropbox |
| 7 | Libraries for POI, PDFBox, HTML, YAML and SQL parsing | Approval | Approval and internal availability if those formats matter |
| 8 | MCP host or SDK | Approval | A decision; see `docs/INTEGRATION.md` |
| 9 | Authentication or SSO for shared deployment | Deployment | The web UI has none; keep loopback or add approved access control |
| 10 | Real-world samples (SQL from your databases, OpenAPI from your gateways, a Confluence export) | Verification | Dialect support was checked with one synthetic sample per dialect |
| 11 | Copilot file locations confirmed only from secondary sources | Verification | Check against your Copilot version |
| 12 | Real browser, proxy, internal CA and load testing | Verification | None of these were exercised |

## Blocked features and what unblocks them
| Feature | Reason | Minimum established dependency | Alternative workflow today | Work after approval |
|---------|--------|--------------------------------|----------------------------|---------------------|
| XLSX and DOCX ingestion | OOXML needs a full reader | Apache POI | Export sheets to CSV/TSV, documents to text or Markdown | Add an adapter behind a Gradle property and source set; tests with generated fixtures |
| PDF ingestion | No JDK PDF text extraction; tables are not reconstructable reliably | Apache PDFBox | Text export plus CSV tables | Adapter and limitations text; require review for ambiguous layouts |
| HTML and Confluence | No reliable HTML parser in the JDK | JSoup | Markdown, text or CSV export, or a plain-text endpoint | Adapter for HTML bodies |
| YAML input and OpenAPI YAML | General YAML parsing is large | An approved YAML parser | Convert to JSON | Wire the parser into the readers |
| SQL DML and lineage | Needs a real SQL parser and semantic analysis | JSqlParser or similar | Document lineage separately; DML is reported | New strategy plus lineage model once `sourcedFromColumns` is confirmed |
| Official schema validation | Schema not supplied | A schema validator library | LOCAL checks | Add the validator and the official schema file |
| JavaFX UI | Not in the JDK; needs a desktop | OpenJFX and native libraries | Browser UI | Optional module over the same services |
| MCP | Protocol not implemented | An approved host or SDK | CLI and HTTP contracts | Stdio adapter over the existing tools |
| OCR for scanned PDFs | Native engine | An OCR engine | None | Not planned |

Scope changes from the original prompt, all at your request or because of it: the Spring Boot, Thymeleaf and Jackson baseline was replaced by a JDK-only application, JUnit by a built-in runner, and the optional
capability builds were not needed because no optional library was added. A generator script for typing the project was not written (it would not reduce repetition meaningfully).

## Delivery ledger
| Phase | Commit | Content |
|-------|--------|---------|
| 1 | 6f7f234, a6c1802 | Build baseline, domain model, directory separation |
| 2 | b1cd2e1 | JDK-only rebuild, validation, JSON/YAML export, browser UI, CLI |
| 3 | 74f6efe, 07f165b | CSV/TSV, text, SQL subset, draft manifest, Sources page |
| 4 | f3c37a8 | URL sources, OpenAPI JSON, interface drafting |
| 5 | 51b24b3 | Five-step wizard, reconciliation, sessions, project files |
| 6 | a861a20 | Agent contracts, skill files, CI scripts, optional workflow |
| 7 | see `git log` | Final review fixes, fuzzing, PowerShell scripts, build guide, final report |

## Resuming
Read `docs/DECISION_LOG.md` (status of every assumption) and `docs/VERIFICATION.md` (what was run). Run the plain build (`infrastructure/ci/build-without-gradle.sh`) to see all tests pass, then the Gradle and Windows steps.
