# Downloads the JDBC drivers of the SQL store from Maven Central, where the web app finds them: source checkout
# .downloaded-libs\<db>\<version>\ (git-ignored); release zip lib-ext\ (the GraalJS jars there are left alone). SQLite is
# bundled and needs no download. At run time only the driver of the jdbc url is loaded.
# Usage: scripts\fetch-sql-driver.ps1 [mysql|postgres|all] [-AcceptLicense] [-BaseUrl URL]   (default: all)
# MySQL (mysql-connector-j) is GPLv2 with the Universal FOSS Exception, never bundled: whoever fetches it accepts those
# terms (asked y/N; -AcceptLicense accepts silently; without a terminal and without the flag it fails).
# PostgreSQL (BSD-2-Clause) needs no acceptance.
# Version and SHA-256 come from the manifests (web\jllm-sql\src\main\resources\db\drivers\<db>.manifest; in a release zip
# lib-ext\manifests\<db>.manifest, copied to lib-ext\). A download whose SHA-256 differs is deleted and the script fails.
param(
  [Parameter(Position = 0)][ValidateSet('mysql', 'postgres', 'all')][string]$Which = 'all',
  [switch]$AcceptLicense,
  [string]$BaseUrl = 'https://repo1.maven.org/maven2'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$here = $PSScriptRoot

function Get-SqlDriver([string]$db) {
  $releaseManifest = Join-Path $here "lib-ext\manifests\$db.manifest"
  $release = Test-Path $releaseManifest
  if ($release) {
    $manifest = $releaseManifest; $out = Join-Path $here 'lib-ext'
  } else {
    $root = Split-Path -Parent $here
    $manifest = Join-Path $root "web\jllm-sql\src\main\resources\db\drivers\$db.manifest"
    if (-not (Test-Path $manifest)) { throw "manifest not found: $manifest" }
    $prop = if ($db -eq 'postgres') { 'postgresql.version' } else { 'mysql.version' }
    $pv = ([regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), "<$prop>(.*?)</$prop>")).Groups[1].Value
    $mv = (Get-Content $manifest | Where-Object { $_ -like 'version=*' } | ForEach-Object { $_.Substring(8) } | Select-Object -First 1)
    if ($pv -ne $mv) { throw "$prop $pv (root pom) differs from the manifest version $mv" }
    $out = Join-Path $root (Join-Path ".downloaded-libs\$db" $mv)
  }
  $lines = @(Get-Content $manifest)
  $entry = $lines | Where-Object { $_ -match '^[0-9a-f]{64}\s+\S+$' } | Select-Object -First 1
  $sum, $path = $entry -split '\s+', 2
  $jar = Split-Path $path -Leaf
  if (($lines -contains 'accept=required') -and -not $AcceptLicense) {
    Write-Host ("$jar is under: " + ($lines | Where-Object { $_ -like 'license=*' } | ForEach-Object { $_.Substring(8) }))
    Write-Host ("  " + ($lines | Where-Object { $_ -like 'terms=*' } | ForEach-Object { $_.Substring(6) }))
    if ([Console]::IsInputRedirected) { throw 'no terminal to ask on: read the terms and run again with -AcceptLicense' }
    if ((Read-Host 'Do you accept these terms? [y/N]') -notmatch '^(y|yes)$') { throw "terms not accepted: $jar not downloaded" }
  }
  New-Item -ItemType Directory -Force $out | Out-Null
  $tmp = Join-Path $out "$jar.part"
  try {
    Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/$path" -OutFile $tmp
    if ((Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower() -ne $sum) {
      Remove-Item -Force $tmp
      throw "SHA-256 mismatch for ${path}: file deleted, nothing changed"
    }
    $old = if ($db -eq 'mysql') { 'mysql-connector-j-*.jar' } else { 'postgresql-*.jar' }
    Get-ChildItem $out -File -Filter $old | Remove-Item -Force
    Move-Item $tmp (Join-Path $out $jar)
    if ($release) { Copy-Item $manifest (Join-Path $out "$db.manifest") -Force }
    Write-Host "$jar in $out"
  } finally {
    if (Test-Path $tmp) { Remove-Item -Force $tmp -ErrorAction SilentlyContinue }
  }
}

if ($Which -eq 'all') { Get-SqlDriver 'mysql'; Get-SqlDriver 'postgres' } else { Get-SqlDriver $Which }
