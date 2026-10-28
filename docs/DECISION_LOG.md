# Decision Log

Status: C = confirmed, P = provisional, U = unverified, X = conflict, A = architecture decision.

| ID | Status | Item | Notes |
|----|--------|------|-------|
| D-001 | P | Root manifest has six sections (logicalServices, systemDataElements, interfaces, dataEntities, databases, transformations) | From the project prompt |
| D-002 | U | Placement of systemEonId and system name | Not in the manifest model |
| D-003 | P | systemKey unique across the whole manifest | Rule `KEY_DUP`; pending official scope |
| D-004 | P | Enum spellings: Transaction/Reference, Yes/No, Third Party, SOURCING/DISTRIBUTION, database type list | Exact, case-sensitive; verify capitalization |
| D-005 | U | Approved PII names | Any non-blank name accepted; no catalog assumed |
| D-006 | U | Database logicalName (TAI) rules | Only a whitespace warning (`DB_NAME_PROVISIONAL`) |
| D-007 | U | sourcedFromColumns shape | Carried as opaque maps, never synthesized; `LINEAGE_UNVERIFIED` info |
| D-008 | P | Validation level is LOCAL only | No official schema, no FDC acceptance evidence |
| D-009 | A | List order in output follows input order | Deterministic ordering is the job of ingestion/reconciliation (Phase 3+) |
| D-010 | A | Zero third-party dependencies | Replaces the earlier Spring Boot/Thymeleaf/Jackson plan at the owner's request. Also removes the need for plugin and dependency repositories |
| D-011 | A | UI is a browser UI on the JDK HTTP server, not JavaFX | JavaFX is not in the JDK, needs OpenJFX modules plus native libraries, and needs a desktop session. A JavaFX front end can be added later as an optional, separately approved module over the same `app` services |
| D-012 | A | Tests use a built-in runner, not JUnit | JUnit is a dependency. Run with `gradlew unitTests` |
| D-013 | A | JSON uses an in-house strict parser/writer | RFC 8259 grammar is small and fully covered by tests. Rejects duplicate keys and deep nesting |
| D-014 | A | YAML is export-only via a narrow emitter | All strings double-quoted so Yes/No never become booleans. No YAML parser exists |
| D-015 | A | Web UI has no authentication | Loopback binding by default, Host and Origin checks. Non-loopback binding prints a warning; approved access control is a deployment requirement |
| D-016 | A | Unreadable, malformed or mistyped input fields are reported, never silently dropped | Reader emits TYPE_MISMATCH, ENUM_INVALID, UNKNOWN_FIELD; bad list items keep their index |
| D-017 | A | Sources are extracted into a RawMetadataBundle before any manifest | Provenance (source and location) is kept for every item; row values from data files are never retained |
| D-018 | A | SQL is read by a restricted in-house parser for a documented subset | Never executed. DML is reported, not analysed, and no transformation lineage is inferred. General SQL needs an approved parser |
| D-019 | A | A draft manifest contains structure only | Terms stay candidates; services, elements, PII, attestations, table types and keys are left to the reviewer |
| D-020 | A | Input path boundary | Optional root checked after symlink resolution; symlinks inside scanned directories are skipped; depth 4; size and count limits |
| D-021 | A | Identifier case is preserved | Names are compared case-insensitively inside one SQL file to resolve ALTER and COMMENT statements |
| D-022 | U | View lineage hints are never written to manifests | The sourcedFromColumns shape is unverified (D-007) |
| D-023 | A | The draft's unresolved decision is shown when the draft is created | The UI is stateless until the Phase 5 wizard state exists, so later validation posts do not repeat it |
| D-024 | A | JSON and YAML files are skipped in directory scans but rejected as explicit sources | Avoids failing a scan because of unrelated configuration files |
| D-025 | A | URL fetching is allowlist-only; an empty allowlist disables it | Entries: host, host:port, *.domain; default ports only unless a port is listed |
| D-026 | A | Prohibited destinations are refused even when allowlisted | Loopback, link-local (169.254.169.254), unspecified, multicast, Alibaba and AWS IPv6 metadata, metadata host names. Private ranges are allowed only for allowlisted hosts |
| D-027 | A | Redirects are re-validated per hop; no https to http downgrade; at most 3; bearer token only to the original host | Token comes from CASC_URL_TOKEN and is never logged |
| D-028 | A | TLS uses the JVM default trust with no custom SSLContext | Approved truststores and proxies use javax.net.ssl.* and https.proxy* properties. Verified against a self-signed server: refused by default, accepted with an approved truststore |
| D-029 | A | Only formats readable without a parser are accepted from URLs | JSON, text, Markdown, CSV, TSV, SQL. HTML, YAML, Office and PDF are reported. Confluence is not supported natively |
| D-030 | P | OpenAPI operations are candidate interfaces; schemas are candidate structures; direction is never inferred | Draft lists response fields for DISTRIBUTION and request fields for SOURCING when the user chooses a direction. This selection rule is provisional |
| D-031 | U | Residual DNS rebinding window between the address check and the connection | Mitigated by the allowlist; needs approved DNS and network controls |
| D-032 | A | URL fetching on a shared web deployment needs authentication | Fetched content is shown to the requester; see D-015 |
| D-033 | A | OpenAPI references are followed locally only | External references are reported and never fetched |
| D-034 | P | System key policy: kind prefix plus lower-case slug, number on collision, generated only on request | Services, elements, interfaces, entities and transformations only; global uniqueness per D-003 |
| D-035 | A | Wizard state is held in server memory per session; nothing is persisted by the server | Users save and restore a project file explicitly; it contains the source texts, so it is sensitive |
| D-036 | A | Session cookie: random 256-bit id, HttpOnly, SameSite=Strict, 2 hours idle, cap of 200 sessions | No Secure flag because the server speaks HTTP (set it at the TLS proxy); quoted cookie values are accepted. Unauthenticated session creation can evict other sessions |
| D-037 | A | Every wizard POST needs the session CSRF token | In addition to the global Origin and Host checks |
| D-038 | A | Unresolved decisions are either acknowledgeable or must-fix | Acknowledging records a review and never changes the manifest |
| D-039 | A | PII hints are name-based prompts only | They never set or clear an attestation, PII element or category |
| D-040 | A | Name suggestions use a small fixed abbreviation list and are applied only when ticked | They never map a column to an element automatically |
| D-041 | A | EON ID and system name stay outside the manifest | Unchanged from D-002 |
| D-042 | A | Tables carry no logical services | The manifest tables have no such field; services attach to interfaces, elements and entities |
| D-043 | A | Views are listed in the wizard but not included in the manifest | The specification's treatment of views is unconfirmed |
| D-044 | U | sourcedFromColumns is entered as raw JSON per column and passed through unverified | Shape unconfirmed (D-007); invalid JSON is an error |
| D-045 | P | Interface payload paths: direction chooses the role (DISTRIBUTION: response, SOURCING: request) and individual paths can be excluded | Same provisional rule as D-030 |
| D-046 | A | The step 5 preview is capped at 200,000 characters; the download is always complete | The copy button is hidden when the preview is cut |
| D-047 | A | Agent integration is the CLI and HTTP JSON contracts, not MCP | Three tools: ingest_sources, validate_manifest_payload, generate_casc_manifest. Plain JSON over stdio is not called MCP |
| D-048 | A | Tool requests carry inline text only and are strict | No path field exists; unknown fields, names and values are rejected with their path. URLs follow the allowlist (D-025) |
| D-049 | A | Tool envelope and exit codes | ok/tool/result or error{code,message}; exit 0 ran, 1 errors with --fail-on-errors, 2 invalid request or usage, 3 I/O, 4 internal |
| D-050 | A | generate_casc_manifest reuses the wizard engine | userInputs use manifest names and the names shown in parsedMetadata; the same decisions, assembler and reconciler as the browser wizard |
| D-051 | A | Skills use the Agent Skills SKILL.md format for Claude and Copilot, plus Copilot instruction files | Tests check that every command and tool they mention exists and that the frontmatter limits hold |
| D-052 | P | Copilot file locations are confirmed only from secondary sources | Official GitHub documentation was not retrieved; confirm against your Copilot version |
| D-053 | A | An MCP adapter is not implemented; it is pending approval of an MCP host or SDK | Cost and scope are written down in docs/INTEGRATION.md |
| D-054 | A | CI is two scripts first; the GitHub workflow is optional and uses no marketplace actions | Includes a byte-identical manifest hash check across operating systems. The workflow has not been executed |
| D-055 | A | No interactive command-line wizard | The CLI never prompts or waits; the browser wizard covers interactive use |

