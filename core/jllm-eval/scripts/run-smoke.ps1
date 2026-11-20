# One-iteration smoke run of jllm-eval; target (tested model) and judge each pick their own provider, model and key.
# Providers: anthropic | nvidia | ollama | llamacpp. The fast model is the target.
#
#   .\run-smoke.ps1                 # pick one of the *.env presets in envs\
#   .\run-smoke.ps1 nvidia.env      # use that preset
#   .\run-smoke.ps1 none            # no preset: environment and built-in defaults only
#
# A preset holds NAME=VALUE lines; a variable already set in the environment wins over the preset.
# Presets must not hold keys. Keys come from the environment or, when not set there, from a hidden prompt:
#   TARGET_API_KEY, then ANTHROPIC_API_KEY / NVIDIA_API_KEY for the target provider
#   JUDGE_API_KEY,  then ANTHROPIC_API_KEY / NVIDIA_API_KEY for the judge provider
# The judge prompt accepts an empty answer when both roles use the same provider (= the target key).
# Ollama and llama.cpp always use the key "none": never read, never asked.
#
# Per role (TARGET_* / JUDGE_*):
#   *_PROVIDER   anthropic | nvidia | ollama | llamacpp (JUDGE_PROVIDER defaults to TARGET_PROVIDER)
#   *_MODEL      model id; required, never assumed
#   *_MODEL_NAME name of the model in results, reports, baselines and history; empty = *_MODEL
#                (e.g. TARGET_MODEL=qwen-local, TARGET_MODEL_NAME=Qwen3.6-35B for a server alias)
#   *_BASE       machine address (scheme://host[:port]); required, never assumed; the script adds the API path /v1
#   *_CTX_TOKENS context tokens
#   *_RPM        requests per minute; 0 = unpaced
#   *_THINKING   minimal|low|medium|high; empty = the model's default (ollama defaults to minimal)
# With both roles on anthropic, the judge shares the target's base, context, timeout and rpm (one eval.anthropic.* block).
param([string]$Preset = '')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$Message) { [Console]::Error.WriteLine($Message); exit 1 }

# ---- preset
$EnvDir = Join-Path $PSScriptRoot 'envs'
$presetValues = @{}
if (-not $Preset) {
    $presets = @(Get-ChildItem -Path $EnvDir -Filter '*.env' -File | Sort-Object Name)
    if ($presets.Count -gt 0) {
        if ([Console]::IsInputRedirected) { Fail 'no preset given and no terminal to choose one; pass <file>.env or none' }
        Write-Host 'Presets:'
        Write-Host '  0) none'
        for ($i = 0; $i -lt $presets.Count; $i++) { Write-Host ("  {0}) {1}" -f ($i + 1), $presets[$i].Name) }
        $choice = Read-Host "Choose [0-$($presets.Count)]"
        $n = 0
        if (-not [int]::TryParse($choice, [ref]$n) -or $n -lt 0 -or $n -gt $presets.Count) { Fail "invalid choice '$choice'" }
        $Preset = if ($n -eq 0) { 'none' } else { $presets[$n - 1].FullName }
    } else {
        $Preset = 'none'
    }
}
if ($Preset -ne 'none') {
    if (-not (Test-Path $Preset -PathType Leaf)) { $Preset = Join-Path $EnvDir $Preset }
    if (-not (Test-Path $Preset -PathType Leaf)) { Fail "preset not found: $Preset" }
    foreach ($line in Get-Content -LiteralPath $Preset) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Fail "${Preset}: bad line '$line'" }
        $k = $Matches[1]; $v = $Matches[2]
        if ($k -like '*API_KEY*') { Fail "${Preset}: keys must not be in a preset ($k)" }
        if ($v -match '^"(.*)"$' -or $v -match "^'(.*)'$") { $v = $Matches[1] }
        $presetValues[$k] = $v
    }
}

# Environment first, then the preset, then the default. -AllowEmpty keeps an empty preset value (e.g. TARGET_THINKING=).
function Get-Setting([string]$Name, [string]$Default, [switch]$AllowEmpty) {
    $v = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrEmpty($v)) { return $v }
    if ($presetValues.ContainsKey($Name)) {
        $p = $presetValues[$Name]
        if ($p -ne '' -or $AllowEmpty) { return $p }
    }
    return $Default
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

# ---- provider defaults
$providerDefaults = @{
    anthropic = @{ Ctx = '200000'; Rpm = '300'; Thinking = ''; Par = '20'; KeyEnv = 'ANTHROPIC_API_KEY' }
    nvidia    = @{ Ctx = '16000'; Rpm = '10'; Thinking = ''; Par = '2'; KeyEnv = 'NVIDIA_API_KEY' }
    ollama    = @{ Ctx = '16000'; Rpm = '0'; Thinking = 'minimal'; Par = '1'; KeyEnv = '' }
    llamacpp  = @{ Ctx = '16000'; Rpm = '0'; Thinking = ''; Par = '1'; KeyEnv = '' }
}
function Test-Provider([string]$Name, [string]$Value) {
    if (-not $providerDefaults.ContainsKey($Value)) { Fail "${Name}: must be anthropic, nvidia, ollama or llamacpp (got '$Value')" }
}

# ---- settings
$TARGET_PROVIDER = Get-Setting 'TARGET_PROVIDER' 'anthropic'
Test-Provider 'TARGET_PROVIDER' $TARGET_PROVIDER
$JUDGE_PROVIDER = Get-Setting 'JUDGE_PROVIDER' $TARGET_PROVIDER
Test-Provider 'JUDGE_PROVIDER' $JUDGE_PROVIDER
$td = $providerDefaults[$TARGET_PROVIDER]
$jd = $providerDefaults[$JUDGE_PROVIDER]

# The model id and the address are never assumed: a missing one stops the run.
function Get-Required([string]$Name) {
    $v = Get-Setting $Name ''
    if ([string]::IsNullOrEmpty($v)) { Fail "$Name is not set: give it in the environment or in the preset" }
    return $v
}
$TARGET_MODEL = Get-Required 'TARGET_MODEL'
$TARGET_BASE = (Get-Required 'TARGET_BASE').TrimEnd('/')
$TARGET_CTX_TOKENS = Get-Setting 'TARGET_CTX_TOKENS' $td.Ctx
$TARGET_RPM = Get-Setting 'TARGET_RPM' $td.Rpm
$TARGET_THINKING = Get-Setting 'TARGET_THINKING' $td.Thinking -AllowEmpty
$TARGET_MODEL_NAME = Get-Setting 'TARGET_MODEL_NAME' ''

$sameProvider = $JUDGE_PROVIDER -eq $TARGET_PROVIDER
$JUDGE_MODEL = Get-Required 'JUDGE_MODEL'
$JUDGE_BASE = (Get-Required 'JUDGE_BASE').TrimEnd('/')
$JUDGE_CTX_TOKENS = Get-Setting 'JUDGE_CTX_TOKENS' $jd.Ctx
$JUDGE_RPM = Get-Setting 'JUDGE_RPM' $jd.Rpm
$JUDGE_THINKING = Get-Setting 'JUDGE_THINKING' $jd.Thinking -AllowEmpty
$JUDGE_MODEL_NAME = Get-Setting 'JUDGE_MODEL_NAME' ''

$COMPACTION_CTX_TOKENS = Get-Setting 'COMPACTION_CTX_TOKENS' '65536'   # context of the compaction group only
$TIMEOUT_SECONDS = Get-Setting 'TIMEOUT_SECONDS' '300'                 # per model request, and warm-up
$CASE_TIMEOUT_SECONDS = Get-Setting 'CASE_TIMEOUT_SECONDS' $TIMEOUT_SECONDS  # cap on each user turn of a case
$TEST_GROUPS = (Get-Setting 'TEST_GROUPS' 'all') -replace '\s', ''   # 'all' or a comma separated list of: smoke,full,strategy,compaction,unit
if (($TEST_GROUPS -split ',') -contains 'all') { $TEST_GROUPS = 'smoke,full,strategy,compaction,unit' }
$TESTS = Get-Setting 'TESTS' ''                    # optional comma separated test classes
$TRIALS = Get-Setting 'TRIALS' '1'
$MAX_TRIALS = Get-Setting 'MAX_TRIALS' '2'         # cap on trials of any case, including cases that set their own count
$UPDATE_BASELINE = Get-Setting 'UPDATE_BASELINE' '0'   # 1: store this run as the model baseline
$IGNORE_TEST_FAILURES = Get-Setting 'IGNORE_TEST_FAILURES' '1'   # 1: failed cases never fail the Maven build
$PARALLELISM = [int](Get-Setting 'PARALLELISM' $td.Par)   # test classes run at once; 1 = strictly sequential
# added to the system prompt of every case; 'none' turns it off. Some hosted models write a tool call as text.
$defaultExtra = if ($TARGET_PROVIDER -eq 'nvidia') { 'Call tools only through the structured tool-calling interface with JSON arguments. Never write a tool call as plain text or inside your reasoning.' } else { 'none' }
$SYSTEM_PROMPT_EXTRA = Get-Setting 'SYSTEM_PROMPT_EXTRA' $defaultExtra
$JAVA_HOME = Find-JavaHome   # build needs JDK 21+

# ---- paths
$ModuleDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $ModuleDir '..\..')).Path
$LogDir = Join-Path $ModuleDir 'eval-results'
$LogFile = Join-Path $LogDir ("smoke-{0}-{1}.log" -f $TARGET_PROVIDER, (Get-Date -Format 'yyyyMMdd-HHmmss'))

# ---- preflight
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Fail 'mvn not found in PATH' }
if (-not $JAVA_HOME) { Fail 'No JDK 21+ found; set JAVA_HOME to one' }

# ---- keys
function Read-Key([string]$Role, [string]$Provider, [string]$Hint, [bool]$AllowEmpty) {
    if ([Console]::IsInputRedirected) { Fail "$($Role.ToUpper())_API_KEY is not set and no terminal is available to ask for it" }
    $secure = Read-Host -AsSecureString "$Role API key for $Provider$Hint"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $k = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ([string]::IsNullOrEmpty($k) -and -not $AllowEmpty) { Fail "empty $Role API key" }
    return $k
}

# The role variable, then the provider variable.
function Get-EnvKey([string]$Role, [string]$Provider) {
    $v = [Environment]::GetEnvironmentVariable("$($Role.ToUpper())_API_KEY")
    if (-not [string]::IsNullOrEmpty($v)) { return $v }
    $keyEnv = $providerDefaults[$Provider].KeyEnv
    if ($keyEnv) { return [Environment]::GetEnvironmentVariable($keyEnv) }
    return $null
}

# ollama and llamacpp (no KeyEnv) always use the key "none"
$targetApiKey = 'none'
if ($td.KeyEnv) {
    $targetApiKey = Get-EnvKey 'target' $TARGET_PROVIDER
    if ([string]::IsNullOrEmpty($targetApiKey)) { $targetApiKey = Read-Key 'target' $TARGET_PROVIDER '' $false }
}

$judgeApiKey = 'none'
if ($jd.KeyEnv) {
    $judgeApiKey = Get-EnvKey 'judge' $JUDGE_PROVIDER
    if ([string]::IsNullOrEmpty($judgeApiKey)) {
        if ($sameProvider) {
            $judgeApiKey = Read-Key 'judge' $JUDGE_PROVIDER ' (empty = same as target)' $true
            if ([string]::IsNullOrEmpty($judgeApiKey)) { $judgeApiKey = $targetApiKey }
        } else {
            $judgeApiKey = Read-Key 'judge' $JUDGE_PROVIDER '' $false
        }
    }
}

$judgeKeyLabel = if (-not $jd.KeyEnv) { 'none' }
                 elseif ($sameProvider -and $judgeApiKey -eq $targetApiKey) { 'shared' }
                 else { 'own' }

# ---- preflight against the endpoints (keys are passed by header, never printed)
function Test-Model([string]$Role, [string]$Provider, [string]$Base, [string]$Key, [string]$Model) {
    try {
        if ($Provider -eq 'anthropic') {
            $served = Invoke-RestMethod -Uri "$Base/v1/models?limit=1000" -TimeoutSec 20 `
                -Headers @{ 'x-api-key' = $Key; 'anthropic-version' = '2023-06-01' }
        } elseif ($Key) {
            $served = Invoke-RestMethod -Uri "$Base/v1/models" -TimeoutSec 20 -Headers @{ Authorization = "Bearer $Key" }
        } else {
            $served = Invoke-RestMethod -Uri "$Base/v1/models" -TimeoutSec 20
        }
    } catch {
        $code = $null
        if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        if ($code -eq 401 -or $code -eq 403) { Fail "${Role}: $Provider at $Base rejected the key (HTTP $code)" }
        if ($code) { Fail "${Role}: $Provider at $Base answered HTTP $code" }
        Fail "${Role}: $Provider not reachable at $Base ($($_.Exception.Message))"
    }
    if (-not ($served.data | Where-Object { $_.id -eq $Model })) {
        Fail "${Role}: model '$Model' not served by $Provider at $Base. Override with `$env:$($Role.ToUpper())_MODEL."
    }
}
Test-Model 'target' $TARGET_PROVIDER $TARGET_BASE $targetApiKey $TARGET_MODEL
Test-Model 'judge' $JUDGE_PROVIDER $JUDGE_BASE $judgeApiKey $JUDGE_MODEL

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$testsShown = if ($TESTS) { $TESTS } else { 'all' }
Write-Host "Log: $LogFile"
Write-Host "Preset: $(if ($Preset -eq 'none') { 'none' } else { Split-Path $Preset -Leaf })"
Write-Host "JAVA_HOME: $JAVA_HOME"
Write-Host "Target: $TARGET_PROVIDER $TARGET_MODEL$(if ($TARGET_MODEL_NAME) { " as $TARGET_MODEL_NAME" })  thinking: $(if ($TARGET_THINKING) { $TARGET_THINKING } else { 'default' })  ctx: $TARGET_CTX_TOKENS  rpm: $TARGET_RPM"
Write-Host "Judge:  $JUDGE_PROVIDER $JUDGE_MODEL$(if ($JUDGE_MODEL_NAME) { " as $JUDGE_MODEL_NAME" })  thinking: $(if ($JUDGE_THINKING) { $JUDGE_THINKING } else { 'default' })  ctx: $JUDGE_CTX_TOKENS  rpm: $JUDGE_RPM  key: $judgeKeyLabel"
Write-Host "Compaction ctx: $COMPACTION_CTX_TOKENS  groups: $TEST_GROUPS  tests: $testsShown  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  case timeout cap: ${CASE_TIMEOUT_SECONDS}s  parallelism: $PARALLELISM"

# ---- eval settings
$evalArgs = @()
$secretEnv = @{}

if ($TARGET_PROVIDER -eq 'anthropic') {
    $evalArgs += @(
        '-Deval.target=anthropic',
        "-Deval.anthropic.baseUrl=$TARGET_BASE",
        "-Deval.anthropic.model=$TARGET_MODEL",
        "-Deval.anthropic.contextTokens=$TARGET_CTX_TOKENS",
        "-Deval.anthropic.timeoutSeconds=$TIMEOUT_SECONDS",
        "-Deval.anthropic.maxRpm=$TARGET_RPM")
    if ($TARGET_THINKING) { $evalArgs += "-Deval.anthropic.thinking=$TARGET_THINKING" }
    $secretEnv['EVAL_ANTHROPIC_APIKEY'] = $targetApiKey
} else {
    $r = "eval.remote.$TARGET_PROVIDER"
    $evalArgs += @(
        "-Deval.target=remote.$TARGET_PROVIDER",
        "-D$r.protocol=openai",
        "-D$r.baseUrl=$TARGET_BASE/v1",
        "-D$r.model=$TARGET_MODEL",
        "-D$r.contextTokens=$TARGET_CTX_TOKENS",
        "-D$r.smallModel=false",
        "-D$r.timeoutSeconds=$TIMEOUT_SECONDS",
        "-D$r.maxRpm=$TARGET_RPM")
    if ($TARGET_THINKING) { $evalArgs += "-D$r.thinking=$TARGET_THINKING" }
    if ($targetApiKey) { $secretEnv["EVAL_REMOTE_$($TARGET_PROVIDER.ToUpper())_APIKEY"] = $targetApiKey }
}

if ($JUDGE_PROVIDER -eq 'anthropic') {
    $evalArgs += @('-Deval.judge=anthropic', "-Deval.judge.anthropic.model=$JUDGE_MODEL")
    if ($TARGET_PROVIDER -ne 'anthropic') {
        $evalArgs += @(
            "-Deval.anthropic.baseUrl=$JUDGE_BASE",
            "-Deval.anthropic.contextTokens=$JUDGE_CTX_TOKENS",
            "-Deval.anthropic.timeoutSeconds=$TIMEOUT_SECONDS",
            "-Deval.anthropic.maxRpm=$JUDGE_RPM")
    }
    if ($JUDGE_THINKING) { $evalArgs += "-Deval.judge.anthropic.thinking=$JUDGE_THINKING" }
    $secretEnv['EVAL_JUDGE_ANTHROPIC_APIKEY'] = $judgeApiKey
} else {
    $evalArgs += @(
        '-Deval.judge=openai',
        "-Deval.judge.openai.name=$JUDGE_PROVIDER",
        "-Deval.judge.openai.baseUrl=$JUDGE_BASE/v1",
        "-Deval.judge.openai.model=$JUDGE_MODEL",
        "-Deval.judge.openai.contextTokens=$JUDGE_CTX_TOKENS",
        "-Deval.judge.openai.timeoutSeconds=$TIMEOUT_SECONDS",
        "-Deval.judge.openai.maxRpm=$JUDGE_RPM")
    if ($JUDGE_THINKING) { $evalArgs += "-Deval.judge.openai.thinking=$JUDGE_THINKING" }
    if ($judgeApiKey) { $secretEnv['EVAL_JUDGE_OPENAI_APIKEY'] = $judgeApiKey }
}

if ($TARGET_MODEL_NAME) { $evalArgs += "-Deval.target.modelName=$TARGET_MODEL_NAME" }
if ($JUDGE_MODEL_NAME) { $evalArgs += "-Deval.judge.modelName=$JUDGE_MODEL_NAME" }
if ($SYSTEM_PROMPT_EXTRA -ne 'none') { $secretEnv['EVAL_SYSTEMPROMPT_EXTRA'] = $SYSTEM_PROMPT_EXTRA }
if ($TESTS) { $evalArgs += "-Dtest=$TESTS" }

$ignoreFailures = if ($IGNORE_TEST_FAILURES -eq '1') { 'true' } else { 'false' }
$mvnArgs = @(
    '-B', '-ntp', '-f', (Join-Path $RepoRoot 'pom.xml'), '-Peval', '-pl', 'core/jllm-eval', '-am', 'test',
    "-Dgroups=$TEST_GROUPS",
    "-Drun.parallelism=$PARALLELISM",
    "-Drun.parallel.enabled=$(if ($PARALLELISM -gt 1) { 'true' } else { 'false' })",
    "-Drun.parallel.classes=$(if ($PARALLELISM -gt 1) { 'concurrent' } else { 'same_thread' })",
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dmaven.test.failure.ignore=$ignoreFailures"
) + $evalArgs + @(
    '-Deval.fast=target',
    "-Deval.trials=$TRIALS",
    "-Deval.maxTrials=$MAX_TRIALS",
    "-Deval.updateBaseline=$(if ($UPDATE_BASELINE -eq '1') { 'true' } else { 'false' })",
    "-Deval.caseTimeoutSeconds=$CASE_TIMEOUT_SECONDS",
    "-Deval.compaction.contextTokens=$COMPACTION_CTX_TOKENS",
    "-Deval.timeout.warmup=$TIMEOUT_SECONDS"
)

# ---- run
$prevJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JAVA_HOME
foreach ($k in $secretEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $secretEnv[$k]) }

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
    foreach ($k in $secretEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $null) }
    $targetApiKey = $null
    $judgeApiKey = $null
    $secretEnv = $null
}

Write-Host ''
Write-Host "Exit: $status   Log: $LogFile"
Write-Host "Results: $LogDir"
exit $status
