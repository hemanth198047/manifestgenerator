# Wizard guide (Phase 5)

Open `/wizard`. The five steps feed one manifest; nothing is written to disk by the server.

## Session, state and project files
- State is kept **in memory per browser session**. The session cookie holds only a random 256-bit id (`HttpOnly`, `SameSite=Strict`, 2 hours idle).
  Sessions are isolated from each other. At most 200 sessions exist; the longest idle one is evicted first. A restart of the server clears everything.
- Every POST carries the session's CSRF token and also passes the global Origin and Host checks.
- **Save project file** downloads the whole state as JSON; **Load project** (step 1) restores it. The file contains the source texts and every decision, so treat it
  as sensitive as the sources. Loading is strict and names the path of any problem. **Start over** clears the session.
- The cookie has no `Secure` flag because the server speaks plain HTTP; if you place it behind a TLS-terminating proxy, add `Secure` there.
  Creating sessions needs no login, so an unauthenticated network could evict other sessions: bind to loopback or put approved authentication in front (see D-015).

## Steps
1. **Sources**: paste, load files or fetch allowlisted URLs; options include the dictionary header mapping (`map.*`). Saving re-reads every source and drops decisions
   for tables that no longer exist (the count is shown).
2. **System**: EON ID, system name, TAI database name, database type, default schema and logical services. The EON ID and name are **not written to the manifest**.
   An unset "deployed on premises" stays unset and is reported.
3. **Classification**: include or exclude each table, set Transaction or Reference, give each interface a direction and logical services, and exclude individual payload paths.
   Only paths for the chosen direction are used (DISTRIBUTION: response paths, SOURCING: request paths; provisional rule). Interfaces without a direction are left out.
   The same table in two included sources is a conflict you must resolve by excluding all but one. Views are listed but not included.
4. **Governance**: logical elements, data entities and transformations (comma separated lists for references), plus a page per table or interface with description override,
   logical element, sensitive-data attestation, PII elements, data subject categories and (tables only) raw lineage JSON.
   - *Suggested elements* come from candidate terms and repeated column names. A suggestion is only added when you tick it, and it never maps a column by itself.
   - *Hints* flag names that resemble sensitive data. They never set or clear an attestation, PII element or category.
   - *Lineage*: hints and statements from the sources are shown for review but are not written, because the official `sourcedFromColumns` shape is unconfirmed.
     Valid JSON you type is passed through unverified; invalid JSON is an error, never silently dropped.
5. **Validation and export**: the manifest is assembled and validated; errors, warnings, unresolved decisions and info are listed separately with paths and corrections.
   Choose JSON or YAML, preview (first 200,000 characters), download (blocked while errors remain unless you tick the override), optionally copy to the clipboard
   (only when the server was started with `--allow-clipboard`), and optionally generate missing system keys.

## Unresolved decisions
Each open decision appears as an `UNRESOLVED_DECISION` warning. Some can be **acknowledged** (for example "no table type set"); acknowledging records that you looked and does not change
the manifest. Others must be fixed: sources that failed, conflicting tables, interfaces without a direction, and columns whose names resemble sensitive data but have no attestation.

## System keys (provisional policy)
Generated only on request, only for services, elements, interfaces, entities and transformations: `svc-`, `elem-`, `intf-`, `ent-`, `xform-` plus the lower-case slug of the name
(runs of other characters become one hyphen). A collision with any existing key appends `-2`, `-3`, and so on. Keys you typed are never changed. Tables, schemas and columns get no keys.

## Limits
Pages show 50 columns or paths at a time; at most 20,000,000 characters of sources per session; request bodies are limited by `--max-body-bytes` (default 5 MiB).
