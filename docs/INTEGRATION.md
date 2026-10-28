# Integrating with agents and automation (Phase 6)

The application works without any AI assistant. These integrations are optional and use only what the application already does.

## Four different things
| Mechanism | What it is | Where it lives | What it can and cannot do |
|-----------|------------|----------------|---------------------------|
| **CLI / JSON tool contracts** | Real, tested commands: `tools` and `tool NAME --request FILE\|-`. JSON in, one JSON document out. The same contracts are served at `POST /api/tool/NAME`. | `backend/.../agent`, `cli` | The actual behaviour. Anything else below only points at this. |
| **Skill / instruction files** | Text that teaches an agent when and how to call those commands, and what it must ask the user. | `infrastructure/integrations/` | Adds no capability. An agent still needs permission to run commands. |
| **MCP** | A protocol (JSON-RPC 2.0) that lets an agent host discover and call tools. | **Not implemented** | See "MCP" below. Plain JSON over stdio is not MCP and is not labelled as such. |
| **GitHub Actions / CI** | Automation that builds, tests and runs the CLI in a pipeline. | `.github/workflows/ci.yml`, `infrastructure/ci/` | Not an interface for agents. |

## Contracts
Run `casc-generator tools` for the machine-readable list. Every call prints one envelope: `{"ok":true,"tool":NAME,"result":{...}}` or `{"ok":false,"tool":NAME,"error":{"code","message"}}`.
Error codes: `INVALID_REQUEST`, `UNKNOWN_TOOL`, `UNAVAILABLE_FORMAT`, `IO_ERROR`, `INTERNAL`.
Exit codes of `tool`: 0 the tool ran (read the result), 1 `--fail-on-errors` and the result has errors, 2 invalid request or unknown tool or usage, 3 I/O error, 4 internal error.
`--request -` reads standard input; without `--request` the command fails at once, it never waits. Requests are limited to 10 MiB of UTF-8 JSON.

**Strict requests.** An unknown field anywhere is rejected with its path. There is deliberately no field that makes the application read a file: sources are inline `name` and `text`
(the name is only a label that selects the reader), and URLs work only for hosts the operator allowed with `--url-allow` or `CASC_URL_ALLOW`.

### `ingest_sources`
Request: `{"sources":[{"name","text"}], "urls":[...], "options":{...}}` (at least one source or URL; options as in `docs/INGESTION.md`).
Result: the bundle (`sources`, `tables`, `terms`, `notes`, `lineageHints`, `interfaces`, `structures`, `findings`) plus `hasErrors`. Unsupported formats and statements appear as findings.

### `validate_manifest_payload`
Request: `{"manifest": {...}}`. Result: `valid`, counts, `findings` (rule code, severity, path, message, suggestion), `validationLevel` (`LOCAL`) and the provisional `profile`.
A manifest that is not an object is reported as a finding, like the `validate` command.

### `generate_casc_manifest`
Request: `{"parsedMetadata": <ingest_sources result>, "userInputs": {...}, "format": "json"|"yaml"}`. `parsedMetadata` is read back strictly; hand-made or edited bundles that do not fit are rejected.
Result: `content`, `format`, `exportable` (false while errors exist), `validationLevel`, `profile`, `report`, and `decisions` (`id`, `message`, `acknowledgeable`, `acknowledged`).

`userInputs` (all optional; nothing is invented when something is missing):
```
{"system": {"databaseLogicalName", "databaseType", "defaultSchema", "eonId", "name"},   // eonId and name never appear in the manifest
 "services": [{"name", "isDeployedOnPrem", "cloudProvider", "systemKey"}],
 "elements": [{"name", "description", "dgcDataConcept", "dgcDataDomain", "logicalServices", "systemKey"}],
 "entities": [{"name", "description", "logicalServices", "systemDataElements", "systemKey"}],
 "transformations": [{"name", "logicalDescription", "sourceDataElements", "targetDataElements", "systemKey"}],
 "tables": {"schema.table": {"include", "tableType", "source", "columns": {"column": {"description", "systemDataElement",
            "sensitiveDataAttestation", "piiElements", "dataSubjectCategories", "sourcedFromColumns"}}}},
 "interfaces": {"name": {"include", "direction", "logicalServices", "excludePaths", "source", "fields": {"path": {same, without sourcedFromColumns}}}},
 "acknowledged": ["decision-id"]}
```
Tables, columns, interfaces and paths are named as `parsedMetadata` shows them. An unknown name, an ambiguous name (add `source`), a bad enum value or an unknown field is an `INVALID_REQUEST`.
Tables default to included; interfaces without a `direction` are left out and reported as an open decision. Open decision ids include `unreviewed-draft`, `system-context`, `no-services`, `no-table-type`,
`terms-unused`, `structures-unused`, `lineage-notes`, `attest-missing`, `attest-hint`, `source-errors`, `table-conflict:<name>` and `interface-direction:<key>`. Only some are acknowledgeable;
sources that failed, conflicts, interfaces without a direction and sensitive-looking names without an attestation must be fixed.

Typical flow for an agent: `ingest_sources`, show the findings, ask the user for everything the sources cannot say, `generate_casc_manifest`, show errors, warnings and open decisions separately, repeat.
Examples that are run by the test suite: `infrastructure/integrations/examples/`.

## Skill and instruction files
- **Claude**: `infrastructure/integrations/claude/casc-manifest-generator/SKILL.md`. Copy the folder to `.claude/skills/` (project) or `~/.claude/skills/` (personal).
- **GitHub Copilot**: `infrastructure/integrations/copilot/copilot-instructions.md` to `.github/copilot-instructions.md`, the `instructions/` folder to `.github/instructions/`, and the same skill folder to `.github/skills/casc-manifest-generator/`.
- The tests check that these files only name real commands and tools, that the skill's frontmatter fits the published limits, and that they say the contracts are not MCP.
- The skill tells the agent to ask the user for PII and attestation decisions, never to set them itself, and never to claim FDC acceptance.

**Conventions checked.** Claude Agent Skills: a folder named like the skill with `SKILL.md`, YAML `name` (64 characters at most) and `description` (1,024 at most), body under 500 lines, in `.claude/skills/`; this was read in Anthropic's skill best-practices page.
Copilot: the locations above come from secondary sources that describe current Copilot behaviour (the official GitHub documentation pages were not retrieved here), so confirm them against your Copilot version.
Conventions change; this was checked once, on the date of this phase.

## MCP
**Not implemented. Pending your decision and approval.** MCP is a protocol, not a file format. A correct stdio server means, at least:
newline-delimited JSON-RPC 2.0 where stdout carries only valid protocol messages and no message contains a line break (so every response must be compact JSON and nothing may use `System.out` for logs; logs go to stderr);
the `initialize` handshake with protocol version negotiation and capability declaration, plus `notifications/initialized` (the current draft specification describes more than one handshake era, so a server has to decide which versions it supports);
`tools/list` returning a JSON Schema per tool; `tools/call` returning content blocks and an error flag, with protocol errors separated from tool errors; `ping`, request cancellation and orderly shutdown on end of input;
and testing against at least one real host, which needs an approved MCP host or client in your environment.
An adapter over the three existing tools would be a few hundred lines of JDK-only Java plus tests, but it is the protocol surface, not the tools, that carries the risk. Until an MCP host or SDK is approved,
use the CLI or HTTP contracts. If you approve it, the work is: implement the stdio server over `AgentTools`, add protocol tests, and document the host configuration.
For actual MCP over stdio, the specification requires stdout to carry only protocol messages; the CLI in this repository prints a pretty-printed envelope and is therefore not a stdio MCP server.

## GitHub Actions and CI
`infrastructure/ci/` holds the scripts any pipeline can run. `.github/workflows/ci.yml` is an optional GitHub-hosted wrapper that uses no marketplace actions. See `infrastructure/ci/README.md`.

## What is not provided
An interactive command-line wizard was left out: the browser wizard covers that workflow, and an interactive prompt would not suit agents or CI. The CLI never prompts.
