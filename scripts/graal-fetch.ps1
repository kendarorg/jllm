# Fetches the GraalJS jar set into .graal\<graalvm.version>\<edition>\ (the set the release ships, same Maven pom).
# Usage: scripts\graal-fetch.ps1 [-Edition community|oracle] [-Refetch] [-AcceptOracleTerms] [-DirOnly]
# Edition: the parameter, else JLLM_GRAAL_EDITION, else community. A marker file (.complete) is written last: when it is
# there the fetch is skipped unless -Refetch. Prints one line with edition and version; -DirOnly prints only the folder.
param(
  [string]$Edition,
  [switch]$Refetch,
  [switch]$AcceptOracleTerms,
  [switch]$DirOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Edition) { $Edition = if ($env:JLLM_GRAAL_EDITION) { $env:JLLM_GRAAL_EDITION } else { 'community' } }
if ($Edition -notin 'community', 'oracle') { throw 'edition is community or oracle' }
$accept = $AcceptOracleTerms -or $env:JLLM_ACCEPT_ORACLE_TERMS -eq '1'
$version = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<graalvm.version>(.*?)</graalvm.version>')).Groups[1].Value
$dir = Join-Path $root ".graal\$version\$Edition"
$marker = Join-Path $dir '.complete'
if ($Refetch -or -not (Test-Path $marker)) {
  if (Test-Path $marker) { Remove-Item -Force $marker }
  & (Join-Path $root 'release\graaljs\resolve.ps1') -Edition $Edition -Out $dir -AcceptTerms:$accept | Out-Host
  New-Item -ItemType File -Force $marker | Out-Null
}
if ($DirOnly) { $dir } else { "GraalJS $Edition $version in $dir" }
