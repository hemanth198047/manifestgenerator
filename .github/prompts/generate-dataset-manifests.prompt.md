---
mode: agent
description: Generate dataset manifests, one per table, from a manifest JSON or SQL files.
---
Use the casc-generator tools to create dataset manifests.

Ask me for anything missing, one question at a time, and reuse what I already said:
- the source: a manifest JSON, or SQL files;
- which tables: a table metadata CSV (table name, domain, boundary, upstream dataset, producer eonid) and/or a list of tables with a default domain and boundary;
- whether the tables have upstream datasets (if not, no upstreams);
- a descriptions CSV, and column metadata for system data elements, if I have them;
- the output folder, and whether existing files may be replaced;
- whether I also want some or all datasets combined into one manifest.

Then call `generate_dataset_manifests` without confirm, show me the plan and every warning explained, and only write after I say yes.
