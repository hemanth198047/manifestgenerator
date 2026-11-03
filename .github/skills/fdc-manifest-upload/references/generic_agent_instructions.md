# Instructions for other CLI agents

Any agent that can run shell commands and read a Markdown instruction file can use this skill without changes to the scripts.

1. Put the skill folder where the agent reads skills, or give the agent the path: `python3 scripts/install_skill.py --agent claude|copilot --scope user|project`, or copy/symlink the folder.
2. If the agent has no skill mechanism, paste this block into its instruction file (`AGENTS.md`, a rules file, a system prompt):

```
When the user asks to upload a manifest to FDC, follow <path>/fdc-manifest-upload/SKILL.md exactly.
Run the helper scripts in <path>/fdc-manifest-upload/scripts with python3. Never run
/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py yourself; only
`run_upload.py execute` may do that, and only after the user has said yes to the summary
from `run_upload.py plan`. Never ask for or print passwords or tokens. Never edit casc.properties.
```
3. Make sure the agent asks the user before running anything with `--confirmed yes`. The helper enforces the plan hash, but the question to the user must come from the agent.
