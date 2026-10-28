# Windows-native equivalent of verify.sh (no Bash, WSL or Git Bash needed). Works in Windows PowerShell 5.1 and PowerShell 7.
# Overrides: $env:CASC_JAR (default build\libs\casc-generator.jar), $env:JAVA (default java).
# NOT EXECUTED by the author: no PowerShell was available while writing it. Run it once and report the result.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$jar  = if ($env:CASC_JAR) { $env:CASC_JAR } else { 'build\libs\casc-generator.jar' }
$java = if ($env:JAVA) { $env:JAVA } else { 'java' }
if (-not (Test-Path -LiteralPath $jar)) { Write-Host "Jar not found: $jar"; exit 2 }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('casc-verify-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null

function Fail($message) { Write-Host "verify: FAILED - $message"; exit 1 }

try {
    & $java -jar $jar version
    if ($LASTEXITCODE -ne 0) { Fail 'version' }

    & $java -jar $jar capabilities --json | Out-File -Encoding ascii -FilePath "$out\capabilities.json"
    if (-not (Select-String -Path "$out\capabilities.json" -Pattern '"UNAVAILABLE"' -Quiet)) { Fail 'unavailable capabilities must be reported' }

    & $java -jar $jar ingest --input-files frontend\resources\static\samples --db-name TRADE_DB --db-type Oracle `
        --default-schema trade --output "$out\manifest.json" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'ingest of the sample sources' }
    $actual   = (Get-FileHash -Algorithm SHA256 -LiteralPath "$out\manifest.json").Hash.ToLowerInvariant()
    $expected = ((Get-Content -LiteralPath 'infrastructure\ci\expected-manifest.sha256' -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
    if ($actual -ne $expected) { Fail "the draft manifest differs from the recorded hash: $actual != $expected" }

    foreach ($tool in 'ingest_sources', 'validate_manifest_payload', 'generate_casc_manifest') {
        & $java -jar $jar tool $tool --request "infrastructure\integrations\examples\$tool.request.json" --fail-on-errors |
            Out-File -Encoding ascii -FilePath "$out\$tool.json"
        if ($LASTEXITCODE -ne 0) { Fail "tool $tool exited with $LASTEXITCODE" }
        if (-not (Select-String -Path "$out\$tool.json" -Pattern '"ok": true' -Quiet)) { Fail "tool $tool did not report ok" }
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java -jar $jar validate --input "$out\missing.json" *> $null
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 3) { Fail "expected exit code 3 for a missing input, got $code" }

    Write-Host "verify: all checks passed ($actual)"
}
finally {
    Remove-Item -Recurse -Force -LiteralPath $out -ErrorAction SilentlyContinue
}
