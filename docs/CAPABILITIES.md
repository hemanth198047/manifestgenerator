# Capability Matrix (end of Phase 1)

| Capability | Dependency | Status | Build enablement | When disabled | Verified |
|------------|------------|--------|------------------|---------------|----------|
| Domain model, provisional profile | JDK only | Baseline | always | n/a | Not executed |
| JSON output | Jackson (via starter-web) | Baseline, Phase 2 | always | n/a | Not yet built |
| YAML | Jackson YAML | Optional, Phase 2 | planned `-PenableYaml` | JSON only, UI says YAML unavailable | Not built |
| CSV/TSV, SQL, XLSX, DOCX, PDF, HTML | various | Phases 3-4 | planned flags | Explicit unavailable message | Not built |

No Gradle build, test run, or OS verification has been executed in the authoring sandbox (no javac, no repository access).
