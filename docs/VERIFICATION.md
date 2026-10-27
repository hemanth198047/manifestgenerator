# Verification Ledger

## Executed (authoring sandbox)
- Platform: Linux x86_64 (Ubuntu 24.04), OpenJDK 21.0.10. Compiled with the JDK's `jdk.compiler` module, not Gradle.
- Test runner: 103 tests. Under `LC_ALL=C.UTF-8` all 103 pass. Under the POSIX locale 102 pass and 1 is skipped (Unicode file names need a UTF-8 locale).
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
- Anything on Windows: `.bat` launchers, drive-letter paths, file locking, Windows symbolic links, and the 127.0.0.2 loopback token test.
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
