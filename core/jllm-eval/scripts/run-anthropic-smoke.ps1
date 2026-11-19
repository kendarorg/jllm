# One-iteration smoke run of jllm-eval against the Anthropic API, Claude Haiku 5.5 as target and fast model.
# The judge is Sonnet 5.5 by default (a different model, so the run is not self-graded); JUDGE_MODEL overrides it.
# Asks for the Anthropic API key (hidden input) unless ANTHROPIC_API_KEY is already set.
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
$ANTHROPIC_BASE = Get-Setting 'ANTHROPIC_BASE' 'https://api.anthropic.com'
$MODEL = Get-Setting 'MODEL' 'claude-haiku-5-5'
$JUDGE_MODEL = Get-Setting 'JUDGE_MODEL' 'claude-sonnet-5-5'
$CTX_TOKENS = Get-Setting 'CTX_TOKENS' '200000'
$COMPACTION_CTX_TOKENS = Get-Setting 'COMPACTION_CTX_TOKENS' '65536'   # context of the compaction group only
$TIMEOUT_SECONDS = Get-Setting 'TIMEOUT_SECONDS' '300'
$CASE_TIMEOUT_SECONDS = Get-Setting 'CASE_TIMEOUT_SECONDS' $TIMEOUT_SECONDS
$TEST_GROUPS = (Get-Setting 'TEST_GROUPS' 'all') -replace '\s', ''   # 'all' or a comma separated list of: smoke,full,strategy,compaction,unit
if (($TEST_GROUPS -split ',') -contains 'all') { $TEST_GROUPS = 'smoke,full,strategy,compaction,unit' }
$TRIALS = Get-Setting 'TRIALS' '1'
$MAX_TRIALS = Get-Setting 'MAX_TRIALS' '2'
$IGNORE_TEST_FAILURES = Get-Setting 'IGNORE_TEST_FAILURES' '1'
$RPM_LIMIT = Get-Setting 'RPM_LIMIT' '20'   # requests per minute to the API (target, judge and fast share it); 0 = unpaced
$PARALLELISM = [int](Get-Setting 'PARALLELISM' '4')   # test classes run at once; 1 = strictly sequential
$JAVA_HOME = Find-JavaHome   # build needs JDK 21+

# ---- paths
$ScriptDir = $PSScriptRoot
$ModuleDir = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $ModuleDir '..\..')).Path
$LogDir = Join-Path $ModuleDir 'eval-results'
$LogFile = Join-Path $LogDir ("anthropic-smoke-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

# ---- preflight
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Error 'mvn not found in PATH'; exit 1 }
if (-not $JAVA_HOME) {
    [Console]::Error.WriteLine('No JDK 21+ found; set JAVA_HOME to one')
    exit 1
}

# ---- key
$apiKey = $env:ANTHROPIC_API_KEY
if ([string]::IsNullOrEmpty($apiKey)) {
    if ([Console]::IsInputRedirected) {
        [Console]::Error.WriteLine('ANTHROPIC_API_KEY is not set and no terminal is available to ask for it')
        exit 1
    }
    $secure = Read-Host -AsSecureString 'Anthropic API key (sk-ant-...)'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
if ([string]::IsNullOrEmpty($apiKey)) { [Console]::Error.WriteLine('empty API key'); exit 1 }

# ---- preflight against the API (key is passed by header, never printed)
try {
    $served = Invoke-RestMethod -Uri "$ANTHROPIC_BASE/v1/models?limit=1000" -TimeoutSec 20 `
        -Headers @{ 'x-api-key' = $apiKey; 'anthropic-version' = '2023-06-01' }
} catch {
    [Console]::Error.WriteLine("Anthropic API not reachable or key rejected at $ANTHROPIC_BASE")
    exit 1
}
foreach ($m in @($MODEL, $JUDGE_MODEL)) {
    if (-not ($served.data | Where-Object { $_.id -eq $m })) {
        [Console]::Error.WriteLine("Model '$m' not served by the Anthropic API. Override with `$env:MODEL / `$env:JUDGE_MODEL.")
        exit 1
    }
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Write-Host "Log: $LogFile"
Write-Host "JAVA_HOME: $JAVA_HOME"
Write-Host "Model: $MODEL  judge: $JUDGE_MODEL  ctx: $CTX_TOKENS  compaction ctx: $COMPACTION_CTX_TOKENS  groups: $TEST_GROUPS  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  rpm: $RPM_LIMIT  parallelism: $PARALLELISM"

# ---- run
$prevJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JAVA_HOME
$env:EVAL_ANTHROPIC_APIKEY = $apiKey

$ignoreFailures = if ($IGNORE_TEST_FAILURES -eq '1') { 'true' } else { 'false' }
$mvnArgs = @(
    '-B', '-ntp', '-f', (Join-Path $RepoRoot 'pom.xml'), '-Peval', '-pl', 'core/jllm-eval', '-am', 'test',
    "-Dgroups=$TEST_GROUPS",
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dmaven.test.failure.ignore=$ignoreFailures",
    '-Deval.target=anthropic',
    "-Deval.anthropic.baseUrl=$ANTHROPIC_BASE",
    "-Deval.anthropic.model=$MODEL",
    "-Deval.anthropic.contextTokens=$CTX_TOKENS",
    "-Deval.anthropic.timeoutSeconds=$TIMEOUT_SECONDS",
    "-Deval.anthropic.maxRpm=$RPM_LIMIT",
    "-Drun.parallelism=$PARALLELISM",
    "-Drun.parallel.enabled=$(if ($PARALLELISM -gt 1) { 'true' } else { 'false' })",
    "-Drun.parallel.classes=$(if ($PARALLELISM -gt 1) { 'concurrent' } else { 'same_thread' })",
    '-Deval.judge=anthropic',
    "-Deval.judge.anthropic.model=$JUDGE_MODEL",
    '-Deval.fast=target',
    "-Deval.trials=$TRIALS",
    "-Deval.maxTrials=$MAX_TRIALS",
    "-Deval.caseTimeoutSeconds=$CASE_TIMEOUT_SECONDS",
    "-Deval.compaction.contextTokens=$COMPACTION_CTX_TOKENS",
    "-Deval.timeout.warmup=$TIMEOUT_SECONDS"
)

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
    $apiKey = $null
    Remove-Item Env:EVAL_ANTHROPIC_APIKEY -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Exit: $status   Log: $LogFile"
Write-Host "Results: $LogDir"
exit $status
