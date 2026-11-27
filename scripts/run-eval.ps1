# Runs the evaluation module (the GraalJS set comes from .downloaded-libs\graal\, see scripts\setup).
# Usage: scripts\run-eval.ps1 [maven arguments, default: test]
# Needs a model (see core\jllm-eval\README.md); only run it yourself, never from a build.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gver = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<graalvm.version>(.*?)</graalvm.version>')).Groups[1].Value
if (-not (Test-Path (Join-Path $root ".downloaded-libs\graal\$gver\repo"))) { throw "GraalJS $gver is not set up: run scripts\setup.ps1" }
Set-Location $root
$goals = if ($args.Count -gt 0) { $args } else { @('test') }
& mvn -Peval -pl eval/jllm-eval -am @goals
exit $LASTEXITCODE
