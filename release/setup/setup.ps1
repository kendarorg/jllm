# jllm setup: unzips the distribution that sits next to this script, asks which GraalJS edition to use for run_js and
# downloads it (Maven Central, SHA-256 verified) into the unzipped lib-ext\. Works on Windows PowerShell and PowerShell 7.
# Layout: next to this script jllm-<version>.zip; after setup, jllm-<version>/ holds the jars, run-*.sh/.cmd, plugins/,
# settings/, webauth/ and lib-ext/ (filled with the chosen GraalJS edition; manifests/ lists the pinned jars).
# Usage: .\setup.ps1 [-Zip FILE] [-Dir DIR] [-Edition community|commercial] [-AcceptLicense] [-Repo URL]
# Without a terminal, give -Edition (and -AcceptLicense for commercial).
param(
  [string]$Zip,
  [string]$Dir,
  [ValidateSet('community', 'commercial')][string]$Edition,
  [switch]$AcceptLicense,
  [string]$Repo,
  [ValidateSet('mysql', 'postgres', 'all', 'none')][string]$Sql = 'all'
)
# -Sql: the JDBC drivers of the SQL store downloaded into lib-ext\ (default all; SQLite is bundled). MySQL is GPL with the
# FOSS exception: asked y/N unless -AcceptLicense. At run time only the driver of the jdbc url is used.
$ErrorActionPreference = 'Stop'
if ($Repo) { $env:JLLM_MAVEN_REPO = $Repo }
if (-not $Zip) {
  $found = @(Get-ChildItem $PSScriptRoot -Filter 'jllm-*.zip' -File)
  if ($found.Count -ne 1) { throw 'put exactly one jllm-<version>.zip next to setup.ps1, or pass -Zip FILE' }
  $Zip = $found[0].FullName
}
if (-not (Test-Path $Zip)) { throw "missing $Zip" }
$Zip = (Resolve-Path $Zip).Path
if (-not $Dir) { $Dir = Split-Path -Parent $Zip }
New-Item -ItemType Directory -Force $Dir | Out-Null
# the question first, so nothing is unpacked or downloaded when the terms are declined
$interactive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
if (-not $Edition) {
  if (-not $interactive) { throw 'no terminal: pass -Edition (and -AcceptLicense for commercial)' }
  Write-Host 'GraalJS runs the run_js tool. Choose the edition:'
  Write-Host '  1) commercial  Oracle GraalVM, hard memory isolation (Oracle GraalVM Free Terms and Conditions)'
  Write-Host '  2) community   UPL/MIT, weaker isolation (no hard memory separation)'
  $c = Read-Host 'Edition [1]'
  $Edition = switch ($c) { { $_ -in '', '1', 'commercial' } { 'commercial' } { $_ -in '2', 'community' } { 'community' } default { throw 'unknown choice' } }
}
if ($Edition -eq 'commercial' -and -not $AcceptLicense) {
  Write-Host 'The commercial set is under the Oracle GraalVM Free Terms and Conditions:'
  Write-Host '  https://www.oracle.com/downloads/licenses/graal-free-license.html'
  if (-not $interactive) { throw 'no terminal: read the terms and pass -AcceptLicense' }
  if ((Read-Host 'Do you accept these terms? [y/N]') -notmatch '^(y|yes)$') { throw 'terms not accepted: nothing unpacked' }
}
Expand-Archive -Path $Zip -DestinationPath $Dir -Force
$home_ = Join-Path $Dir ([IO.Path]::GetFileNameWithoutExtension($Zip))
if (-not (Test-Path $home_)) { throw "$home_ not found after unzipping" }
Push-Location $home_
try {
  if ($Edition -eq 'commercial') { & (Join-Path $home_ 'fetch-graaljs.ps1') oracle -AcceptOracleTerms }
  else { & (Join-Path $home_ 'fetch-graaljs.ps1') community }
  if ($Sql -ne 'none') {
    $sqlArgs = @{ Which = $Sql }
    if ($AcceptLicense) { $sqlArgs.AcceptLicense = $true }
    if ($env:JLLM_MAVEN_REPO) { $sqlArgs.BaseUrl = $env:JLLM_MAVEN_REPO }
    & (Join-Path $home_ 'fetch-sql-driver.ps1') @sqlArgs
  }
} finally { Pop-Location }
Write-Host "jllm is ready in ${home_}: run-cli.cmd or run-web.cmd (Java 21+ needed)"
