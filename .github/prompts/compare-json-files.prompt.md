---
mode: agent
description: Compare several JSON files and write an HTML report.
---
Use the casc-generator tools to compare JSON files.

Ask me which files (the first is the baseline) and whether to compare only one section. First call `compare_summary` and explain the counts in plain words (added, removed, changed, same). Then offer the HTML report: call `compare_report` without confirm, show me the plan, and write only after I say yes.
