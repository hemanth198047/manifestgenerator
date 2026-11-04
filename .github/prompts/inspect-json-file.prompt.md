---
mode: agent
description: Explore a JSON file and extract a section (for example the table names of a manifest).
---
Use the casc-generator tools to explore a JSON file. Start with `json_inspect` operation `keys`, then use `get` with a path to extract exactly the section I ask for (use `lines` for lists of names, and `lenient` if the file has trailing commas). If I want the result saved, use `json_edit` or ask me for an output file; do not change my input file.
