# Verification Ledger

## Executed (authoring sandbox)
- Platform: Linux x86_64 (Ubuntu 24.04), OpenJDK 21.0.10. Compiled with the JDK's `jdk.compiler` module, not Gradle.
- Target JDK is 21 (D-056). The sandbox runtime is OpenJDK 21.0.10, so tests ran on the target JDK; the plain build compiled with `--source 21 --target 21` because this runtime cannot honour `--release` (no compatibility data). The `--release 21` flag in `build.gradle` and the scripts has therefore not been exercised.
- Test runner: 120 tests. Under `LC_ALL=C.UTF-8` all 120 pass. Under the POSIX locale 119 pass and 1 is skipped (Unicode file names need a UTF-8 locale).
- Fuzzing: seeded, repeatable tests in the suite (random text through every reader, damaged copies of the samples, damaged manifests, damaged tool requests) and a heavier one-off campaign of
  1,222,461 random and damaged inputs over the readers, manifest mapper, exporter and tool contract with a fresh seed each run: no uncaught exception and no empty success.
- Phase 7 review found and fixed two defects: a stale home page, and a Windows-style source name such as `C:\specs\orders.csv` producing a wrong table name (now covered by a test).
- Agent contracts: strict request checking at every level, stdin and file requests, exit codes, a request that must never read standard input, no arbitrary file access, URLs only under the allowlist,
  the HTTP route, a full ingest-to-generate pipe, example requests checked for drift against a fresh ingest, skill and instruction files checked against the real commands and tools,
  and the recorded manifest hash checked against today's output.
- CI scripts: `infrastructure/ci/build-without-gradle.sh` ran here (with the JDK compiler and jar modules supplied through its override variables), compiled, passed all tests and packaged a jar;
  `infrastructure/ci/verify.sh` then passed against that jar, including the manifest hash.
- Wizard engine tests (no web): assembly of only decided content, EON ID and name never in the manifest, malformed lineage JSON reported, decisions and acknowledgement rules, table conflicts,
  system keys with collision checks, project file round trip and strict loading, session isolation, expiry and cap.
- Wizard web tests with cookie jars: a full five-step run, cookie attributes, CSRF enforcement, isolation between two sessions, escaping of hostile input, conflicts and bad lineage blocking
  download, project save/reset/restore, large manifest preview cap, clipboard button only when allowed.
- A real defect was found and fixed through these tests: the server did not accept quoted cookie values (sent by Java's cookie jar); it does now.
- Packaged jar smoke test with curl: session cookie, CSRF 403/303 behaviour, a 3,000-table state (step 3 in about 0.2 s, step 5 in about 1 s), a 6 MB POST rejected with 413.
- URL tests use a JDK-only local HTTP server: allowlist matching, prohibited addresses and DNS answers, redirects (loop, off-list, metadata), timeouts, size limit,
  query redaction, bearer token isolation across hosts (127.0.0.1 and 127.0.0.2), URL-to-source mapping, web and REST URL flows, CLI URL safety.
- TLS (manual, scratch program, not part of the test suite): an HTTPS server with a self-signed certificate was refused with an actionable message under default trust, and
  fetched successfully once `-Djavax.net.ssl.trustStore` pointed at a truststore containing that certificate. Certificate checks are never weakened.
- Phase 3 results still hold: jar packaging, web pages, ingestion samples, scale checks (3,000 tables in about 0.7 s; 200k CSV rows in about 0.6 to 1.8 s).

## Supplied but NOT executed
- Gradle build (`jar`, `unitTests`, wrapper generation). Gradle was not available in the sandbox; `build.gradle` has never been parsed by Gradle.
- The GitHub workflow `.github/workflows/ci.yml` (no GitHub runner was available), including its plain-git checkout and the runner-provided JDK 21 and Gradle.
- The skill and instruction files in a real Claude or Copilot session; only their consistency with the application was checked.
- The two PowerShell scripts `verify.ps1` and `build-without-gradle.ps1` (no PowerShell was available).
- Anything on Windows, including the cross-platform manifest hash: `.bat` launchers, drive-letter paths, file locking, Windows symbolic links, and the 127.0.0.2 loopback token test.
- Real internal hosts, corporate proxies, internal certificate authorities and real Confluence or wiki servers. Only a local test server and one self-signed server were used.
- HTTPS redirect downgrade refusal is implemented but has no automated test (the test server speaks http only).
- Proxy configuration through the JVM properties was not exercised.
- Real browsers: rendering of the Sources and wizard pages, the JavaScript (file loaders, project loader, copy button), SameSite enforcement and accessibility. Only HTTP-level checks were run.
- Multi-user behaviour under load and long-running sessions (only the session cap and expiry logic are tested).
- Dialect coverage is still one synthetic sample per dialect; real OpenAPI documents from your gateways were not tried.

## Not available
- Official CasC schema validation and FDC acceptance evidence.
- Native XLSX, DOCX, PDF, HTML and YAML input, SQL DML analysis, Confluence page content (HTML).

## Equivalent commands used here (no Gradle)
```
java -m jdk.compiler/com.sun.tools.javac.Main -d out -encoding UTF-8 $(find backend/src -name '*.java')
cp -r frontend/resources/* out/
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp out com.example.casc.TestRunner
```
