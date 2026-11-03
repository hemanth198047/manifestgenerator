---
name: casc-json-tools
description: Extracts any section from a JSON file and runs operations on it with the local casc-generator tool - get a section by path, list keys, count values, pretty-print or sort, set or delete values, merge files, flatten, convert an array to CSV, search keys and values, and check that a file is valid JSON. Use when the user wants to pull part of a JSON or manifest file, edit values, merge or inspect JSON, or fix why a JSON file will not parse. Works on any JSON file.
---

# JSON tools (extract a section, inspect, edit)

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\\launchers\\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.
- The full command list is in `docs/COMMANDS.md`.

## What to ask the user
1. Which file, and what they want out of it: a section, a list of keys, counts, a changed copy, a merge, CSV, or a search.
2. For changes (`set`, `delete`, `merge`): the output file. These commands never change the input file; to change a file in place the user must say so (same file with `--overwrite`).
3. If the file has trailing or missing commas, add `--lenient`; the output is always valid JSON. If only one section of the file is broken, `--lenient` still reads the others and says which it left out; tell the user so they can fix that section if they need it.

## Commands
`run-cli json OPERATION --input FILE [options]` (`run-cli json` alone lists the operations).

| Operation | Use |
|-----------|-----|
| `get --path P [--lines] [--with-paths] [--compact] [--output FILE]` | Extract the section(s) a path selects; `--lines` prints one value per line (good for lists of names). Several matches print an array, or `{path: value}` with `--with-paths`. |
| `keys [--path P]` | Map of a file: keys with kind and size, or item kinds and keys of an array. Start here when you do not know the structure. |
| `stats [--path P]` | Counts of objects, arrays, strings, numbers, booleans, nulls and depth |
| `format [--compact] [--sort-keys] [--output FILE]` | Pretty-print, minify, sort keys |
| `set --path P --value JSON --output FILE` | Set a value (text must be quoted JSON, for example `--value '"Account ID"'`); `*`, slices and filters set several places |
| `delete --path P --output FILE` | Remove keys or items |
| `merge --input A --input B ... --output FILE` | Deep merge, later wins, arrays replaced |
| `flatten [--path P] [--output FILE]` | One line per value: `path = value` |
| `csv --path P [--output FILE]` | An array of objects as CSV |
| `find --text T [--max N]` | Paths whose key or value contains the text |
| `validate` | Is it valid JSON? The error gives line and column |

Paths: `databases[0].schemas[*].tables[?physicalName=TAPSAccount].columns`. `[n]` one item (`-1` last), `[*]` every item, `[a:b]` slice, `[?field=value]` filter (`!=` not equal, `~` contains), `..key` any depth, `["a.b"]` quoted key, no path = whole document.
Add `--overwrite` only when the user wants an existing output file replaced. Exit codes: 0 ok, 1 nothing matched, 2 usage error (the message explains a bad path), 3 file unreadable or not valid JSON, 4 internal error.

## Workflow
1. If the structure is unknown, run `keys` (and `keys --path ...` to go deeper) before guessing a path.
2. Run the operation and show the result. For a path that matches nothing, show the message and use `keys` to find the right path; keys are case-sensitive.
3. For `set` and `delete`, say how many changes were made and where the result was written.

## Explaining results (always do this)
Tell the user in plain words what the output means, what to do next and where to look. For a finding code, run `run-cli explain CODE` (all codes: `docs/FINDINGS.md`). Never hide errors or warnings.

## Rules
- Do not edit the user's files unless they ask; write results to a new file by default.
- Do not invent values. Use only what the user gave you.
