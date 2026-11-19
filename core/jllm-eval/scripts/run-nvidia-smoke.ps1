# One-iteration smoke run of jllm-eval against NVIDIA NIM hosted models (free tier).
# Target, judge and fast model all use the same model on the same endpoint (self-graded run).
# Asks for the NVIDIA API key (hidden input) unless NVIDIA_API_KEY is already set.
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
$NVIDIA_BASE = Get-Setting 'NVIDIA_BASE' 'https://integrate.api.nvidia.com/v1'
$MODEL = Get-Setting 'MODEL' 'meta/muse-glimmer-30b'
$CTX_TOKENS = Get-Setting 'CTX_TOKENS' '16000'
$TIMEOUT_SECONDS = Get-Setting 'TIMEOUT_SECONDS' '300'
$CASE_TIMEOUT_SECONDS = Get-Setting 'CASE_TIMEOUT_SECONDS' $TIMEOUT_SECONDS
$TEST_GROUPS = Get-Setting 'TEST_GROUPS' 'smoke,full,strategy,unit'
$TRIALS = Get-Setting 'TRIALS' '1'
$MAX_TRIALS = Get-Setting 'MAX_TRIALS' '2'
$IGNORE_TEST_FAILURES = Get-Setting 'IGNORE_TEST_FAILURES' '1'
$RPM_LIMIT = Get-Setting 'RPM_LIMIT' '40'
$JAVA_HOME = Find-JavaHome   # build needs JDK 21+

# ---- paths
$ScriptDir = $PSScriptRoot
$ModuleDir = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $ModuleDir '..\..')).Path
$LogDir = Join-Path $ModuleDir 'eval-results'
$LogFile = Join-Path $LogDir ("nvidia-smoke-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

# ---- preflight
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Error 'mvn not found in PATH'; exit 1 }
if (-not $JAVA_HOME) {
    [Console]::Error.WriteLine('No JDK 21+ found; set JAVA_HOME to one')
    exit 1
}

# ---- token
$apiKey = $env:NVIDIA_API_KEY
if ([string]::IsNullOrEmpty($apiKey)) {
    if ([Console]::IsInputRedirected) {
        [Console]::Error.WriteLine('NVIDIA_API_KEY is not set and no terminal is available to ask for it')
        exit 1
    }
    $secure = Read-Host -AsSecureString 'NVIDIA API key (nvapi-...)'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
if ([string]::IsNullOrEmpty($apiKey)) { [Console]::Error.WriteLine('empty API key'); exit 1 }

# ---- preflight against the endpoint (key is passed by header, never printed)
try {
    $served = Invoke-RestMethod -Uri "$NVIDIA_BASE/models" -TimeoutSec 20 -Headers @{ Authorization = "Bearer $apiKey" }
} catch {
    [Console]::Error.WriteLine("NVIDIA endpoint not reachable or key rejected at $NVIDIA_BASE")
    exit 1
}
if (-not ($served.data | Where-Object { $_.id -eq $MODEL })) {
    [Console]::Error.WriteLine("Model '$MODEL' not served by NVIDIA NIM. Override with `$env:MODEL='<org/model>'.")
    exit 1
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Write-Host "Log: $LogFile"
Write-Host "JAVA_HOME: $JAVA_HOME"
Write-Host "Model: $MODEL  ctx: $CTX_TOKENS  groups: $TEST_GROUPS  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s"

# ---- run
$prevJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JAVA_HOME
$env:EVAL_REMOTE_NVIDIA_APIKEY = $apiKey
$env:EVAL_JUDGE_OPENAI_APIKEY = $apiKey

$ignoreFailures = if ($IGNORE_TEST_FAILURES -eq '1') { 'true' } else { 'false' }
$mvnArgs = @(
    '-B', '-ntp', '-f', (Join-Path $RepoRoot 'pom.xml'), '-Peval', '-pl', 'core/jllm-eval', '-am', 'test',
    "-Dgroups=$TEST_GROUPS",
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dmaven.test.failure.ignore=$ignoreFailures",
    '-Deval.target=remote.nvidia',
    '-Deval.remote.nvidia.protocol=openai',
    "-Deval.remote.nvidia.baseUrl=$NVIDIA_BASE",
    "-Deval.remote.nvidia.model=$MODEL",
    "-Deval.remote.nvidia.contextTokens=$CTX_TOKENS",
    '-Deval.remote.nvidia.smallModel=false',
    "-Deval.remote.nvidia.timeoutSeconds=$TIMEOUT_SECONDS",
    "-Deval.remote.nvidia.maxRpm=$RPM_LIMIT",
    '-Deval.judge=openai',
    '-Deval.judge.openai.name=nvidia',
    "-Deval.judge.openai.baseUrl=$NVIDIA_BASE",
    "-Deval.judge.openai.model=$MODEL",
    "-Deval.judge.openai.contextTokens=$CTX_TOKENS",
    "-Deval.judge.openai.timeoutSeconds=$TIMEOUT_SECONDS",
    "-Deval.judge.openai.maxRpm=$RPM_LIMIT",
    '-Deval.fast=target',
    "-Deval.trials=$TRIALS",
    "-Deval.maxTrials=$MAX_TRIALS",
    "-Deval.caseTimeoutSeconds=$CASE_TIMEOUT_SECONDS",
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
    Remove-Item Env:EVAL_REMOTE_NVIDIA_APIKEY -ErrorAction SilentlyContinue
    Remove-Item Env:EVAL_JUDGE_OPENAI_APIKEY -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Exit: $status   Log: $LogFile"
Write-Host "Results: $LogDir"
exit $status
