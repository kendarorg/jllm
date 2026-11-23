# Replaces the GraalJS jars of lib-ext\ with the set of one edition, downloaded from Maven Central and verified.
# Usage: .\fetch-graaljs.ps1 community|oracle [-AcceptOracleTerms]
# Editions never mix: lib-ext\*.jar is emptied first. A download whose SHA-256 differs from the manifest is deleted and
# the script fails (lib-ext is left as it was). The oracle set may declare terms (the manifest says so): read them,
# then pass -AcceptOracleTerms. JLLM_MAVEN_REPO overrides the repository URL.
param(
  [Parameter(Mandatory, Position = 0)][ValidateSet('community', 'oracle')][string]$Edition,
  [switch]$AcceptOracleTerms
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
Set-Location $PSScriptRoot
$manifest = "lib-ext\manifests\graaljs-$Edition.manifest"
if (-not (Test-Path $manifest)) { throw "missing $manifest" }
$repo = if ($env:JLLM_MAVEN_REPO) { $env:JLLM_MAVEN_REPO } else { 'https://repo1.maven.org/maven2' }
$lines = @(Get-Content $manifest)
$terms = ($lines | Where-Object { $_ -like 'terms=*' } | ForEach-Object { $_.Substring(6) } | Select-Object -First 1)
if ($terms -and -not $AcceptOracleTerms) {
  [Console]::Error.WriteLine("the $Edition set declares terms you must accept: $terms")
  [Console]::Error.WriteLine('read them, then run again with -AcceptOracleTerms')
  exit 3
}
$stage = 'lib-ext\.download'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
try {
  $n = 0
  foreach ($line in $lines) {
    if ($line -notmatch '^([0-9a-f]{64})\s+(\S+)$') { continue }
    $sum = $Matches[1]; $path = $Matches[2]
    $name = Split-Path $path -Leaf
    $file = Join-Path $stage $name
    Write-Host "fetching $name"
    Invoke-WebRequest -UseBasicParsing -Uri "$repo/$path" -OutFile $file
    if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLower() -ne $sum) {
      Remove-Item -Force $file
      throw "SHA-256 mismatch for ${path}: file deleted, nothing changed"
    }
    $n++
  }
  if ($n -eq 0) { throw "no jar listed in $manifest" }
  Get-ChildItem lib-ext -File | Where-Object { $_.Name -like '*.jar' -or $_.Name -like 'graaljs*.manifest' } | Remove-Item -Force
  Move-Item "$stage\*.jar" lib-ext
  Copy-Item $manifest "lib-ext\graaljs-$Edition.manifest"
  $v = ($lines | Where-Object { $_ -like 'version=*' } | ForEach-Object { $_.Substring(8) } | Select-Object -First 1)
  Write-Host "GraalJS $Edition ${v}: $n jars in lib-ext"
} finally {
  if (Test-Path $stage) { Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue }
}
