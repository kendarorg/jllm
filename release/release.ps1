# Builds target\release\jllm-<version>.zip: cli + web + plugins + settings + users + runners + lib-ext (GraalJS).
# Usage: release\release.ps1 [-Build] [-Out DIR] [-Graal community|oracle] [-AcceptOracleTerms]
# GraalJS is not inside the front-end jars: it is resolved with release\graaljs and shipped in lib-ext\ (default edition
# community). The manifests of both editions go to lib-ext\manifests\ for fetch-graaljs; the build fails if a front-end
# jar contains GraalJS or if a resolved pom declares a licence other than UPL, MIT, Apache, BSD, Unicode/ICU.
# Without -Build it asks whether to build (mvn clean install, with tests); the default is no: package the existing jars.
param([switch]$Build, [string]$Out, [ValidateSet('community', 'oracle')][string]$Graal = 'community', [switch]$AcceptOracleTerms)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'target\release' }
Set-Location $root
if (-not $Build) { $Build = (Read-Host 'Build with mvn clean install (tests included)? [y/N]') -match '^(y|yes)$' }
if ($Build) {
  # true for a JDK 21+ directory (cmd runs it: java prints its version on stderr, which PowerShell 5.1 treats as an error)
  function Test-Jdk([string]$dir) {
    $j = Join-Path $dir 'bin\java.exe'
    if (-not (Test-Path $j)) { return $false }
    return ((cmd /c "`"$j`" -version 2>&1" | Out-String) -match 'version "(2[1-9]|[3-9]\d)')
  }
  # JAVA_HOME, else jenv, else the JDKs of IntelliJ (~\.jdks, the lowest 21+), else java on the PATH
  if (-not ($env:JAVA_HOME -and (Test-Jdk $env:JAVA_HOME))) {
    $env:JAVA_HOME = $null
    if (Get-Command jenv -ErrorAction SilentlyContinue) {
      $d = (cmd /c "jenv getjava 2>nul" | Out-String).Trim()
      if ($d -and (Test-Jdk $d)) { $env:JAVA_HOME = $d }
    }
    if (-not $env:JAVA_HOME) {
      $found = @(Get-ChildItem (Join-Path $HOME '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Jdk $_.FullName } | Sort-Object Name)
      if ($found.Count -gt 0) { $env:JAVA_HOME = $found[0].FullName }
    }
    if (-not $env:JAVA_HOME) {
      $d = ((cmd /c "java -XshowSettings:properties -version 2>&1" | Out-String) -split "`n" |
        Where-Object { $_ -match '^\s*java\.home = (.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1)
      if ($d -and (Test-Jdk $d)) { $env:JAVA_HOME = $d }
    }
  }
  if (-not $env:JAVA_HOME) { throw 'JDK 21+ not found: set JAVA_HOME to a JDK 21' }
  Write-Host "using JDK $env:JAVA_HOME"
  if (Test-Path target\plugins) { Remove-Item -Recurse -Force target\plugins }
  mvn -B clean install
  if ($LASTEXITCODE -ne 0) { throw 'maven build failed' }
}
$version = ([regex]::Match((Get-Content pom.xml -Raw), '<revision>(.*?)</revision>')).Groups[1].Value
$name = "jllm-$version"; $stage = Join-Path $Out $name; $zip = Join-Path $Out "$name.zip"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
if (Test-Path $zip) { Remove-Item -Force $zip }
New-Item -ItemType Directory -Force (Join-Path $stage 'plugins') | Out-Null
Copy-Item "jllm-cli\target\jllm-cli-$version.jar" (Join-Path $stage 'jllm-cli.jar')
Copy-Item "jllm-web\target\jllm-web-$version.jar" (Join-Path $stage 'jllm-web.jar')
$plugins = @(Get-ChildItem target\plugins\*.jar)
if ($plugins.Count -lt 5) { throw "expected 5 plugin jars in target\plugins, found $($plugins.Count)" }
Copy-Item $plugins.FullName (Join-Path $stage 'plugins')
Copy-Item release\template\* $stage -Recurse -Force
Copy-Item LICENSE, NOTICE $stage
# neither front end may carry GraalJS (it is provided through lib-ext)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($j in 'jllm-cli.jar', 'jllm-web.jar') {
  $zf = [IO.Compression.ZipFile]::OpenRead((Join-Path $stage $j))
  try { $bad = @($zf.Entries | Where-Object { $_.FullName -match '^org/graalvm/|^com/oracle/truffle/|(^|/)(polyglot|truffle-[a-z]+|js-language|js-isolate[a-z0-9-]*|regex|graaljs)-[0-9][^/]*\.jar$' }) } finally { $zf.Dispose() }
  if ($bad.Count -gt 0) { throw "$j contains GraalJS classes or jars: it must be provided through lib-ext, not bundled" }
}
$libExt = Join-Path $stage 'lib-ext'
New-Item -ItemType Directory -Force (Join-Path $libExt 'manifests') | Out-Null
$resolve = Join-Path $root 'release\graaljs\resolve.ps1'
& $resolve -Edition $Graal -Out $libExt -AcceptTerms:$AcceptOracleTerms
$other = if ($Graal -eq 'community') { 'oracle' } else { 'community' }
# the other edition: manifest only (nothing of it is shipped; the operator accepts its terms when running fetch-graaljs)
$tmpOther = Join-Path ([IO.Path]::GetTempPath()) ("jllm-graal-other-" + [guid]::NewGuid().ToString('N'))
try {
  & $resolve -Edition $other -Out $tmpOther -AcceptTerms
  Copy-Item (Join-Path $tmpOther "graaljs-$other.manifest") (Join-Path $libExt 'manifests')
} finally { Remove-Item -Recurse -Force $tmpOther -ErrorAction SilentlyContinue }
Copy-Item (Join-Path $libExt "graaljs-$Graal.manifest") (Join-Path $libExt 'manifests')
Compress-Archive -Path $stage -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  $name.zip" | Set-Content (Join-Path $Out 'SHA256SUMS') -Encoding ascii
Write-Host "built $zip"
Write-Host "publish: gh release create v$version `"$zip`" `"$(Join-Path $Out 'SHA256SUMS')`" --title `"jllm $version`" --notes-file release\template\README.md"
