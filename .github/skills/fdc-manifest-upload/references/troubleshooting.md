# Troubleshooting

The helper names a **likely** cause category from the text of the script's output. It is a heuristic, not a diagnosis: always show the user the real output.

| Category | Typical signs | What to do |
|----------|---------------|------------|
| Authentication | 401, "unauthorized", "login required", "session expired", Kerberos/SSO errors | sign in again (SSO, else PROID), re-run `auth_check.py`, then plan and confirm again |
| Authorization | 403, "forbidden", "permission denied", "access denied" | the identity may not upload to this environment: request access; do not try another environment |
| Invalid environment | "unknown environment", "invalid environment" | ask the user for the exact environment name the script expects; never substitute one |
| Missing configuration | "casc.properties not found", "missing property" | check where the script looks (`--inspect-script`); confirm the file and the working folder shown in the summary; never move the file without approval |
| Invalid script arguments | "usage:", "unrecognized arguments", "arguments are required" | re-check `-s -m -p -r -e -md` with the user |
| Python execution | "Traceback", "ModuleNotFoundError", "bad interpreter", "Permission denied" when starting | check that the interpreter on the script's `#!` line and its dependencies are available, and that the script is executable |
| FDC connectivity | "connection refused", "timed out", DNS or SSL errors, 502/503/504 | check network/VPN and the server value, then retry |
| Manifest validation | "validation failed", "invalid manifest", schema errors | read the message; the manifest is not changed by this skill; fix it where it is produced |
| Other script error | none of the above | show the output and ask the script's owners |

## Messages from the validation step
| Message | Meaning | Fix |
|---------|---------|-----|
| "No file at ..." | the path to `casc.properties` is wrong | ask for the correct absolute path |
| "exists but is not readable" | permissions | fix permissions yourself; the skill never changes them |
| "upload script was not found" | `/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py` is missing on this machine | run from a host that has `/ms/dist`; the skill never copies the script |
| "not executable" | the command is run as given, without `python` | ask the user how they normally start it |
| "starts with '-'" | a value would be read as an option | re-check the value |
| "You gave project ... but casc.properties says ..." | mismatch with the configured property | ask which is right; change neither file nor value on your own |
| "REFUSED: the plan changed" | an input or `casc.properties` changed after the summary | run `plan` again, show the new summary, ask again |
| "NOT VERIFIED" | exit 0 but no proof | check FDC; ask the script's owners for its success message and set `FDC_UPLOAD_SUCCESS_PATTERN` |
