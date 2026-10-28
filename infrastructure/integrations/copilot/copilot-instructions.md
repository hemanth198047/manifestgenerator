# Repository instructions for GitHub Copilot

Copy this file to `.github/copilot-instructions.md`, and the `instructions/` folder to `.github/instructions/`.
To give Copilot the full workflow, also copy `../claude/casc-manifest-generator/` to `.github/skills/casc-manifest-generator/`: it uses the same `SKILL.md` format.

## What this project is
CasC Multi-Source Manifest Generator: a JDK-only (Java 21) application that extracts metadata from SQL, CSV/TSV, text/Markdown and OpenAPI JSON and drafts CasC manifests.
It has no third-party libraries. Build and test with Gradle (`gradle wrapper`, then `./gradlew jar unitTests`) or see `docs/VERIFICATION.md` for the plain `javac` commands.

## Rules for suggestions
- Do not add dependencies, Gradle plugins, annotation processors or external repositories. Use the JDK only.
- Keep logic in Java; scripts stay thin. Use `java.nio.file.Path`, UTF-8 and LF line endings so Windows and Linux behave the same.
- Never weaken TLS or certificate validation, never add hosts to allowlists on the user's behalf, and never execute uploaded SQL or document content.
- Never infer a sensitive-data attestation, PII element or lineage. Report uncertainty as a finding.
- Validation is LOCAL against a provisional profile. Do not claim FDC acceptance.

## Calling the application from an agent
Use the documented commands only: `tools`, `tool ingest_sources|validate_manifest_payload|generate_casc_manifest --request FILE`, `ingest`, `validate`, `export`, `capabilities`.
The tool contracts are JSON in and JSON out and are not an MCP server. See `docs/INTEGRATION.md`.
