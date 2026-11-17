# Guided deployment of the llama.cpp server used by jllm-eval (Windows host with an NVIDIA GPU, Docker Desktop). Run from core/jllm-eval.
#
#   .\docker\llm.ps1 up [strong|alt|small]   check the host, load one model, wait until it answers, print the eval commands
#   .\docker\llm.ps1 status [model]          show what the server reports
#   .\docker\llm.ps1 down [model]            stop and remove the container
#
# Optional environment: MODELS_DIR (folder with the .gguf files), CACHE_DIR, LLAMA_PORT, HOST_ADDR (address the eval
# machine uses to reach this host). They override the values in <model>.env.
# The image is pulled, not built. The eval never starts or stops this server: you do, with this script.
param(
    [ValidateSet('up', 'status', 'down')][string]$Command = 'up',
    [string]$Model = ''
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Step($t) { Write-Host "`n== $t" }
function Fail($t) { Write-Host "ERROR: $t" -ForegroundColor Red; exit 1 }
function Ask($q, $default) { $a = Read-Host "$q [$default]"; if ([string]::IsNullOrWhiteSpace($a)) { $default } else { $a } }

$script:EnvFile = ''
$script:Cfg = @{}

function Load-Env {
    if (-not (Test-Path $script:EnvFile)) { Fail "$($script:EnvFile) not found" }
    $script:Cfg = @{}
    foreach ($line in Get-Content $script:EnvFile) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2
        $k = $k.Trim()
        $env_ = [Environment]::GetEnvironmentVariable($k)
        # values already in the environment win over the env file
        $script:Cfg[$k] = if ($env_) { $env_ } else { $v.Trim() }
        [Environment]::SetEnvironmentVariable($k, $script:Cfg[$k], 'Process')
    }
}

function Compose { docker compose --env-file $script:EnvFile @args; if ($LASTEXITCODE -ne 0) { Fail "docker compose $($args -join ' ') failed" } }

function Check-Host {
    Step '1. Host checks'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'docker is not installed (Docker Desktop with the WSL2 backend)' }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'cannot talk to docker: start Docker Desktop' }
    Write-Host 'docker: ok'
    if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) { Fail 'nvidia-smi not found: install the NVIDIA driver' }
    Write-Host ("GPU: " + (nvidia-smi --query-gpu=name,memory.total --format=csv,noheader | Select-Object -First 1))
    docker run --rm --gpus all hello-world *> $null
    if ($LASTEXITCODE -ne 0) { Write-Host 'WARNING: docker could not start a container with --gpus all. Enable GPU support in Docker Desktop (WSL2).' }
}

function Pick-Model {
    Step '2. Model'
    if (-not $Model) {
        Write-Host 'strong = Qwen3.6-27B 4-bit | alt = gpt-oss-20b | small = 8-12B 4-bit (small-model prompt)'
        $script:Model = Ask 'Which model' 'strong'
    }
    if ($script:Model -notin 'strong', 'alt', 'small') { Fail "unknown model '$($script:Model)' (use strong, alt or small)" }
    $script:EnvFile = "$($script:Model).env"
    Load-Env
    Write-Host "using $($script:EnvFile): file=$($script:Cfg.MODEL_FILE) alias=$($script:Cfg.MODEL_ALIAS) context=$($script:Cfg.CTX_TOKENS)"
}

function Check-Files {
    Step '3. Model files'
    $dir = $script:Cfg.MODELS_DIR
    if (-not (Test-Path $dir)) {
        Write-Host "MODELS_DIR '$dir' does not exist."
        $dir = Ask 'Folder with the .gguf files' 'C:\models'
        $script:Cfg.MODELS_DIR = $dir
        [Environment]::SetEnvironmentVariable('MODELS_DIR', $dir, 'Process')
    }
    $cache = $script:Cfg.CACHE_DIR
    if (-not $cache) { $cache = Join-Path $dir 'cache'; [Environment]::SetEnvironmentVariable('CACHE_DIR', $cache, 'Process') }
    New-Item -ItemType Directory -Force $cache | Out-Null
    $file = Join-Path $dir $script:Cfg.MODEL_FILE
    if (-not (Test-Path $file)) {
        Write-Host "Missing: $file"
        Write-Host 'Download it from Hugging Face, for example:'
        Write-Host '  pip install -U huggingface_hub'
        Write-Host "  huggingface-cli download <repo-with-$($script:Cfg.MODEL_FILE)> $($script:Cfg.MODEL_FILE) --local-dir `"$dir`""
        Fail "put the file in $dir and run again"
    }
    Write-Host ("found $file ({0:N1} GB)" -f ((Get-Item $file).Length / 1GB))
}

function Deploy {
    Step '4. Deploy (one model, one request slot)'
    Compose pull
    Compose up -d --force-recreate
}

function Port { if ($script:Cfg.LLAMA_PORT) { $script:Cfg.LLAMA_PORT } else { '8080' } }

function Wait-Ready {
    Step '5. Waiting for the model to load (a 27B model can take minutes)'
    $port = Port
    for ($i = 0; $i -lt 120; $i++) {
        try {
            $r = Invoke-WebRequest "http://localhost:$port/health" -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { Write-Host 'server is ready'; return }
        } catch { }
        Write-Host -NoNewline '.'
        Start-Sleep 5
    }
    docker logs --tail 30 jllm-eval-llama
    Fail 'the server did not become ready in 10 minutes (log above)'
}

function Report {
    Step '6. What the server reports'
    $port = Port
    $id = '?'; $nctx = '?'; $slots = '?'
    try {
        $id = (Invoke-RestMethod "http://localhost:$port/v1/models").data[0].id
        $p = Invoke-RestMethod "http://localhost:$port/props"
        $nctx = $p.default_generation_settings.n_ctx
        $slots = $p.total_slots
    } catch { Write-Host "WARNING: could not read the server properties: $($_.Exception.Message)" }
    Write-Host "model id: $id   n_ctx: $nctx   slots: $slots"
    if ($id -ne $script:Cfg.MODEL_ALIAS) { Write-Host "WARNING: id differs from the expected alias '$($script:Cfg.MODEL_ALIAS)'" }
    if ("$slots" -ne '1' -and "$slots" -ne '?') { Write-Host "WARNING: $slots slots: cache results will be flagged unreliable (need 1)" }
    $addr = $env:HOST_ADDR
    if (-not $addr) {
        $addr = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress
    }
    $small = if ($script:Model -eq 'small') { 'true' } else { 'false' }
    Step '7. Next: on the machine that runs the eval'
    Write-Host "check:  curl http://${addr}:$port/v1/models"
    Write-Host 'set in core/jllm-eval/src/test/resources/eval.properties (or with -D):'
    Write-Host "  eval.remote.gpu.baseUrl=http://${addr}:$port/v1"
    Write-Host "  eval.remote.gpu.model=$id"
    Write-Host "  eval.remote.gpu.contextTokens=$nctx   (not more than n_ctx)"
    Write-Host "  eval.remote.gpu.smallModel=$small"
    Write-Host 'then, with ANTHROPIC_API_KEY set for the judge:'
    Write-Host '  mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=smoke" "-Deval.target=remote.gpu"'
    Write-Host "open port $port in this host's firewall if the check from the eval machine fails."
}

switch ($Command) {
    'up' { Check-Host; Pick-Model; Check-Files; Deploy; Wait-Ready; Report }
    'status' { if (-not $Model) { $Model = 'strong' }; $script:EnvFile = "$Model.env"; Load-Env; Report }
    'down' { if (-not $Model) { $Model = 'strong' }; $script:EnvFile = "$Model.env"; Load-Env; Compose down }
}
