---
applyTo: "**/*manifest*.json,**/*manifest*.yaml,**/specs/**"
description: Rules for editing CasC manifests and their source specifications
---

- A manifest has the sections logicalServices, systemDataElements, interfaces, dataEntities, databases and transformations. Do not add fields that are not part of the model.
- Enum values are exact and case-sensitive: interface type SOURCING or DISTRIBUTION; table type Transaction or Reference; attestation Yes or No; subject categories Client, Worker, Third Party.
- `isDeployedOnPrem` must be stated explicitly; a missing value is not false. `cloudProvider` is required when it is false.
- Schema, table and column names are unqualified (no periods). Interface paths and entity names may contain periods.
- An attestation of Yes needs at least one PII element and one data subject category.
- Check changes with `casc-generator validate --input FILE` (exit code 1 means errors). The result is a local check, not FDC acceptance.
