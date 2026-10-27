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

Bash: `CASC_PORT=9090 java -jar build/libs/casc-generator.jar web`
PowerShell: `$env:CASC_PORT = "9090"; java -jar build\libs\casc-generator.jar web`

## Ingest examples
Bash:       `./infrastructure/launchers/run-cli.sh ingest --input-files ./specs --root ./specs --db-name TRADE_DB --default-schema trade --output ./manifest.json`
PowerShell: `.\infrastructure\launchers\run-cli.bat ingest --input-files .\specs --root .\specs --db-name TRADE_DB --default-schema trade --output .\manifest.json`
Paths with spaces must be quoted. See `docs/INGESTION.md` for options and exit codes.

## Security notes
- Loopback only by default; requests whose Host is not loopback are refused while bound to loopback.
- POST requests carrying a different Origin are refused.
- `ingest` reads only the files you name; with `--root` they must resolve inside that directory (symbolic links are resolved first). The web UI never reads server files; it only receives pasted or uploaded text.
- No authentication. Binding to another address prints a warning; put the service behind approved access control.
- Linux: use a UTF-8 locale (for example `LANG=C.UTF-8`) if input files have non-ASCII names.

## Later phases
Run-time TLS, proxies, truststores, URL allow-lists, CI workflows and service wrappers are added with the features that need them.
