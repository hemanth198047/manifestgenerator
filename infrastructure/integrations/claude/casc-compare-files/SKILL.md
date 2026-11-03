---
name: casc-compare-files
description: Compares two or more JSON files (such as manifests or dataset manifests) with the local casc-generator tool and writes a self-contained HTML report that can be downloaded and opened offline, plus an optional JSON result. Shows added, removed, changed and identical values against a baseline file. Use when the user wants to compare, diff or reconcile several JSON or manifest files, or wants a report of what differs between versions or environments.
---

# Compare JSON files (HTML report)

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\\launchers\\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.
- The full command list is in `docs/COMMANDS.md`.

## What to ask the user
1. Which files (or folders of `.json` files). The **first** file is the baseline the others are compared with; confirm which one it should be.
2. Compare everything, or only a section (`--path`, for example `--path databases`)?
3. Where to write the report (`--output report.html`), and whether they also want the result as JSON (`--json-output`).
4. Do the arrays of objects have an identifying field other than `name`, `physicalName`, `id`, `logicalName` or `key`? If so, `--key FIELD`.

## Command
`run-cli compare --input A.json --input B.json [--input C.json ...] --output report.html [--json-output result.json] [--path SECTION] [--key FIELD,FIELD] [--title TEXT] [--max-rows N] [--lenient] [--fail-on-diff] [--overwrite]`

- A folder given with `--input` contributes its `.json` files in name order.
- Arrays of objects are matched by an identifying field when every item has a distinct one, so reordering is not a difference; other arrays are compared by position.
- The console prints added, removed, changed and same counts per file. The report is one HTML file: summary, per-file table, per-section table, and a searchable, filterable list of every difference with one column per file. It works offline, in light and dark mode, and prints well.
- `--max-rows N` limits the rows listed (default 5000); the counts are always complete. `--fail-on-diff` returns exit 1 when files differ (the report is still written). Without `--overwrite` an existing report is not replaced.
- Exit codes: 0 ok, 1 differences with `--fail-on-diff` (or a missing `--path`), 2 usage error, 3 a file is unreadable or not valid JSON, 4 internal error.

## Workflow
1. Confirm the files, the baseline and the output with the user.
2. Run the command, show the console summary, and tell the user where the HTML report is so they can open or download it.
3. Explain the counts in plain words: **Added** = only in this file, **Removed** = in the baseline but missing here, **Changed** = in both with a different value, **Same** = equal.
4. If a file is not valid JSON, show the message (it gives line and column); offer `--lenient` when the only problem is a trailing or missing comma. Never fix the user's files yourself.

## Explaining results (always do this)
Tell the user in plain words what the output means, what to do next and where to look. For a finding code, run `run-cli explain CODE` (all codes: `docs/FINDINGS.md`). Never hide errors or warnings.

## Rules
- Do not claim the differences are wrong or right; say what differs and let the user decide.
- Do not upload the files or the report anywhere.
