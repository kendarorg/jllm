# Runs the evaluation module on the fetched GraalJS set (the graal-dir profile swaps the provided set for it).
# Usage: scripts\run-eval.ps1 [maven arguments, default: test]    Edition: JLLM_GRAAL_EDITION (default community)
# Needs a model (see core\jllm-eval\README.md); only run it yourself, never from a build.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = & (Join-Path $PSScriptRoot 'graal-fetch.ps1') -DirOnly
$ed = if ($env:JLLM_GRAAL_EDITION) { $env:JLLM_GRAAL_EDITION } else { 'community' }
Write-Host "GraalJS $ed $(Split-Path -Leaf (Split-Path -Parent $dir)) from $dir"
Set-Location $root
$goals = if ($args.Count -gt 0) { $args } else { @('test') }
& mvn -Peval -pl core/jllm-eval -am "-Dgraal.dir=$($dir.Replace('\', '/'))" @goals
exit $LASTEXITCODE
