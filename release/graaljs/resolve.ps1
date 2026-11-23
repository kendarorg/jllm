# Resolves the GraalJS jar set of one edition with Maven (release\graaljs\pom.xml) into a folder, re-checks the licence
# declared by every resolved pom and writes the manifest next to the jars.
# Usage: release\graaljs\resolve.ps1 -Edition community|oracle -Out DIR [-AcceptTerms]
# Same rules as resolve.sh: UPL, MIT, Apache, BSD and Unicode/ICU pass, anything else fails; the "GraalVM Free Terms and
# Conditions" is recorded as terms= and fails unless -AcceptTerms is given. The version is read from the root pom.
param(
  [Parameter(Mandatory)][ValidateSet('community', 'oracle')][string]$Edition,
  [Parameter(Mandatory)][string]$Out,
  [switch]$AcceptTerms
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$m = [regex]::Match((Get-Content (Join-Path $root 'pom.xml') -Raw), '<graalvm.version>(.*?)</graalvm.version>')
if (-not $m.Success) { throw "graalvm.version not found in $root\pom.xml" }
$version = $m.Groups[1].Value
$work = Join-Path ([IO.Path]::GetTempPath()) ("jllm-graal-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
try {
  & mvn -q -B -f (Join-Path $here 'pom.xml') "-P$Edition" "-Dgraalvm.version=$version" "-Dgraal.out=$work" `
    "-Dmdep.useRepositoryLayout=true" dependency:copy-dependencies | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'maven failed to resolve the GraalJS set' }
  $terms = @()
  foreach ($pom in (Get-ChildItem $work -Recurse -Filter *.pom | Sort-Object FullName)) {
    $names = @(([xml](Get-Content $pom.FullName -Raw)).project.licenses.license | ForEach-Object { $_.name })
    if ($names.Count -eq 0) { throw "no licence declared in $($pom.FullName)" }
    foreach ($n in $names) {
      if ($n -match 'Universal Permissive|^MIT|Apache|BSD|Unicode|ICU') { continue }
      elseif ($n -match 'GraalVM Free Terms|GFTC') { if ($terms -notcontains $n) { $terms += $n } }
      else { throw "unacceptable licence '$n' in $($pom.FullName)" }
    }
  }
  if ($terms.Count -gt 0 -and -not $AcceptTerms) {
    throw ("the $Edition set declares terms the operator must accept: " + ($terms -join '; ') +
      "`nread them, then run again with -AcceptTerms (or JLLM_ACCEPT_ORACLE_TERMS=1 in the fetch scripts)")
  }
  New-Item -ItemType Directory -Force $Out | Out-Null
  Get-ChildItem $Out -File | Where-Object { $_.Name -like '*.jar' -or $_.Name -like 'graaljs*.manifest' } | Remove-Item -Force
  $lines = @('# jllm GraalJS jar set: <sha256>  <path in Maven Central>', "edition=$Edition", "version=$version")
  if ($terms.Count -gt 0) { $lines += ('terms=' + ($terms -join '; ')) }
  $prefix = (Resolve-Path $work).Path.TrimEnd('\') + '\'
  $count = 0
  foreach ($jar in (Get-ChildItem $work -Recurse -Filter *.jar | Sort-Object FullName)) {
    Copy-Item $jar.FullName $Out
    $rel = $jar.FullName.Substring($prefix.Length).Replace('\', '/')
    $lines += ((Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower() + '  ' + $rel)
    $count++
  }
  # LF line endings: the .sh fetch script reads the same manifest
  [IO.File]::WriteAllText((Join-Path $Out "graaljs-$Edition.manifest"), (($lines -join "`n") + "`n"))
  Write-Host "GraalJS $Edition ${version}: $count jars in $Out"
} finally {
  Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
