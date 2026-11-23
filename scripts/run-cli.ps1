# Runs the built CLI (jllm-cli\target) with the GraalJS set of .graal\<graalvm.version>\repo on the class path.
# Needs scripts\setup first, then mvn -DskipTests package. Usage: scripts\run-cli.ps1 [cli arguments]
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pom = Get-Content (Join-Path $root 'pom.xml') -Raw
$gver = ([regex]::Match($pom, '<graalvm.version>(.*?)</graalvm.version>')).Groups[1].Value
$dir = Join-Path $root ".graal\$gver"
if (-not (Test-Path (Join-Path $dir 'repo'))) { throw "GraalJS $gver is not set up: run scripts\setup.ps1" }
$version = ([regex]::Match($pom, '<revision>(.*?)</revision>')).Groups[1].Value
$jar = Join-Path $root "jllm-cli\target\jllm-cli-$version.jar"
if (-not (Test-Path $jar)) { throw "missing ${jar}: run mvn -DskipTests package" }
$jars = @(Get-ChildItem (Join-Path $dir 'repo') -Recurse -Filter *.jar | Sort-Object FullName | ForEach-Object { $_.FullName })
Write-Host "GraalJS $gver from $dir"
$plugins = @(); $pd = Join-Path $root 'target\plugins'
if (Test-Path $pd) { $plugins = @('--plugin-dir', $pd) }
& java "-Djllm.graal.dir=$dir" -cp ((@($jar) + $jars) -join ';') org.kendar.llm.cli.Main @plugins @args
exit $LASTEXITCODE
