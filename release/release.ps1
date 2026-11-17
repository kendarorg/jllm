# Builds target\release\jllm-<version>.zip: cli + web + plugins + settings + users + runners.
# Usage: scripts\release.ps1 [-SkipBuild] [-Out DIR]
param([switch]$SkipBuild, [string]$Out)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'target\release' }
Set-Location $root
if (-not $SkipBuild) {
  if (-not $env:JAVA_HOME) { throw 'set JAVA_HOME to a JDK 21' }
  $v = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-String)
  if ($v -notmatch 'version "(2[1-9]|[3-9]\d)') { throw 'JDK 21+ required' }
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
Write-Host "publish: gh release create v$version `"$zip`" `"$(Join-Path $Out 'SHA256SUMS')`" --title `"jllm $version`" --notes-file release\template\README.txt"
