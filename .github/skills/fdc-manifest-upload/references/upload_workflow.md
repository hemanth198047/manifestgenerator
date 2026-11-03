# Upload workflow (reference)

The skill runs seven steps. Every step uses a helper in `scripts/`; the upload itself is always the existing script.

```
collect inputs -> validate -> authenticate + authorize -> plan (summary) -> user confirms -> execute -> report
```

## The command that is run
```
/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py -s <server> -m <meta> -p <project> -r <release> -e <environment> -md <distribution system>
```
- Used as given: the script is started directly (it is not prefixed with `python`), as a list of arguments with **no shell**, so a value such as `a; rm -rf /` stays one harmless argument.
- Only the six values change. The flag order is fixed: `-s -m -p -r -e -md`.
- The working folder is the folder that holds the supplied `casc.properties` (`--cwd` overrides it). It is shown in the summary.

## Step by step
| Step | Helper | Result |
|------|--------|--------|
| 1 Collect | (conversation) | environment, `casc.properties` location, server, meta, project, release, distribution system |
| 2 Validate | `validate_upload_prerequisites.py` | PASS/FAIL/WARN/INFO lines; exit 1 on any FAIL |
| 3 Authenticate | `auth_check.py --method sso` then, if needed, `--method proid` | exit 0 / 10 / 11 / 12 / 13 |
| 4 Plan | `run_upload.py plan` | summary + plan hash |
| 5 Confirm | (conversation) | explicit yes from the user |
| 6 Execute | `run_upload.py execute --plan-hash H --confirmed yes` | RESULT line, exit 0 / 1 / 3 / 2 |
| 7 Report | (conversation) | success, not verified, or failure with guidance |

## What step 2 checks
Environment not empty and safe; `casc.properties` exists, is a file and is readable; the project root (nearest folder with `.git`, `pom.xml`, `build.gradle`, `pyproject.toml`, ... above the file, else the file's folder); the upload script exists and is executable; its interpreter (from its `#!` line) exists; the five other arguments are present, not blank, do not start with `-`, contain no control characters and are at most 256 characters; and, only if `FDC_PROPS_PROJECT_KEY` / `FDC_PROPS_RELEASE_KEY` are set, that the values in `casc.properties` equal what the user gave.

## Where does the script look for casc.properties?
Not known to this skill (the script is outside this repository). The skill runs the script in the folder that holds the file you supplied. If the script instead looks in the project root, step 2 warns when the file is somewhere else. Use `validate_upload_prerequisites.py --inspect-script` to read the script's own lines about `casc.properties` (read only) and decide with the user. The skill never moves or copies the file.

## The update-versus-delete property
Not known to this skill. If the organisation sets `FDC_PROPS_DELETE_KEY` to the property's name, the summary shows `name=value` exactly as written. Otherwise the summary lists lines of `casc.properties` whose key looks related (delete, remove, purge, update, sync, ...; values of secret-looking keys are hidden) and says the behaviour is decided by the script. The summary **always** warns that, depending on `casc.properties`, existing FDC entries that are not in the incoming manifest may be deleted.

## Safety guards in `run_upload.py execute`
- `--confirmed yes` is required (the agent passes it only after the user said yes).
- `--plan-hash` must equal the hash of the current plan. The hash covers the exact argument list, the working folder, the path **and content** of `casc.properties`, and the authentication method, so changing any of them after the summary forces a new summary and a new yes.
- `--auth-method unverified` also needs `--ack-unverified-auth`.
- `--timeout` (default 3600 s) stops a hanging upload and reports it as a failure.

## Result classification
| RESULT | Exit | Rule |
|--------|------|------|
| UPLOAD SUCCEEDED | 0 | exit code 0, no error text in the output, and `FDC_UPLOAD_SUCCESS_PATTERN` matched |
| UPLOAD NOT VERIFIED | 3 | exit code 0 and no error text, but no pattern is configured or it did not match |
| UPLOAD FAILED | 1 | non-zero exit, a timeout, or error text in the output even with exit code 0 |
| REFUSED | 2 | a guard or a validation failed; nothing ran |
