# CI

Everything the pipeline needs is in two scripts that work on Linux and on Windows with Git Bash. Use them on any approved CI (Jenkins, GitLab, Azure DevOps, a self-hosted runner)
and you do not need the GitHub workflow at all.

| Script | What it does |
|--------|--------------|
| `build-without-gradle.sh` | Compiles with `javac`, runs the test runner, packages `build/plain/casc-generator.jar` with the `jar` tool. Overrides: `JAVAC`, `JAR_TOOL`, `JAVA`, `JAVAC_FLAGS`. |
| `verify.sh` | Checks a built jar (`CASC_JAR`, default `build/libs/casc-generator.jar`): version, capabilities (unavailable ones must be reported), the three agent tool examples with `--fail-on-errors`, exit code 3 for a missing input, and that the draft manifest generated from the sample sources is **byte-identical** to `expected-manifest.sha256`. |

Gradle steps (when Gradle is approved on the runner): `gradle wrapper` once, then `./gradlew jar unitTests`. Run the same two scripts afterwards.

## Equivalent output on Windows and Linux
`expected-manifest.sha256` was produced on Linux. Running `verify.sh` on Windows with the same sources must give the same hash: manifests use LF, UTF-8, stable ordering and no host paths.
`.gitattributes` forces LF for the sample sources, which matters because a multi-line CSV value would otherwise change. If the hash legitimately changes because the output format changed,
regenerate it on one OS (command in `verify.sh`) and confirm the other OS reproduces it before committing.

## GitHub Actions (optional)
`.github/workflows/ci.yml` runs on `ubuntu-latest` and `windows-latest`. It uses no marketplace actions: it checks out with plain `git` and the job token, takes the JDK preinstalled on the runner
(`JAVA_HOME_17_X64`) and the runner's Gradle. Public runners and every action are supply chain dependencies that need your organization's approval; if you add `actions/checkout`, `actions/setup-java`
or similar, pin them to a reviewed commit SHA. The workflow has **not been executed** by the author.

## What the pipeline cannot show
Passing CI shows that the code builds, the tests pass and the output is reproducible on the runners used. It says nothing about the official CasC schema or acceptance by the FDC service.
