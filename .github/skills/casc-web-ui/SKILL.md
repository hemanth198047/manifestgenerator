---
name: casc-web-ui
description: Starts the casc-generator web interface (a five-step wizard that reads sources, collects system context and classification and governance decisions, then validates and exports a manifest) on a local address. Use when the user wants a browser UI instead of the command line, wants to review and decide interactively, or asks how to run the web app.
---

# Web UI

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Command
`run-cli web [--bind ADDR] [--port N] [--max-body-bytes N] [--allow-clipboard] [--url-allow HOST ...] [--url-allow-http] [--url-timeout S] [--url-max-bytes N]`

Or use the launcher: Linux/macOS `infrastructure/launchers/run-web.sh`, Windows `infrastructure\launchers\run-web.bat`.

- Defaults to `127.0.0.1:8080` (environment: `CASC_BIND`, `CASC_PORT`). Open `http://127.0.0.1:8080/wizard` in a browser. Stop it with Ctrl+C.
- The application has **no authentication**. Keep it on the loopback address. If the user binds a non-loopback address, warn them that it needs approved access control in front of it.
- URL sources in the UI are blocked unless hosts are allowed with `--url-allow`; never add hosts the user did not name.
- `--allow-clipboard` shows a copy button on the export step. The UI also offers project-file save and restore, and the tool contracts at `POST /api/tool/NAME`.
- If the port is taken the command exits with code 3; pick another `--port`.

## Workflow
Start the server in a terminal the user can see, tell them the address, and explain the five steps in the page (1 Sources, 2 System, 3 Classification, 4 Governance, 5 Validation and export; details in `docs/WIZARD.md`). Do not run it in the background without telling the user.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.
