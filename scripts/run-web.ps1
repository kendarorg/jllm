# Runs the built web app (jllm-web\target) with the GraalJS set of .graal\<graalvm.version>\repo on the loader path.
# Needs scripts\setup first, then mvn -DskipTests package. Usage: scripts\run-web.ps1 [web arguments]
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pom = Get-Content (Join-Path $root 'pom.xml') -Raw
$gver = ([regex]::Match($pom, '<graalvm.version>(.*?)</graalvm.version>')).Groups[1].Value
$dir = Join-Path $root ".graal\$gver"
if (-not (Test-Path (Join-Path $dir 'repo'))) { throw "GraalJS $gver is not set up: run scripts\setup.ps1" }
$version = ([regex]::Match($pom, '<revision>(.*?)</revision>')).Groups[1].Value
$jar = Join-Path $root "jllm-web\target\jllm-web-$version.jar"
if (-not (Test-Path $jar)) { throw "missing ${jar}: run mvn -DskipTests package" }
$jars = @(Get-ChildItem (Join-Path $dir 'repo') -Recurse -Filter *.jar | Sort-Object FullName | ForEach-Object { $_.FullName })
Write-Host "GraalJS $gver from $dir"
$plugins = @(); $pd = Join-Path $root 'target\plugins'
if (Test-Path $pd) { $plugins = @("--jllm.plugin-dir=$pd") }
& java "-Dloader.path=$($jars -join ',')" "-Djllm.graal.dir=$dir" -jar $jar @plugins @args
exit $LASTEXITCODE
