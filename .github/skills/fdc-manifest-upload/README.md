# fdc-manifest-upload

An AI agent skill that uploads a manifest to a chosen FDC environment by running the **existing** upload script:

```
/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py -s <server> -m <meta> -p <project> -r <release> -e <environment> -md <distribution system>
```

The command is used exactly as it is; the skill only fills in the values the user gives. It does not reimplement, copy or modify the upload, does not call FDC APIs, and does not change `casc.properties`. It works in Claude Code, GitHub Copilot (VS Code, IntelliJ, Copilot CLI) and any CLI agent that can read a Markdown instruction file and run shell commands.

## What it does
1. Asks for the FDC environment, the location of `casc.properties`, and Server, Meta, Project, Release and Distribution System.
2. Validates them (files exist and are readable, project root, script present and executable, interpreter available, safe values, optional consistency with `casc.properties`).
3. Verifies authentication and authorization: SSO first, PROID as the fallback, through the organisation's own commands (configured by you, see below).
4. Shows a summary and a plan hash; **runs the upload only after an explicit yes**, and only for exactly the plan that was shown.
5. Runs the existing script (no shell, in the folder that holds `casc.properties`), captures the output with secrets redacted, and reports **succeeded**, **not verified** or **failed** with guidance.

## Folder layout
```
fdc-manifest-upload/
├── SKILL.md                  the instructions the agent follows
├── README.md                 this file
├── scripts/
│   ├── fdc_common.py                    shared checks, command builder, redaction, result classification
│   ├── validate_upload_prerequisites.py step 2 (read only)
│   ├── auth_check.py                    step 3 (SSO, PROID, authorization)
│   ├── run_upload.py                    steps 4-7 (plan, then execute behind the guards)
│   └── install_skill.py                 installs the folder for Claude or Copilot
├── references/               upload_workflow.md, authentication.md, troubleshooting.md, generic_agent_instructions.md
├── examples/sample_upload.md example conversations
└── tests/                    unit tests (python3 -m unittest discover -s tests)
```

## Prerequisites
- Run on a host where `/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py` exists and is executable (it is started as given, without `python`).
- `python3` (3.8 or newer) for the helper scripts; standard library only, nothing to install.
- A project with a `casc.properties` file.
- The organisation's SSO and PROID commands wired in (see "Integration points").

## Installation
The folder is already in this repository at `.github/skills/fdc-manifest-upload/`.

| Agent | How |
|-------|-----|
| **GitHub Copilot** (VS Code agent mode, IntelliJ, Copilot CLI, cloud agent) | Nothing to do inside this repository: Copilot discovers `.github/skills/`. For another project, `python3 scripts/install_skill.py --agent copilot --scope project --project-dir /path/to/project` (or `--scope user` for `~/.copilot/skills/`). In IntelliJ enable Agent Skills in the Copilot settings and use Agent mode. |
| **Claude Code / Claude CLI** | `python3 scripts/install_skill.py --agent claude --scope user` (installs to `~/.claude/skills/`) or `--scope project --project-dir DIR` (`DIR/.claude/skills/`). Add `--symlink` to link instead of copy, `--force` to replace an existing install. Or copy the folder yourself. |
| **Other CLI agents** | See `references/generic_agent_instructions.md`: point the agent at `SKILL.md` or paste the short block shown there. |

The installer never overwrites an existing installation without `--force`. Only the skill files are installed; the upload script stays where it is.

## Usage
Say: "Upload my manifest to FDC" (or "push the manifest to FDC UAT"). The agent asks for what it is missing, validates, checks access, shows the summary, and waits for your yes. See `examples/sample_upload.md`.

You can also run the helpers by hand (all read only except `run_upload.py execute`):
```
python3 scripts/validate_upload_prerequisites.py --environment UAT --properties /path/casc.properties --server S --meta M --project P --release R --distribution-system MD
python3 scripts/auth_check.py --method sso --environment UAT --project P --release R
python3 scripts/run_upload.py plan --environment UAT --properties /path/casc.properties --server S --meta M --project P --release R --distribution-system MD --auth-method sso
python3 scripts/run_upload.py execute <same inputs> --auth-method sso --plan-hash <hash from plan> --confirmed yes
python3 scripts/validate_upload_prerequisites.py --inspect-script      # how the script mentions casc.properties (read only)
```

## Integration points (configure once)
The repository has no copy of the upload script or of your SSO/PROID tooling, so these are **not guessed**; set them as environment variables (for example in your shell profile). Anything unset is reported as "not configured".

| Variable | Purpose |
|----------|---------|
| `FDC_SSO_STATUS_CMD` | exits 0 when a valid SSO session exists (`{environment}`) |
| `FDC_SSO_LOGIN_CMD` | the approved SSO sign-in command; shown to the user, never run by the skill |
| `FDC_PROID_AUTH_CMD` | the approved PROID authentication (`{proid}` `{environment}`) |
| `FDC_AUTHZ_CHECK_CMD` | exits 0 when the identity may upload to the environment (`{environment}` `{project}` `{release}` `{proid}`) |
| `FDC_PROPS_DELETE_KEY` | name of the `casc.properties` property that controls update versus delete (shown as written, never interpreted) |
| `FDC_PROPS_PROJECT_KEY` / `FDC_PROPS_RELEASE_KEY` | property names holding project and release, to cross-check what the user typed |
| `FDC_UPLOAD_SUCCESS_PATTERN` | regular expression for the script's success message; without it a clean exit is reported as NOT VERIFIED, never as success |

## Assumptions and open dependencies
Things that could not be inspected here and must be confirmed with the owners of the upload script and of FDC access:
1. **Where the script looks for `casc.properties`.** Assumed: the current folder (the skill runs the script in the folder of the file you give). If it looks in a fixed project root, the validation warns when the file is elsewhere. Check with `--inspect-script`.
2. **The name of the update/delete property** and the meaning of its values. Not assumed; set `FDC_PROPS_DELETE_KEY` to show it. The summary always warns that deletion may happen.
3. **SSO and PROID mechanisms and the authorization check.** Not assumed; configure the four commands above. Without them access is reported as NOT VERIFIED and the upload needs the user's explicit acknowledgement (the script still enforces access itself).
4. **The script's success output.** Not assumed; set `FDC_UPLOAD_SUCCESS_PATTERN` to get "SUCCEEDED" instead of "NOT VERIFIED".
5. **Environment names and FDC endpoints.** Not known to the skill; the script maps `-e` to an endpoint. The skill only checks that the name is non-empty and safe, and never changes it.
6. The script is **executable** and started without `python`, as in the command given.

## Safety summary
Explicit yes required; the executed plan equals the summarised plan (hash includes the content of `casc.properties`); no shell, so no injection; values starting with `-` or containing control characters are refused; `casc.properties` and the script are never modified, moved or deleted; no secrets are requested, stored or shown; authorization is checked separately from authentication; failures are never reported as success.

## Tests
```
cd .github/skills/fdc-manifest-upload
python3 -m unittest discover -s tests -v
```
The tests use a fake upload script in a temporary folder; the real script is never run. They cover validation, argument safety, authentication states, the confirmation guards, exact command construction, classification of results and redaction. The repository's own build runs them too (skipped when `python3` is missing).
