# Infrastructure

## Launchers (`launchers/`)
`run-web.sh|bat` starts the web UI; `run-cli.sh|bat` runs any command. Logic stays in Java. Scripts locate the jar relative to
themselves, support spaces, forward arguments and return the exit code. Extra JVM options: `JAVA_OPTS`.
Linux: `chmod +x infrastructure/launchers/*.sh` if the bit is lost. `.sh` must be LF and `.bat` CRLF (set by `.gitattributes`).

## Configuration
There is no configuration file; settings are arguments or environment variables, identical on Windows and Linux.

| Setting | Argument | Environment | Default |
|---------|----------|-------------|---------|
| Bind address | `--bind` | `CASC_BIND` | `127.0.0.1` |
| Port | `--port` | `CASC_PORT` | `8080` |
| Max request body | `--max-body-bytes` | n/a | 5 MiB |
| Input root for `ingest` | `--root` | `CASC_INPUT_ROOT` | none (any readable path) |
| Allowed URL hosts | `--url-allow` (repeatable) | `CASC_URL_ALLOW` (comma separated) | none: URL sources are disabled |
| Allow plain http | `--url-allow-http` | n/a | off (https only) |
| URL time limit / size limit | `--url-timeout`, `--url-max-bytes` | n/a | 15 s / 5 MiB |
| Bearer token for URLs | n/a | `CASC_URL_TOKEN` | none |
| Copy button in wizard step 5 | `--allow-clipboard` (web) | n/a | off |

Bash: `CASC_PORT=9090 java -jar build/libs/casc-generator.jar web`
PowerShell: `$env:CASC_PORT = "9090"; java -jar build\libs\casc-generator.jar web`

## Ingest examples
Bash:       `./infrastructure/launchers/run-cli.sh ingest --input-files ./specs --root ./specs --db-name TRADE_DB --default-schema trade --output ./manifest.json`
PowerShell: `.\infrastructure\launchers\run-cli.bat ingest --input-files .\specs --root .\specs --db-name TRADE_DB --default-schema trade --output .\manifest.json`
Paths with spaces must be quoted. See `docs/INGESTION.md` for options and exit codes.

## URL sources, proxies and truststores
URL fetching is off until hosts are allowlisted. Examples:
Bash:       `CASC_URL_ALLOW=wiki.corp.example,*.docs.corp.example ./infrastructure/launchers/run-cli.sh ingest --url https://wiki.corp.example/api/trade.json --interface-direction DISTRIBUTION --output ./m.json`
PowerShell: `$env:CASC_URL_ALLOW = "wiki.corp.example"; .\infrastructure\launchers\run-cli.bat ingest --url https://wiki.corp.example/api/trade.json --output .\m.json`
Keep the token out of command lines and logs: `export CASC_URL_TOKEN=...` (Bash) or `$env:CASC_URL_TOKEN = "..."` (PowerShell), set from your secret store.
Certificate validation is never weakened. For an internal certificate authority use an approved truststore through the standard JVM properties, passed with `JAVA_OPTS`:
`JAVA_OPTS="-Djavax.net.ssl.trustStore=/etc/casc/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=<store password>"`
(a truststore password protects integrity only and is usually public, but it is visible in the process list). Corporate proxies use the JVM properties
`-Dhttps.proxyHost=... -Dhttps.proxyPort=... -Dhttp.nonProxyHosts=...`; proxy handling has not been exercised in this repository's tests.
The web server fetches URLs on behalf of whoever can reach it; with an allowlist configured, put the service behind approved authentication before sharing it.

## Agent and automation integration
`casc-generator tools` and `casc-generator tool NAME --request FILE|-` expose three JSON contracts (see `docs/INTEGRATION.md`); the web server serves the same at `POST /api/tool/NAME`.
Skill and instruction files for Claude and Copilot are in `infrastructure/integrations/`. CI scripts and the optional GitHub workflow are described in `infrastructure/ci/README.md`.
The tool contracts take inline text only and never read files on the caller's behalf.

## Optional reader adapters
With adapters enabled the jar needs its libraries: keep `build/libs/lib/` next to `casc-generator.jar` (the jar's `Class-Path` points there), or put the libraries on the class path yourself
(`java -cp "casc-generator.jar:lib/*" com.example.casc.Main ...`). The launchers run `java -jar` and therefore need the `lib` folder beside the jar. Check what is active with
`casc-generator capabilities`. Spreadsheet, Word and PDF files in the web UI are uploaded through the browser (Base64); raise `--max-body-bytes` for large files. See `docs/ADAPTERS.md`.

## Wizard sessions
Wizard state lives in server memory (200 sessions, 2 hours idle) and is lost on restart; users save a project file to keep it (it contains the source texts: treat it as sensitive).
The session cookie is `HttpOnly; SameSite=Strict` without `Secure`, because the server itself speaks HTTP. Behind a TLS-terminating proxy, add `Secure` there.
Anyone who can reach the server can create sessions, so keep the default loopback binding or put approved authentication in front before sharing it.

## Security notes
- Loopback only by default; requests whose Host is not loopback are refused while bound to loopback.
- POST requests carrying a different Origin are refused.
- `ingest` reads only the files you name; with `--root` they must resolve inside that directory (symbolic links are resolved first). The web UI never reads server files; it only receives pasted or uploaded text.
- URL fetching refuses loopback, link-local (cloud metadata) and other prohibited addresses even for allowlisted hosts, re-checks every redirect and limits time and size.
- No authentication. Binding to another address prints a warning; put the service behind approved access control.
- Linux: use a UTF-8 locale (for example `LANG=C.UTF-8`) if input files have non-ASCII names.

## Later phases
Run-time TLS, proxies, truststores, URL allow-lists, CI workflows and service wrappers are added with the features that need them.
