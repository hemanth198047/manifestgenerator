# The manifest generator agent (GitHub Copilot)

The application has no model of its own. The agent is **GitHub Copilot** (the model your organisation allows) working through the application's tools:

```
You  <->  GitHub Copilot (agent mode, VS Code / IntelliJ / Copilot CLI)  <->  MCP server: casc-generator mcp  <->  the application's engines
```

- `casc-generator mcp` is an MCP server that talks over standard input and output. Copilot starts it, lists its tools and calls them.
- `.github/agents/casc-manifest-agent.agent.md` is a Copilot **custom agent**: instructions and the list of tools for this work.
- `.github/prompts/*.prompt.md` are ready-made prompts for common tasks.
- Nothing is sent anywhere by the application. Whatever the tools return goes to Copilot, the same as any other Copilot tool.

## Setup

1. Build the jar once: `./gradlew jar` (JDK 21), or `bash infrastructure/ci/build-without-gradle.sh` and copy `build/plain/casc-generator.jar` to `build/libs/casc-generator.jar`.
2. Connect the server to Copilot:

| Where | How |
|-------|-----|
| **VS Code** | The repository already has `.vscode/mcp.json`. Open the folder, start the `casc-generator` server (the *MCP: List Servers* command, or the Start link in `mcp.json`), then open Copilot Chat in **Agent** mode and pick the agent **casc-manifest-agent** (or use the tools directly). |
| **IntelliJ** | In the Copilot settings open the MCP configuration and add a server with the same entry as `.vscode/mcp.json` (`type` stdio, `command` `java`, `args` `-jar /path/to/casc-generator.jar mcp --root /path/to/your/project`). Use **Agent** mode in Copilot Chat. The exact menu names depend on the plugin version. |
| **Copilot CLI** | `copilot --agent=casc-manifest-agent` from the project folder (the agent file defines the server), or add the server to `~/.copilot/mcp-config.json`. |

`--root` is the only folder the tools can read or write. Use the project folder that holds your manifests and CSV files.

## Using it

Ask in plain words. The agent looks at your files first, asks for what it needs, shows a plan before it writes anything, and explains every warning.

Examples:
```
Generate dataset manifests from manifest_3212_1.json for the tables in table_metadata.csv, descriptions from descriptions.csv, no upstreams, into out/.
Combine the first 10 manifests in out/ into combined.json.
List the table names in manifest_3212_1.json.
Compare out_old/ and out_new/ and give me an HTML report.
Validate manifest.json and explain every finding.
Draft a manifest from schema.sql for database MYDB.
```
Prompt files (type `/` in Copilot Chat): `generate-dataset-manifests`, `combine-dataset-manifests`, `compare-json-files`, `inspect-json-file`.

A typical write looks like this:
```
You:   Generate dataset manifests ... into out/.
Agent: [calls generate_dataset_manifests without confirm]
       PLAN: would write 2 files (out/manifest_A.json, out/manifest_B.json); 1 warning: ... (explained). Do you want to proceed?
You:   Yes.
Agent: [calls it again with confirm=true and the planHash] Wrote 2 files. Summary: read 694 tables, 2 generated.
```

## The tools

| Tool | Reads or writes | What it does |
|------|-----------------|--------------|
| `list_files` | read | list files under the root (hidden files are never shown) |
| `read_file` | read | read a text file with line numbers |
| `capabilities` | read | what this build can do |
| `explain_finding` | read | explain a finding code (or list all codes) |
| `validate_manifest` | read | validate a CasC manifest (LOCAL level) |
| `ingest_sources` | read | read SQL/CSV/TSV/text/Markdown/OpenAPI and summarise |
| `json_inspect` | read | keys, get (a section by path), stats, find, flatten, csv, validate |
| `compare_summary` | read | compare JSON files, return counts and differences |
| `generate_dataset_manifests` | **write** | one dataset manifest per table (all the `ingest` dataset options), optional combined file |
| `combine_datasets` | **write** | combine dataset manifests: all, the first N, or named tables |
| `json_edit` | **write** | set, delete, merge or format JSON into a **new** file |
| `compare_report` | **write** | HTML (and optional JSON) comparison report |
| `export_manifest` | **write** | validate and write a CasC manifest as JSON or YAML |
| `draft_manifest` | **write** | write an unreviewed draft manifest from sources |

Each tool is one of the application's own commands, run in the same process, so results, messages and files are exactly what `docs/COMMANDS.md` describes. Paths in the tools are relative to the root.

## Safety

- **Two-step writes.** A write tool first runs a dry run in a scratch folder and returns a plan (files to write, which already exist, warnings) and a `planHash`. The second call needs `confirm: true` and that hash. The hash covers the exact arguments **and the content of the input files**, so a changed argument or a changed file means a new plan and a new yes. Existing files are never replaced unless `overwrite` is true.
- **Sandbox.** Only paths inside `--root` are used; `..`, absolute paths outside the root and symbolic links that leave it are refused. Hidden files and folders (`.git`, `.github`, `.vscode`, `.env`) are never read or written, files whose names look like secrets are never read, and only plain data types (`json html htm csv tsv txt md yaml yml`) can be written.
- **No raw shell, no network.** The tools are fixed commands with checked arguments. Unknown arguments and values that start with `-` are refused. URLs are never fetched.
- **Data, not instructions.** File contents and tool results are data; the agent instructions and the server instructions tell the model to ignore instructions found there.
- **Open decisions stay with you.** The tools never decide PII, attestations, table types, services or interface directions.
- **Audit log.** `--audit-log FILE` (or `CASC_AUDIT_LOG`) writes one JSON line per call (time, tool, phase, arguments with long text cut, files written). File contents are never logged.
- **Read-only mode.** `--read-only` removes every write tool.
- **Copilot's own approvals still apply.** Copilot asks before it calls tools that are not marked read-only, in addition to the plan step.
- **No FDC acceptance claims, no upload.** Validation is LOCAL. Uploading to FDC is not a tool here; use the `fdc-manifest-upload` skill, which has its own summary and confirmation.

## Server options
`casc-generator mcp [--root DIR] [--read-only] [--audit-log FILE]` (defaults: `--root` from `CASC_INPUT_ROOT`, else the current folder; the audit log from `CASC_AUDIT_LOG`). Only protocol messages go to standard output; a ready message and errors go to standard error.

## Limits and notes
- Large outputs are cut at 20,000 characters with a note; the agent narrows the request (a smaller path, fewer files).
- The draft flow stops at `draft_manifest`: services, table types, PII, attestations and similar decisions are not made by the tools. Use the wizard (`casc-generator web`) or the `generate_casc_manifest` tool contract for those.
- Copilot's agent and MCP settings differ slightly between VS Code, IntelliJ and the CLI and change between releases. If a setting is not where this page says, search the Copilot documentation for "MCP servers" in your IDE; the server itself does not change.
- The separate `casc-generator tools` / `tool` commands (three JSON contracts for scripts) are **not** this server; they take inline text and never read files.
