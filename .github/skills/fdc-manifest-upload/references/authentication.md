# Authentication and authorization (reference)

## What is and is not known
This repository does not contain the upload script or any FDC authentication code, so **how SSO and PROID work in your organisation is not known to this skill**. The skill does not invent it. It calls the organisation's own approved commands, which you configure once with environment variables. Nothing is hard-coded.

| Variable | When it runs | Success means | Placeholders |
|----------|--------------|---------------|--------------|
| `FDC_SSO_STATUS_CMD` | SSO step | exit 0 = a valid SSO session exists | `{environment}` |
| `FDC_SSO_LOGIN_CMD` | shown to the user when there is no session; **never run by the skill** | n/a | `{environment}` |
| `FDC_PROID_AUTH_CMD` | PROID step | exit 0 = the PROID is authenticated through the approved mechanism | `{environment}` `{proid}` |
| `FDC_AUTHZ_CHECK_CMD` | after authentication (SSO or PROID) | exit 0 = this identity may upload to the environment | `{environment}` `{project}` `{release}` `{proid}` |

How the commands are run: the template is split into arguments first (like a shell would, but without running a shell), then the placeholders are filled in, so a value can never add an argument. Standard input is closed, there is a timeout (120 s), and only the **exit code** decides. A few lines of output are shown after secret redaction (`password=`, `token:`, `Authorization: Bearer ...`, `api_key=` and similar are replaced by `<redacted>`).

Example (placeholders; use your organisation's real commands):
```
export FDC_SSO_STATUS_CMD='<your sso status command> --env {environment}'
export FDC_SSO_LOGIN_CMD='<your sso login command> --env {environment}'
export FDC_AUTHZ_CHECK_CMD='<your access check command> --env {environment} --project {project}'
export FDC_PROID_AUTH_CMD='<your proid sign-in command> --proid {proid} --env {environment}'
```

## SSO (preferred)
1. `auth_check.py --method sso` runs the status command. Exit 0 = session active.
2. No session (exit 10): the skill prints the sign-in command. The user runs it **in their own terminal** (Claude Code: type `!` and the command). The skill never automates credentials, opens browsers for the user or stores anything.
3. After the user says they are done, run the check again. It verifies the session and then **authorization** for the environment.

## PROID (fallback)
Only when SSO cannot be completed. The user gives the **PROID name** (letters, digits, `.`, `_`, `-`; anything else is rejected, which also stops passwords and tokens from being pasted into the command). The skill hands the name to `FDC_PROID_AUTH_CMD`. If the approved mechanism needs a secret, it prompts the user in their own terminal or uses the organisation's secure store; the skill never sees it. PROID alone is not assumed to be enough: `FDC_AUTHZ_CHECK_CMD` must also pass.

## Exit codes of `auth_check.py`
| Code | State |
|------|-------|
| 0 | authentication **and** authorization verified |
| 10 | SSO session not active; sign in, then run again |
| 11 | a required command is **not configured**; nothing was verified for that part |
| 12 | authentication failed |
| 13 | authenticated, but not authorized for the environment |
| 2 | invalid input (bad environment, bad PROID, unknown method) |

## If nothing is configured
The skill says plainly that it could not verify access. The existing script enforces access itself, so the user may choose to continue; this needs their explicit acceptance, is recorded in the summary as "NOT VERIFIED by the skill", and uses `--auth-method unverified --ack-unverified-auth`. Configure the variables above to remove this gap.

## Security properties
No passwords or tokens are requested, stored, written to files or shown. Output is redacted before display. The upload script receives the environment of the shell it runs from (so existing Kerberos/SSO tickets keep working); the skill does not print or log it.
