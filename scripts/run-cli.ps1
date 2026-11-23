# Runs the built CLI (jllm-cli\target) with the fetched GraalJS set on the class path. Build first: mvn -DskipTests package
# Usage: scripts\run-cli.ps1 [cli arguments]    Edition: JLLM_GRAAL_EDITION (default community)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = & (Join-Path $PSScriptRoot 'graal-fetch.ps1') -DirOnly
$version = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<revision>(.*?)</revision>')).Groups[1].Value
$jar = Join-Path $root "jllm-cli\target\jllm-cli-$version.jar"
if (-not (Test-Path $jar)) { throw "missing ${jar}: run mvn -DskipTests package" }
$ed = if ($env:JLLM_GRAAL_EDITION) { $env:JLLM_GRAAL_EDITION } else { 'community' }
Write-Host "GraalJS $ed $(Split-Path -Leaf (Split-Path -Parent $dir)) from $dir"
$plugins = @(); $pd = Join-Path $root 'target\plugins'
if (Test-Path $pd) { $plugins = @('--plugin-dir', $pd) }
& java "-Djllm.graal.dir=$dir" -cp "$jar;$dir\*" org.kendar.llm.cli.Main @plugins @args
exit $LASTEXITCODE
