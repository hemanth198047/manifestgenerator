---
name: fdc-manifest-upload
description: Uploads a manifest to a chosen FDC (Federated Data Catalog) environment by running the existing casc_upload_manifest.py script. Collects and validates the environment, the casc.properties location, server, meta, project, release and distribution system; verifies SSO (with a PROID fallback) authentication and authorization; shows an upload summary and waits for an explicit yes before running. Use when the user asks to upload, publish or push a manifest to FDC. Never edits casc.properties or the upload script, and never uploads without confirmation.
---

# FDC manifest upload

This skill is a careful wrapper around one existing command. It does not upload anything itself and never calls FDC APIs.

The command that does the upload (used exactly as given; only the values after the flags change):

```
/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py -s <server> -m <meta> -p <project> -r <release> -e <environment> -md <distribution system>
```

The existing script reads `casc.properties` and decides by itself whether existing FDC entries are only updated or also deleted. **You must not interpret, change or override that property.** You only make sure the right `casc.properties` is where the script looks, and you tell the user plainly what may happen.

Run the helper scripts from this skill's folder with `python3 scripts/<name>.py` (the folder that holds this `SKILL.md`). They use only the Python standard library.

## Rules that never bend
1. Never run the upload without the user's explicit "yes" to the summary. Invoking the skill is not consent.
2. Never upload to an environment other than the one the user named; pass it exactly as given.
3. Never invent, guess or default a value for `-s -m -p -r -e -md`. Ask. Use a value from the conversation or the project only when it is clearly stated there.
4. Never modify, move, copy or delete `casc.properties`, and never edit `casc_upload_manifest.py`. If something needs changing, explain the smallest safe change and ask for approval first.
5. Never bypass SSO or any access control. Never ask the user for a password or token in the chat; secrets are typed only into the organisation's own sign-in tool. A PROID is a name, not a secret.
6. Never treat "signed in" as "allowed to upload". Check authorization for the selected environment separately.
7. Never report success unless the helper says `UPLOAD SUCCEEDED`. If it says `NOT VERIFIED`, say exactly that.
8. Never print secrets. The helpers redact output; do not undo that.
9. Never create or change a manifest. This skill only uploads the one that already exists.

## Step 1: collect the inputs
Ask for what is missing, one short question at a time, and do not re-ask what the conversation already gave you.
1. **FDC environment** (becomes `-e`). Use the exact spelling the user gives.
2. **Location of `casc.properties`** (absolute path, or the folder that holds it).
3. **Server (`-s`), Meta (`-m`), Project (`-p`), Release (`-r`), Distribution system (`-md`)**: ask the user to provide or confirm all five together.

## Step 2: validate (read only)
```
python3 scripts/validate_upload_prerequisites.py --environment ENV --properties /path/to/casc.properties \
  --server S --meta M --project P --release R --distribution-system MD
```
It checks: environment not empty, `casc.properties` exists and is readable, the project root, the working folder, the upload script exists and is executable, its interpreter is available, every argument is present and safe, and (only when the organisation configured the property names) that project and release match `casc.properties`.
- Any `[FAIL]`: explain it in plain words and ask the user to correct it. Do not continue.
- `[WARN]` about the location of `casc.properties`: the script may look for the file in the project root or the current folder. To see how the script mentions it, run `python3 scripts/validate_upload_prerequisites.py --inspect-script` (read only) and show the lines to the user. If the file must be elsewhere, ask the user what they want; never move it yourself.

## Step 3: authenticate and authorize
Try **SSO first**:
```
python3 scripts/auth_check.py --method sso --environment ENV --project P --release R
```
| Exit | Meaning | What you do |
|------|---------|-------------|
| 0 | SSO session active and authorized for ENV | go on |
| 10 | no active SSO session | show the SSO sign-in command the helper prints, ask the user to sign in **in their own terminal** (in Claude Code they can type `!` before the command), wait for them to say they are done, then run the check again |
| 11 | a needed check is **not configured**, so nothing was verified | tell the user which integration point is missing (see `references/authentication.md`); do not pretend it was verified |
| 12 | authentication failed | tell the user; offer the PROID fallback only if SSO cannot be completed |
| 13 | signed in but **not authorized** for ENV | stop; tell the user to request access; never try another environment |

**PROID fallback** (only when SSO could not be completed): tell the user SSO did not complete, ask for the **PROID name**, then
```
python3 scripts/auth_check.py --method proid --environment ENV --proid NAME --project P --release R
```
Use only the organisation's approved mechanism, which the helper calls. If more credentials or approvals are needed, tell the user to supply them through that tool. Never ask for or accept a password or token in the chat.

If the checks are **not configured** (exit 11) the skill cannot verify access. Say so clearly. The upload script enforces access itself, so you may continue **only** if the user explicitly accepts that the skill could not verify authentication and authorization; then use `--auth-method unverified` with `--ack-unverified-auth` below, and the summary will say so.

## Step 4: prepare the upload
```
python3 scripts/run_upload.py plan --environment ENV --properties /path/to/casc.properties \
  --server S --meta M --project P --release R --distribution-system MD --auth-method sso
```
(`--auth-method proid --proid NAME` after a PROID login, or `--auth-method unverified` as described above.) It re-validates, then prints the summary and a **plan hash**. The exact command it will run is in the summary.

## Step 5: confirm
Show the summary to the user as printed: environment, server, meta, project, release, distribution system, `casc.properties` location, authentication method, the update behaviour lines exactly as written in `casc.properties`, and the exact command. **Highlight that the upload may delete existing FDC manifest entries that are not in the incoming manifest**, depending on `casc.properties`, which the script reads.
Ask: "Do you want to proceed?" Continue only on an explicit yes. If the user changes anything, run `plan` again and ask again.

## Step 6: execute
```
python3 scripts/run_upload.py execute <same inputs and --auth-method> --plan-hash HASH --confirmed yes
```
It refuses to run if the plan changed (including `casc.properties`), if `--confirmed yes` is missing, or if authentication was unverified without `--ack-unverified-auth`. It runs the existing script without a shell, in the folder that holds `casc.properties`, and captures the output (secrets redacted).

## Step 7: report
Read the helper's `RESULT` line:
- **UPLOAD SUCCEEDED** (exit 0): confirm; show environment, project, release, authentication method and the relevant output; repeat the update-behaviour lines.
- **UPLOAD NOT VERIFIED** (exit 3): the script exited normally but nothing proves the manifest arrived. Say that, show the output, and suggest checking FDC. Do not call it a success.
- **UPLOAD FAILED** (exit 1): say it failed, show the error output, name the likely cause category the helper printed (it is a heuristic from the output text) and give the troubleshooting guidance. See `references/troubleshooting.md`.
- **REFUSED** (exit 2): a safety check stopped the run; explain which one and what to fix.

## Configuration the organisation can provide (all optional)
The skill never guesses these; each unset item is reported as "not configured".
| Variable | Purpose |
|----------|---------|
| `FDC_SSO_STATUS_CMD` | command that exits 0 when a valid SSO session exists |
| `FDC_SSO_LOGIN_CMD` | the approved SSO sign-in command (shown to the user, never run by the skill) |
| `FDC_PROID_AUTH_CMD` | the approved way to authenticate a PROID (`{proid}`, `{environment}`) |
| `FDC_AUTHZ_CHECK_CMD` | exits 0 when the identity may upload to `{environment}` |
| `FDC_PROPS_DELETE_KEY` | name of the `casc.properties` property that controls updating versus deleting (shown as written, never interpreted) |
| `FDC_PROPS_PROJECT_KEY`, `FDC_PROPS_RELEASE_KEY` | names of the properties that hold project and release, to cross-check the user's values |
| `FDC_UPLOAD_SUCCESS_PATTERN` | regular expression for the script's success message; without it a clean exit is reported as NOT VERIFIED |

More detail: `references/upload_workflow.md`, `references/authentication.md`, `references/troubleshooting.md`, `references/generic_agent_instructions.md`, `examples/sample_upload.md`, and `README.md` (installation, assumptions, tests).
