# Resolves the GraalJS jar set of one edition with Maven (release\graaljs\pom.xml) into a folder, re-checks the licence
# declared by every resolved pom and writes the manifest next to the jars.
# Usage: release\graaljs\resolve.ps1 -Edition community|oracle -Out DIR [-AcceptTerms] [-Repo DIR]
# -Repo DIR: no flat jars; DIR is replaced by a Maven layout (jars + poms) holding the gate artifact
# org.kendar.llm.graal:jllm-graal-set:<version> (jllm-graal-set.pom, written last); the manifest goes to -Out.
# Same rules as resolve.sh: UPL, MIT, Apache, BSD and Unicode/ICU pass, anything else fails; the "GraalVM Free Terms and
# Conditions" is recorded as terms= and fails unless -AcceptTerms is given. The version is read from the root pom.
param(
  [Parameter(Mandatory)][ValidateSet('community', 'oracle')][string]$Edition,
  [Parameter(Mandatory)][string]$Out,
  [switch]$AcceptTerms,
  [string]$Repo
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
      "`nread them, then run again with -AcceptTerms")
  }
  New-Item -ItemType Directory -Force $Out | Out-Null
  Get-ChildItem $Out -File | Where-Object { $_.Name -like 'graaljs*.manifest' -or (-not $Repo -and $_.Name -like '*.jar') } | Remove-Item -Force
  $lines = @('# jllm GraalJS jar set: <sha256>  <path in Maven Central>', "edition=$Edition", "version=$version")
  if ($terms.Count -gt 0) { $lines += ('terms=' + ($terms -join '; ')) }
  $prefix = (Resolve-Path $work).Path.TrimEnd('\') + '\'
  $count = 0
  foreach ($jar in (Get-ChildItem $work -Recurse -Filter *.jar | Sort-Object FullName)) {
    if (-not $Repo) { Copy-Item $jar.FullName $Out }
    $rel = $jar.FullName.Substring($prefix.Length).Replace('\', '/')
    $lines += ((Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower() + '  ' + $rel)
    $count++
  }
  if ($Repo) {
    if (Test-Path $Repo) { Remove-Item -Recurse -Force $Repo }
    New-Item -ItemType Directory -Force $Repo | Out-Null
    Copy-Item (Join-Path $work '*') $Repo -Recurse -Force
    $isolate = if ($Edition -eq 'community') { 'js-isolate-community' } else { 'js-isolate' }
    $gate = Join-Path $Repo "org\kendar\llm\graal\jllm-graal-set\$version"
    New-Item -ItemType Directory -Force $gate | Out-Null
    $tpl = [IO.File]::ReadAllText((Join-Path $here 'jllm-graal-set.pom'))
    [IO.File]::WriteAllText((Join-Path $gate "jllm-graal-set-$version.pom"), $tpl.Replace('@VERSION@', $version).Replace('@ISOLATE@', $isolate))
  }
  # LF line endings: the .sh fetch script reads the same manifest
  [IO.File]::WriteAllText((Join-Path $Out "graaljs-$Edition.manifest"), (($lines -join "`n") + "`n"))
  Write-Host "GraalJS $Edition ${version}: $count jars in $(if ($Repo) { $Repo } else { $Out })"
} finally {
  Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
