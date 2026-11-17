# Builds target\release\jllm-<version>.zip: cli + web + plugins + settings + users + runners.
# Usage: scripts\release.ps1 [-SkipBuild] [-Out DIR]
param([switch]$SkipBuild, [string]$Out)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'target\release' }
Set-Location $root
if (-not $SkipBuild) {
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
  mvn -B -DskipTests clean install
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
Compress-Archive -Path $stage -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  $name.zip" | Set-Content (Join-Path $Out 'SHA256SUMS') -Encoding ascii
Write-Host "built $zip"
Write-Host "publish: gh release create v$version `"$zip`" `"$(Join-Path $Out 'SHA256SUMS')`" --title `"jllm $version`" --notes-file release\template\README.md"
