# Windows verification checklist

The author could only run Linux. Windows has **not** been verified. Please run these steps on a Windows machine and record the results; any failure is a defect to report.
None of the steps needs WSL, Git Bash or administrator rights except the optional Bash route in step 5.

| # | Step (PowerShell, from the repository root) | Expected | Result |
|---|---------------------------------------------|----------|--------|
| 1 | `java -version` and `gradle --version` | JDK 21 or newer; your approved Gradle | |
| 2 | `gradle wrapper ...` (see `docs/BUILD.md`), then `gradlew.bat jar unitTests` | `BUILD SUCCESSFUL`; the runner prints `122 passed` (or `121 passed, 1 skipped` if the console locale cannot encode Unicode file names) | |
| 3 | `powershell -File infrastructure\ci\verify.ps1` | `verify: all checks passed (dc6bdaf5...)` with the same hash as Linux | |
| 4 | `powershell -File infrastructure\ci\build-without-gradle.ps1` then `$env:CASC_JAR="build\plain\casc-generator.jar"; powershell -File infrastructure\ci\verify.ps1` | all tests pass; the plain-built jar also passes verify | |
| 5 | Optional: `bash infrastructure/ci/verify.sh` in Git Bash | same hash | |
| 6 | `infrastructure\launchers\run-cli.bat version`, then `run-cli.bat validate --input "C:\path with spaces\m.json"`, then `echo %ERRORLEVEL%` | version prints; the exit code of `validate` is propagated (0, 1 or 3) | |
| 7 | `infrastructure\launchers\run-web.bat --port 8080`, open http://127.0.0.1:8080/wizard in a browser, run the sample files through the five steps | pages render; a manifest downloads; no firewall prompt for loopback | |
| 8 | A Unicode file name: `ingest --input-files ".\spécifications\données.csv"` | the table is named `données`; no garbled characters in the output file | |
| 9 | Open the output file while the program writes to it (locked file) | a clear error message and exit code 3; no partial file | |
| 10 | Check out with `core.autocrlf=true`, run step 3 again | same hash (`.gitattributes` forces LF) | |

Also report: Windows edition and build, JDK vendor and version, Gradle version, and the first line of any failure.
Known untested risks: NTFS junctions inside scanned directories (symbolic links are skipped, but junction behaviour is unverified), `127.0.0.2` loopback for one token test (it skips itself if unreachable),
and the two PowerShell scripts, which have never been run.
