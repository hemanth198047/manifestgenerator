# Windows-native equivalent of build-without-gradle.sh: compile, test and package with only the JDK tools.
# Overrides: $env:JAVAC, $env:JAR_TOOL, $env:JAVA. Requires JDK 17+ on PATH (or those variables).
# NOT EXECUTED by the author: no PowerShell was available while writing it. Run it once and report the result.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$javac = if ($env:JAVAC) { $env:JAVAC } else { 'javac' }
$jarTool = if ($env:JAR_TOOL) { $env:JAR_TOOL } else { 'jar' }
$java = if ($env:JAVA) { $env:JAVA } else { 'java' }
$out = 'build\plain'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path "$out\main", "$out\test" | Out-Null

# javac argument files treat backslashes as escapes, so write forward slashes and quote every path.
function Write-ArgFile($sourceDir, $target) {
    Get-ChildItem -Path $sourceDir -Recurse -Filter *.java |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } |
        Set-Content -Encoding ascii -Path $target
}

Write-ArgFile 'backend\src\main' "$out\main-files.txt"
& $javac -d "$out\main" -encoding UTF-8 --release 17 "@$out\main-files.txt"
if ($LASTEXITCODE -ne 0) { Write-Host 'Compilation of the main sources failed'; exit 1 }
Copy-Item -Path 'frontend\resources\*' -Destination "$out\main" -Recurse -Force

Write-ArgFile 'backend\src\test' "$out\test-files.txt"
& $javac -d "$out\test" -cp "$out\main" -encoding UTF-8 --release 17 "@$out\test-files.txt"
if ($LASTEXITCODE -ne 0) { Write-Host 'Compilation of the tests failed'; exit 1 }

& $java '-Dfile.encoding=UTF-8' -cp "$out\test;$out\main" com.example.casc.TestRunner
if ($LASTEXITCODE -ne 0) { Write-Host 'Tests failed'; exit 1 }

"Main-Class: com.example.casc.Main`r`nImplementation-Version: plain`r`n" | Set-Content -Encoding ascii -Path "$out\manifest.mf" -NoNewline
& $jarTool --create --file "$out\casc-generator.jar" --manifest "$out\manifest.mf" -C "$out\main" .
if ($LASTEXITCODE -ne 0) { Write-Host 'Packaging failed'; exit 1 }
Write-Host "Built $out\casc-generator.jar"
