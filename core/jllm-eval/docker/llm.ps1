# Guided deployment of the llama.cpp server used by jllm-eval. Run from core/jllm-eval.
#
#   .\docker\llm.ps1 up [strong|alt|small] [-DockerHost H]   check docker and the GPU, load one model, wait until it answers
#   .\docker\llm.ps1 status [model] [-DockerHost H]          show what the server reports
#   .\docker\llm.ps1 down [model] [-DockerHost H]            stop and remove the container
#
# The docker host is a remote machine. H is its address (192.168.1.96, user@192.168.1.96 or ssh://user@host: reached
# over ssh, which needs key-based login) or tcp://host:2376. The script always asks for it; -DockerHost, LLM_DOCKER_HOST
# or DOCKER_HOST only provide the default (Enter accepts it). Without a terminal the default is used as is. Nothing is checked on this machine except the docker CLI. The GPU, the model folder and the container all live on
# the docker host, so they are checked through docker. Paths (MODELS_DIR, CACHE_DIR) are paths ON THE DOCKER HOST.
# Optional environment: MODELS_DIR, CACHE_DIR, LLAMA_PORT, HOST_ADDR (address of the docker host as seen from here
# and from the eval machine; derived from DOCKER_HOST or the docker context when not set). They override <model>.env.
# The image is pulled, not built. The eval never starts or stops this server: you do, with this script.
param(
    [ValidateSet('up', 'status', 'down')][string]$Command = 'up',
    [string]$Model = '',
    [string]$DockerHost = ''
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Step($t) { Write-Host "`n== $t" }
function Fail($t) { Write-Host "ERROR: $t" -ForegroundColor Red; exit 1 }
function Ask($q, $default) { $a = Read-Host "$q [$default]"; if ([string]::IsNullOrWhiteSpace($a)) { $default } else { $a } }

$script:EnvFile = ''
$script:Cfg = @{}
$script:Addr = 'localhost'

function Load-Env {
    if (-not (Test-Path $script:EnvFile)) { Fail "$($script:EnvFile) not found" }
    $script:Cfg = @{}
    foreach ($line in Get-Content $script:EnvFile) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2
        $k = $k.Trim()
        $fromEnv = [Environment]::GetEnvironmentVariable($k)
        # values already in the environment win over the env file
        $script:Cfg[$k] = if ($fromEnv) { $fromEnv } else { $v.Trim() }
        [Environment]::SetEnvironmentVariable($k, $script:Cfg[$k], 'Process')
    }
}

function Compose { docker compose --env-file $script:EnvFile @args; if ($LASTEXITCODE -ne 0) { Fail "docker compose $($args -join ' ') failed" } }

# address of the docker host: HOST_ADDR, else from DOCKER_HOST / the context endpoint, else this machine
function Docker-Host-Addr {
    if ($env:HOST_ADDR) { return $env:HOST_ADDR }
    $ep = $env:DOCKER_HOST
    if (-not $ep) { $ep = (docker context inspect --format '{{.Endpoints.docker.Host}}' 2>$null) }
    if ($ep -match '^(tcp|ssh)://(?:[^@/]*@)?([^:/]+)') { return $Matches[2] }
    return 'localhost'
}

# makes the remote daemon the target of every docker command of this script
function Select-Host {
    $def = $script:DockerHost
    if (-not $def) { $def = $env:LLM_DOCKER_HOST }
    if (-not $def) { $def = $env:DOCKER_HOST }
    $h = $def
    if ([Environment]::UserInteractive) { $h = Ask 'Docker host (address, user@address, ssh://... or tcp://...)' $def }
    if ([string]::IsNullOrWhiteSpace($h)) { Fail 'a docker host is required: pass -DockerHost <address> or set DOCKER_HOST' }
    $env:DOCKER_HOST = if ($h -match '^(ssh|tcp)://') { $h } else { "ssh://$h" }
}

function Check-Docker {
    Step '1. Docker'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'the docker CLI is not installed on this machine' }
    Select-Host
    Write-Host "docker host: $env:DOCKER_HOST"
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Fail "'docker compose' plugin is missing on this machine" }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { Fail "cannot reach the docker daemon (DOCKER_HOST='$env:DOCKER_HOST'). Is it running, is ssh/tcp access set up?" }
    $script:Addr = Docker-Host-Addr
    Write-Host ("daemon: " + (docker info --format '{{.Name}} ({{.OperatingSystem}})') + " at $($script:Addr)")
    $info = docker info 2>$null | Out-String
    if ($info -notmatch '(?i)runtimes:.*nvidia') {
        Write-Host 'WARNING: the daemon lists no nvidia runtime. On the docker host install nvidia-container-toolkit (Linux) or enable GPU support (Docker Desktop, WSL2).'
    }
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
    Write-Host "using $($script:EnvFile): file=$($script:Cfg.MODEL_FILE) alias=$($script:Cfg.MODEL_ALIAS) context=$($script:Cfg.CTX_TOKENS) image=$($script:Cfg.LLAMA_IMAGE)"
}

function Check-Gpu {
    Step '3. GPU on the docker host (checked through docker, pulls the image if needed)'
    $out = docker run --rm --gpus all --entrypoint nvidia-smi $script:Cfg.LLAMA_IMAGE --query-gpu=name,memory.total --format=csv,noheader 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { Fail "a container could not use the GPU on the docker host:`n$out`nCheck the NVIDIA driver and the container GPU support on that host." }
    Write-Host ("GPU: " + ($out -split "`n" | Select-Object -First 1).Trim())
}

function Check-Files {
    Step '4. Model file on the docker host'
    while ($true) {
        $dir = $script:Cfg.MODELS_DIR
        $out = docker run --rm -v "${dir}:/models:ro" --entrypoint ls $script:Cfg.LLAMA_IMAGE -lh "/models/$($script:Cfg.MODEL_FILE)" 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) { Write-Host "found $dir/$($script:Cfg.MODEL_FILE) on the docker host: $(($out -split '\s+')[4])"; break }
        Write-Host "Not found: $dir/$($script:Cfg.MODEL_FILE) (path on the docker host; the env files carry example paths)."
        if (-not [Environment]::UserInteractive) { Fail "set MODELS_DIR to the folder with $($script:Cfg.MODEL_FILE) on the docker host" }
        Write-Host 'Download it on the docker host from Hugging Face, for example:'
        Write-Host "  pip install -U huggingface_hub; huggingface-cli download <repo-with-$($script:Cfg.MODEL_FILE)> $($script:Cfg.MODEL_FILE) --local-dir <folder>"
        $dir = Ask 'Folder on the docker host with the .gguf files (Ctrl-C to stop)' $dir
        $script:Cfg.MODELS_DIR = $dir
        [Environment]::SetEnvironmentVariable('MODELS_DIR', $dir, 'Process')
    }
    $cache = $script:Cfg.CACHE_DIR
    if (-not $cache -or $cache -notlike "$($script:Cfg.MODELS_DIR)*") {
        $script:Cfg.CACHE_DIR = "$($script:Cfg.MODELS_DIR)/cache"
        [Environment]::SetEnvironmentVariable('CACHE_DIR', $script:Cfg.CACHE_DIR, 'Process')
    }
}

function Deploy {
    Step '5. Deploy (one model, one request slot)'
    Compose pull
    Compose up -d --force-recreate
}

function Port { if ($script:Cfg.LLAMA_PORT) { $script:Cfg.LLAMA_PORT } else { '8080' } }

function Wait-Ready {
    Step '6. Waiting for the model to load (a 27B model can take minutes)'
    $port = Port
    for ($i = 0; $i -lt 120; $i++) {
        try {
            $r = Invoke-WebRequest "http://$($script:Addr):$port/health" -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { Write-Host 'server is ready'; return }
        } catch { }
        Write-Host -NoNewline '.'
        Start-Sleep 5
    }
    docker logs --tail 30 jllm-eval-llama
    Fail "no answer from http://$($script:Addr):$port/health in 10 minutes. See the log above; if the container is fine, open port $port in the firewall of the docker host."
}

function Report {
    Step '7. What the server reports'
    $port = Port
    $id = '?'; $nctx = '?'; $slots = '?'
    try {
        $id = (Invoke-RestMethod "http://$($script:Addr):$port/v1/models" -TimeoutSec 10).data[0].id
        $p = Invoke-RestMethod "http://$($script:Addr):$port/props" -TimeoutSec 10
        $nctx = $p.default_generation_settings.n_ctx
        $slots = $p.total_slots
    } catch { Write-Host "WARNING: could not read the server properties: $($_.Exception.Message)" }
    Write-Host "model id: $id   n_ctx: $nctx   slots: $slots"
    if ($id -ne $script:Cfg.MODEL_ALIAS) { Write-Host "WARNING: id differs from the expected alias '$($script:Cfg.MODEL_ALIAS)'" }
    if ("$slots" -ne '1' -and "$slots" -ne '?') { Write-Host "WARNING: $slots slots: cache results will be flagged unreliable (need 1)" }
    $small = if ($script:Model -eq 'small') { 'true' } else { 'false' }
    Step '8. Next: on the machine that runs the eval (from core/jllm-eval)'
    Write-Host "check:  curl http://$($script:Addr):$port/v1/models"
    Write-Host 'set in src/test/resources/eval.properties (or with -D):'
    Write-Host "  eval.remote.gpu.baseUrl=http://$($script:Addr):$port/v1"
    Write-Host "  eval.remote.gpu.model=$id"
    Write-Host "  eval.remote.gpu.contextTokens=$nctx   (not more than n_ctx)"
    Write-Host "  eval.remote.gpu.smallModel=$small"
    Write-Host 'then, with ANTHROPIC_API_KEY set for the judge:'
    Write-Host '  mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=smoke" "-Deval.target=remote.gpu"'
    Write-Host "If the eval machine cannot reach the address, open port $port in the firewall of the docker host."
}

switch ($Command) {
    'up' { Check-Docker; Pick-Model; Check-Gpu; Check-Files; Deploy; Wait-Ready; Report }
    'status' { Check-Docker; if (-not $Model) { $Model = 'strong' }; $script:Model = $Model; $script:EnvFile = "$Model.env"; Load-Env; Report }
    'down' { Check-Docker; if (-not $Model) { $Model = 'strong' }; $script:EnvFile = "$Model.env"; Load-Env; Compose down }
}
