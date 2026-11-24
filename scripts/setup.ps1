# One-time setup: downloads the GraalJS libraries into .downloaded-libs\graal\<graalvm.version>\ (git-ignored) and runs the first build.
# Usage: scripts\setup.ps1 [-Edition community] [-AcceptLicense] [-NoBuild]
# - the version is graalvm.version of the root pom; run this again after it changes
# - default edition: commercial (Oracle GraalVM Free Terms and Conditions, asked y/N before any download).
#   -Edition community needs no terms (weaker isolation: no hard memory separation)
# - -AcceptLicense accepts the commercial terms silently; without it and without a terminal the script fails
# - -NoBuild skips "mvn -DskipTests install"
# The GraalJS libraries are not in the repository: when .downloaded-libs\graal\ holds them you downloaded them here and accepted the
# terms of the chosen edition.
param(
  [ValidateSet('community', 'commercial')][string]$Edition = 'commercial',
  [switch]$AcceptLicense,
  [switch]$NoBuild,
  [ValidateSet('mysql', 'postgres', 'all', 'none')][string]$Sql = 'all'
)
# -Sql: also downloads the JDBC drivers of the SQL store into .downloaded-libs\<db>\<version>\ (default all; SQLite is
# bundled): scripts\fetch-sql-driver.ps1. MySQL is GPL with the FOSS exception: asked y/N unless -AcceptLicense.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<graalvm.version>(.*?)</graalvm.version>')).Groups[1].Value
if (-not $version) { throw "graalvm.version not found in $root\pom.xml" }
$ed = if ($Edition -eq 'community') { 'community' } else { 'oracle' }
if ($ed -eq 'oracle' -and -not $AcceptLicense) {
  Write-Host "GraalJS $version (Oracle GraalVM) is under the Oracle GraalVM Free Terms and Conditions:"
  Write-Host '  https://www.oracle.com/downloads/licenses/graal-free-license.html'
  if ([Console]::IsInputRedirected) { throw 'no terminal to ask on: read the terms and run again with -AcceptLicense' }
  $answer = Read-Host 'Do you accept these terms? [y/N]'
  if ($answer -notmatch '^(y|yes)$') { throw 'terms not accepted: nothing downloaded' }
}
$graal = Join-Path $root '.downloaded-libs/graal'
$target = Join-Path $graal $version
New-Item -ItemType Directory -Force $target | Out-Null
& (Join-Path $root 'release\graaljs\resolve.ps1') -Edition $ed -Out $target -AcceptTerms -Repo (Join-Path $target 'repo') | Out-Host
# other versions (and the old .downloaded-libs\graal\<version>\<edition> layout) are obsolete
Get-ChildItem $graal -Directory | Where-Object { $_.Name -ne $version } | ForEach-Object { Write-Host "removing old $($_.FullName)"; Remove-Item -Recurse -Force $_.FullName }
Get-ChildItem $target -Directory | Where-Object { $_.Name -ne 'repo' } | ForEach-Object { Write-Host "removing old $($_.FullName)"; Remove-Item -Recurse -Force $_.FullName }
if ($Sql -ne 'none') {
  $sqlArgs = @{ Which = $Sql }
  if ($AcceptLicense) { $sqlArgs.AcceptLicense = $true }
  & (Join-Path $root 'scripts\fetch-sql-driver.ps1') @sqlArgs
}
if (-not $NoBuild) {
  Set-Location $root
  & mvn -DskipTests install
  exit $LASTEXITCODE
}
