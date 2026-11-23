# Prints the Maven flags that run the tests of the js plugin and of jllm-eval on the fetched GraalJS set (fetching it first):
#   mvn (scripts\graal-flags.ps1) -Peval -pl core/jllm-eval -am test
# Edition: JLLM_GRAAL_EDITION (default community). The edition and version line goes to the host, not the output.
$ErrorActionPreference = 'Stop'
$dir = & (Join-Path $PSScriptRoot 'graal-fetch.ps1') -DirOnly
$ed = if ($env:JLLM_GRAAL_EDITION) { $env:JLLM_GRAAL_EDITION } else { 'community' }
Write-Host "GraalJS $ed $(Split-Path -Leaf (Split-Path -Parent $dir)) in $dir"
"-Dgraal.dir=$($dir.Replace('\', '/'))"
