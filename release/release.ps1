# Builds target\release\jllm-<version>.zip: cli + web + plugins + configs (unsafe, webSafe, cliSafe) + runners + an empty lib-ext, and
# copies setup.sh / setup.ps1 (release\setup) next to the zip as separate release files.
# Usage: release\release.ps1 [-Build] [-Out DIR]
# No GraalJS jar is shipped: lib-ext\manifests\ holds the manifests of both editions (resolved with release\graaljs) and
# the user runs setup.sh / setup.ps1, which unzips the distribution, asks the edition and fetches it with fetch-graaljs.
# The build fails if a front-end jar contains GraalJS or if a resolved pom declares a licence other than UPL, MIT,
# Apache, BSD, Unicode/ICU (the Oracle terms are only recorded in the manifest, never accepted here).
# Without -Build it asks whether to build (mvn clean install); the default is no: package the existing jars.
# When building it asks whether to run the tests (default yes; -Tests answers yes). JaCoCo is never run (no agent, no report module).
param([switch]$Build, [switch]$Tests, [string]$Out)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'target\release' }
Set-Location $root
if (-not $Build) { $Build = (Read-Host 'Build with mvn clean install? [y/N]') -match '^(y|yes)$' }
if ($Build -and -not $Tests) { $Tests = -not ((Read-Host 'Run the tests? [Y/n]') -match '^(n|no)$') }
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
  $mvnArgs = @('-B', '-Djacoco.skip=true', '-pl', '!jacoco', 'clean', 'install')
  if (-not $Tests) { $mvnArgs += '-DskipTests' }
  mvn @mvnArgs
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
# safe configurations: the same folders the runners use (runConfigs), without tmp; unsafe comes from the template
foreach ($c in 'webSafe', 'cliSafe') {
  $dst = Join-Path $stage "configs\$c"
  New-Item -ItemType Directory -Force $dst | Out-Null
  Copy-Item "runConfigs\$c\*" $dst -Recurse -Force
  Remove-Item -Recurse -Force (Join-Path $dst 'tmp') -ErrorAction SilentlyContinue
}
# SQL store: the starter and its jars go to lib-ext\sql (fetch-graaljs empties lib-ext\*.jar, not lib-ext\sql); the JDBC
# drivers (MySQL GPL, PostgreSQL BSD) are never shipped: fetch-sql-driver downloads them into lib-ext and checks the manifests
New-Item -ItemType Directory -Force (Join-Path $stage 'lib-ext\sql') | Out-Null
Copy-Item "web\jllm-sql-spring-starter\target\jllm-sql-spring-starter-$version.jar" (Join-Path $stage 'lib-ext\sql')
Copy-Item web\jllm-sql-spring-starter\target\lib-sql\*.jar (Join-Path $stage 'lib-ext\sql')
Copy-Item scripts\fetch-sql-driver.sh, scripts\fetch-sql-driver.ps1, scripts\fetch-sql-driver.cmd $stage
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
Copy-Item web\jllm-sql\src\main\resources\db\drivers\*.manifest (Join-Path $libExt 'manifests')
$resolve = Join-Path $root 'release\graaljs\resolve.ps1'
# manifests only, nothing is shipped: the operator accepts the terms of the oracle edition in setup / fetch-graaljs
foreach ($ed in 'community', 'oracle') {
  $tmpEd = Join-Path ([IO.Path]::GetTempPath()) ("jllm-graal-mf-" + [guid]::NewGuid().ToString('N'))
  try {
    & $resolve -Edition $ed -Out $tmpEd -AcceptTerms
    Copy-Item (Join-Path $tmpEd "graaljs-$ed.manifest") (Join-Path $libExt 'manifests')
  } finally { Remove-Item -Recurse -Force $tmpEd -ErrorAction SilentlyContinue }
}
Copy-Item (Join-Path $root 'release\setup\setup.sh'), (Join-Path $root 'release\setup\setup.ps1') $Out
Compress-Archive -Path $stage -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  $name.zip" | Set-Content (Join-Path $Out 'SHA256SUMS') -Encoding ascii
Write-Host "built $zip"
Write-Host "publish: gh release create v$version `"$zip`" `"$(Join-Path $Out 'SHA256SUMS')`" `"$(Join-Path $Out 'setup.sh')`" `"$(Join-Path $Out 'setup.ps1')`" --title `"jllm $version`" --notes-file release\template\README.md"
