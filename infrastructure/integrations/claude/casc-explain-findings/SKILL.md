---
name: casc-explain-findings
description: Explains any warning, error or info finding reported by casc-generator (for example UNKNOWN_FIELD, TYPE_MISMATCH, PII_REQUIRED, METADATA_TABLE_NOT_IN_MANIFEST) in plain language, says exactly how to fix it and where to look, using the built-in explain command and docs/FINDINGS.md. Use when the user pastes tool output, asks what a finding, code or the Validation level LOCAL summary means, or asks how to fix it.
---

# Explain findings

Turns the tool's finding output into clear guidance. It adds nothing of its own: the text comes from the application's `explain` command.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.

## Commands
- `run-cli explain CODE`: what the finding means, how to fix it, where to look (for example `run-cli explain UNKNOWN_FIELD`; case does not matter).
- `run-cli explain`: lists every code with a short title.
- All codes are also written out in `docs/FINDINGS.md`.

## How a finding is laid out
```
WARNING UNKNOWN_FIELD datasets        <- severity, code, path (where in the file)
    <message>                         <- what was found and what the tool does about it
    Fix: <suggestion>                 <- what to do
    More: casc-generator explain ...  <- how to get the full explanation
Summary: 0 error(s), 1 warning(s)...  <- counts and the validation level
```
- **ERROR** blocks export, nothing is written. **WARNING** needs attention but does not block. **INFO** is for your information.
- **Validation level LOCAL** means the manifest was checked only against this tool's provisional rules. It is not the official CasC schema and not FDC acceptance.

## Workflow
1. Find the code(s) in what the user pasted, or in the output you just produced.
2. Run `run-cli explain CODE` for each distinct code.
3. For each finding, tell the user in plain words: what it means, why it matters, exactly what to do at the reported path, and where to look. Quote the path from their output.
4. If the fix needs a decision only the user can make (an attestation, PII, a table type, which source is authoritative), ask them. Never decide for them.
5. If the code is unknown to `explain`, say so and give the message and fix text exactly as the tool printed them. Do not invent an explanation.

## Rules
- Never tell the user to ignore a finding without saying why it is safe (for example `UNKNOWN_FIELD` on `datasets` in a dataset manifest is expected, but the file must not be exported).
- Do not claim a manifest is accepted by the Firmwide Data Catalog.
- Do not edit the user's files unless they ask you to.
