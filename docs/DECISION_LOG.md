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
