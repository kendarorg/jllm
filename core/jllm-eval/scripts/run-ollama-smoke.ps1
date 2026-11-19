# One-iteration smoke run of jllm-eval against a local Ollama server.
# Target, judge and fast model all use the same model on the same server (self-graded run).
# Settings can be overridden through environment variables of the same name.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-Setting([string]$Name, [string]$Default) {
    $v = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($v)) { return $Default }
    return $v
}

# Picks a JDK 21+: JAVA_HOME if it qualifies, else the oldest 21+ one under the usual install folders.
function Find-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $roots = @("$HOME\.jdks", "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
               "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu")
    foreach ($r in $roots) {
        if (Test-Path $r) { $candidates += Get-ChildItem $r -Directory | ForEach-Object { $_.FullName } }
    }
    $best = $null; $bestVer = [int]::MaxValue
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if (-not (Test-Path (Join-Path $c 'bin\javac.exe')) -or -not (Test-Path $release)) { continue }
        $m = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
        if (-not $m) { continue }
        $ver = [int]$m.Matches[0].Groups[1].Value
        if ($ver -lt 21) { continue }
        if ($c -eq $env:JAVA_HOME) { return $c }
        if ($ver -lt $bestVer) { $best = $c; $bestVer = $ver }
    }
    return $best
}

# ---- settings
$OLLAMA_BASE = Get-Setting 'OLLAMA_BASE' 'http://localhost:11434'
$MODEL = Get-Setting 'MODEL' 'qwen3:30b'
$CTX_TOKENS = Get-Setting 'CTX_TOKENS' '16000'                 # must not exceed the context Ollama actually allocates (num_ctx)
$TIMEOUT_SECONDS = Get-Setting 'TIMEOUT_SECONDS' '300'         # per model request, and warm-up
$CASE_TIMEOUT_SECONDS = Get-Setting 'CASE_TIMEOUT_SECONDS' $TIMEOUT_SECONDS  # cap on each user turn of a case
#$TEST_GROUPS = Get-Setting 'TEST_GROUPS' 'smoke'
$TEST_GROUPS = Get-Setting 'TEST_GROUPS' 'smoke,full,strategy,unit'
#$TESTS = Get-Setting 'TESTS' 'InfrastructureSmokeTest,ExampleToolEvalTest'
$TESTS = ''
$TRIALS = Get-Setting 'TRIALS' '1'
$MAX_TRIALS = Get-Setting 'MAX_TRIALS' '2'                     # cap on trials of any case, including cases that set their own count
# 1 (default): failed cases are reported but never fail the Maven build, so the run always completes and writes its summary
$IGNORE_TEST_FAILURES = Get-Setting 'IGNORE_TEST_FAILURES' '1'
# minimal = Ollama reasoning_effort "none": Qwen3 thinking would eat the token budget and leave empty replies
$THINKING = Get-Setting 'THINKING' 'minimal'
$JAVA_HOME = Find-JavaHome   # build needs JDK 21+

# ---- paths
$ScriptDir = $PSScriptRoot
$ModuleDir = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $ModuleDir '..\..')).Path
$LogDir = Join-Path $ModuleDir 'eval-results'
$LogFile = Join-Path $LogDir ("ollama-smoke-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

# ---- preflight
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Error 'mvn not found in PATH'; exit 1 }
if (-not $JAVA_HOME) {
    [Console]::Error.WriteLine('No JDK 21+ found; set JAVA_HOME to one')
    exit 1
}

try {
    $served = Invoke-RestMethod -Uri "$OLLAMA_BASE/v1/models" -TimeoutSec 10
} catch {
    [Console]::Error.WriteLine("Ollama not reachable at $OLLAMA_BASE (start it with: ollama serve)")
    exit 1
}
if (-not ($served.data | Where-Object { $_.id -eq $MODEL })) {
    $available = ($served.data | ForEach-Object { $_.id }) -join ' '
    [Console]::Error.WriteLine("Model '$MODEL' not served by Ollama. Available: $available")
    exit 1
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Write-Host "Log: $LogFile"
Write-Host "JAVA_HOME: $JAVA_HOME"
$testsShown = if ($TESTS) { $TESTS } else { 'all' }
Write-Host "Model: $MODEL  ctx: $CTX_TOKENS  groups: $TEST_GROUPS  tests: $testsShown  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  case timeout cap: ${CASE_TIMEOUT_SECONDS}s"

# ---- run
$ignoreFailures = if ($IGNORE_TEST_FAILURES -eq '1') { 'true' } else { 'false' }
$mvnArgs = @(
    '-B', '-ntp', '-f', (Join-Path $RepoRoot 'pom.xml'), '-Peval', '-pl', 'core/jllm-eval', '-am', 'test',
    "-Dgroups=$TEST_GROUPS"
)
if ($TESTS) { $mvnArgs += "-Dtest=$TESTS" }
$mvnArgs += @(
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dmaven.test.failure.ignore=$ignoreFailures",
    '-Deval.target=remote.ollama',
    '-Deval.remote.ollama.protocol=openai',
    "-Deval.remote.ollama.baseUrl=$OLLAMA_BASE/v1",
    "-Deval.remote.ollama.model=$MODEL",
    "-Deval.remote.ollama.contextTokens=$CTX_TOKENS",
    '-Deval.remote.ollama.smallModel=false',
    "-Deval.remote.ollama.timeoutSeconds=$TIMEOUT_SECONDS",
    "-Deval.remote.ollama.thinking=$THINKING",
    '-Deval.judge=openai',
    '-Deval.judge.openai.name=ollama',
    "-Deval.judge.openai.baseUrl=$OLLAMA_BASE/v1",
    "-Deval.judge.openai.model=$MODEL",
    "-Deval.judge.openai.contextTokens=$CTX_TOKENS",
    "-Deval.judge.openai.timeoutSeconds=$TIMEOUT_SECONDS",
    "-Deval.judge.openai.thinking=$THINKING",
    '-Deval.fast=target',
    "-Deval.trials=$TRIALS",
    "-Deval.maxTrials=$MAX_TRIALS",
    "-Deval.caseTimeoutSeconds=$CASE_TIMEOUT_SECONDS",
    "-Deval.timeout.warmup=$TIMEOUT_SECONDS"
)

$prevJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JAVA_HOME
$writer = New-Object System.IO.StreamWriter($LogFile, $false, (New-Object System.Text.UTF8Encoding($false)))
$ErrorActionPreference = 'Continue'
try {
    & mvn @mvnArgs 2>&1 | ForEach-Object {
        $line = "$_"
        $writer.WriteLine($line)
        Write-Host $line
    }
    $status = $LASTEXITCODE
} finally {
    $writer.Dispose()
    $ErrorActionPreference = 'Stop'
    $env:JAVA_HOME = $prevJavaHome
}

Write-Host ''
Write-Host "Exit: $status   Log: $LogFile"
Write-Host "Results: $LogDir"
exit $status
