# Runs the built web app (jllm-web\target) with the fetched GraalJS set on the loader path. Build first: mvn -DskipTests package
# Usage: scripts\run-web.ps1 [web arguments]    Edition: JLLM_GRAAL_EDITION (default community)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = & (Join-Path $PSScriptRoot 'graal-fetch.ps1') -DirOnly
$version = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<revision>(.*?)</revision>')).Groups[1].Value
$jar = Join-Path $root "jllm-web\target\jllm-web-$version.jar"
if (-not (Test-Path $jar)) { throw "missing ${jar}: run mvn -DskipTests package" }
$ed = if ($env:JLLM_GRAAL_EDITION) { $env:JLLM_GRAAL_EDITION } else { 'community' }
Write-Host "GraalJS $ed $(Split-Path -Leaf (Split-Path -Parent $dir)) from $dir"
$plugins = @(); $pd = Join-Path $root 'target\plugins'
if (Test-Path $pd) { $plugins = @("--jllm.plugin-dir=$pd") }
& java "-Dloader.path=$dir" "-Djllm.graal.dir=$dir" -jar $jar @plugins @args
exit $LASTEXITCODE
