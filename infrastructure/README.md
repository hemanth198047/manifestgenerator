# Infrastructure

- launchers/  optional run-web.sh and run-web.bat. All logic stays in Java; scripts only locate the jar and forward arguments and the exit code. run-cli launchers arrive in Phase 6.
- config/     example external configuration.
- CI, proxy and truststore guidance are added in later phases.

Linux: `chmod +x infrastructure/launchers/run-web.sh` if the executable bit is lost. Keep .sh as LF and .bat as CRLF (enforced by .gitattributes).
Launchers are supplied but not yet executed.
