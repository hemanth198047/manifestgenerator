# Windows-native equivalent of build-without-gradle.sh: compile, test and package with only the JDK tools.
# Overrides: $env:JAVAC, $env:JAR_TOOL, $env:JAVA. Requires JDK 21 or newer on PATH (or those variables).
# Optional reader adapters (off by default, see docs\ADAPTERS.md):
#   $env:ADAPTERS = 'poi,pdfbox,jsoup,jsqlparser'   (or 'all')
#   $env:ADAPTER_LIB_DIR = 'D:\approved\jars'       directory of approved jars, or $env:ADAPTER_CP = 'a.jar;b.jar'
#   $env:SKIP_TEST_FILES = 'PoiDocxTests'           regex of adapter test files to leave out
# NOT EXECUTED by the author: no PowerShell was available while writing it. Run it once and report the result.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$javac = if ($env:JAVAC) { $env:JAVAC } else { 'javac' }
$jarTool = if ($env:JAR_TOOL) { $env:JAR_TOOL } else { 'jar' }
$java = if ($env:JAVA) { $env:JAVA } else { 'java' }
$adapters = if ($env:ADAPTERS) { $env:ADAPTERS } else { '' }
if ($adapters -eq 'all') { $adapters = 'poi,pdfbox,jsoup,jsqlparser' }
$adapterCp = if ($env:ADAPTER_CP) { $env:ADAPTER_CP } else { '' }
if ($env:ADAPTER_LIB_DIR) {
    $jars = (Get-ChildItem -Path $env:ADAPTER_LIB_DIR -Recurse -Filter *.jar | Sort-Object FullName | ForEach-Object FullName) -join ';'
    $adapterCp = if ($adapterCp) { "$jars;$adapterCp" } else { $jars }
}
if ($adapters -and -not $adapterCp) { Write-Host "ADAPTERS=$adapters needs ADAPTER_LIB_DIR or ADAPTER_CP (approved jars). Nothing is downloaded."; exit 2 }
$skip = if ($env:SKIP_TEST_FILES) { $env:SKIP_TEST_FILES } else { '' }
$out = 'build\plain'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path "$out\main", "$out\test" | Out-Null

# javac argument files treat backslashes as escapes, so write forward slashes and quote every path.
function Write-ArgFile($dirs, $target, $exclude) {
    $files = foreach ($d in $dirs) { if (Test-Path $d) { Get-ChildItem -Path $d -Recurse -Filter *.java } }
    $files | Where-Object { -not $exclude -or $_.Name -notmatch $exclude } |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } | Set-Content -Encoding ascii -Path $target
}

$mainDirs = @('backend\src\main')
$testDirs = @('backend\src\test')
$strategies = @()
$analyzers = @()
foreach ($a in ($adapters -split ',' | Where-Object { $_ })) {
    switch ($a) {
        'poi'        { $strategies += 'com.example.casc.adapters.poi.PoiStrategy' }
        'pdfbox'     { $strategies += 'com.example.casc.adapters.pdfbox.PdfStrategy' }
        'jsoup'      { $strategies += 'com.example.casc.adapters.jsoup.HtmlStrategy' }
        'jsqlparser' { $analyzers += 'com.example.casc.adapters.jsqlparser.JSqlParserAnalyzer' }
        default      { Write-Host "Unknown adapter '$a'. Known: poi, pdfbox, jsoup, jsqlparser"; exit 2 }
    }
    $mainDirs += "backend\src\adapters\$a\java"
    $testDirs += "backend\src\adapters\$a\test\java"
}

Write-ArgFile $mainDirs "$out\main-files.txt" $null
$cpMain = @(); if ($adapterCp) { $cpMain = @('-cp', $adapterCp) }
& $javac -d "$out\main" -encoding UTF-8 --release 21 @cpMain "@$out\main-files.txt"
if ($LASTEXITCODE -ne 0) { Write-Host 'Compilation of the main sources failed'; exit 1 }
Copy-Item -Path 'frontend\resources\*' -Destination "$out\main" -Recurse -Force
if ($strategies -or $analyzers) {
    New-Item -ItemType Directory -Force -Path "$out\main\META-INF\services" | Out-Null
    if ($strategies) { $strategies | Set-Content -Encoding ascii "$out\main\META-INF\services\com.example.casc.ingest.SourceIngestionStrategy" }
    if ($analyzers) { $analyzers | Set-Content -Encoding ascii "$out\main\META-INF\services\com.example.casc.ingest.SqlDmlAnalyzer" }
}

Write-ArgFile $testDirs "$out\test-files.txt" $skip
$testCp = if ($adapterCp) { "$out\main;$adapterCp" } else { "$out\main" }
& $javac -d "$out\test" -cp $testCp -encoding UTF-8 --release 21 "@$out\test-files.txt"
if ($LASTEXITCODE -ne 0) { Write-Host 'Compilation of the tests failed'; exit 1 }

$runCp = if ($adapterCp) { "$out\test;$out\main;$adapterCp" } else { "$out\test;$out\main" }
& $java '-Dfile.encoding=UTF-8' -cp $runCp com.example.casc.TestRunner
if ($LASTEXITCODE -ne 0) { Write-Host 'Tests failed'; exit 1 }

# Same suite with main and test classes in separate class loaders: catches package-private access across them.
$splitMain = if ($adapterCp) { "$out\main;$adapterCp" } else { "$out\main" }
& $java '-Dfile.encoding=UTF-8' -cp "$out\test" com.example.casc.SplitLoaderRunner $splitMain "$out\test"
if ($LASTEXITCODE -ne 0) { Write-Host 'Tests failed with separate class loaders'; exit 1 }

# Manifest: Main-Class and, with adapters, a Class-Path of lib/ jars (wrapped at 70 characters).
$lines = @('Main-Class: com.example.casc.Main', 'Implementation-Version: plain')
if ($adapterCp) {
    New-Item -ItemType Directory -Force -Path "$out\lib" | Out-Null
    $names = @()
    foreach ($j in ($adapterCp -split ';' | Where-Object { $_ })) { Copy-Item $j "$out\lib" -Force; $names += ('lib/' + (Split-Path $j -Leaf)) }
    $attr = 'Class-Path: ' + ($names -join ' ')
    $lines += $attr.Substring(0, [Math]::Min(70, $attr.Length))
    $rest = if ($attr.Length -gt 70) { $attr.Substring(70) } else { '' }
    while ($rest.Length -gt 0) { $take = [Math]::Min(69, $rest.Length); $lines += (' ' + $rest.Substring(0, $take)); $rest = $rest.Substring($take) }
}
($lines -join "`r`n") + "`r`n" | Set-Content -Encoding ascii -Path "$out\manifest.mf" -NoNewline
& $jarTool --create --file "$out\casc-generator.jar" --manifest "$out\manifest.mf" -C "$out\main" .
if ($LASTEXITCODE -ne 0) { Write-Host 'Packaging failed'; exit 1 }
Write-Host "Built $out\casc-generator.jar $(if ($adapters) { 'with adapters: ' + $adapters })"
