#!/usr/bin/env bash
# Guided deployment of the llama.cpp server used by jllm-eval. Run from core/jllm-eval.
#
#   ./docker/llm.sh up [strong|alt|small] [--host H]   check docker and the GPU, load one model, wait until it answers
#   ./docker/llm.sh status [model] [--host H]          show what the server reports
#   ./docker/llm.sh down [model] [--host H]            stop and remove the container
#
# The docker host is a remote machine. H is its address (192.168.1.96, user@192.168.1.96 or ssh://user@host: reached
# over ssh, which needs key-based login) or tcp://host:2376. The script always asks for it; --host, LLM_DOCKER_HOST or
# DOCKER_HOST only provide the default (Enter accepts it). Without a terminal the default is used as is. Nothing is checked on this machine except the docker CLI. The GPU, the model folder and the container all live on
# the docker host, so they are checked through docker. Paths (MODELS_DIR, CACHE_DIR) are paths ON THE DOCKER HOST.
# Optional environment: MODELS_DIR, CACHE_DIR, LLAMA_PORT, HOST_ADDR (address of the docker host as seen from here
# and from the eval machine; derived from DOCKER_HOST or the docker context when not set). They override <model>.env.
# The image is pulled, not built. The eval never starts or stops this server: you do, with this script.
set -u
cd "$(dirname "$0")"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ask()  { local a; read -r -p "$1 [$2]: " a; printf '%s' "${a:-$2}"; }

cmd="${1:-up}"; shift || true
model=""; dhost="${LLM_DOCKER_HOST:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --host) dhost="${2:-}"; shift 2;;
    --host=*) dhost="${1#--host=}"; shift;;
    *) model="$1"; shift;;
  esac
done

compose() { docker compose --env-file "$ENV_FILE" "$@"; }

load_env() {
  [ -f "$ENV_FILE" ] || fail "$ENV_FILE not found"
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue;; esac
    [ -z "${!k:-}" ] && export "$k=$v"   # values already in the environment win
  done < "$ENV_FILE"
}

# address of the docker host: HOST_ADDR, else from DOCKER_HOST / the context endpoint, else this machine
docker_host_addr() {
  if [ -n "${HOST_ADDR:-}" ]; then printf '%s' "$HOST_ADDR"; return; fi
  local ep="${DOCKER_HOST:-}"
  [ -z "$ep" ] && ep="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"
  case "$ep" in
    tcp://*|ssh://*) ep="${ep#*://}"; ep="${ep#*@}"; printf '%s' "${ep%%:*}"; return;;
  esac
  printf 'localhost'
}

# makes the remote daemon the target of every docker command of this script
select_host() {
  local def="${dhost:-${DOCKER_HOST:-}}"
  if [ -t 0 ]; then
    dhost="$(ask 'Docker host (address, user@address, ssh://... or tcp://...)' "$def")"
  else
    dhost="$def"
  fi
  [ -n "$dhost" ] || fail "a docker host is required: pass --host <address> or set DOCKER_HOST"
  case "$dhost" in
    ssh://*|tcp://*) export DOCKER_HOST="$dhost";;
    *) export DOCKER_HOST="ssh://$dhost";;
  esac
}

check_docker() {
  step "1. Docker"
  command -v docker >/dev/null || fail "the docker CLI is not installed on this machine"
  select_host
  say "docker host: ${DOCKER_HOST:-<local default>}"
  docker compose version >/dev/null 2>&1 || fail "'docker compose' plugin is missing on this machine"
  docker info >/dev/null 2>&1 || fail "cannot reach the docker daemon (DOCKER_HOST='${DOCKER_HOST:-<default>}'). Is it running, is ssh/tcp access set up?"
  DOCKER_ADDR="$(docker_host_addr)"
  say "daemon: $(docker info --format '{{.Name}} ({{.OperatingSystem}})' 2>/dev/null) at ${DOCKER_ADDR}"
  if ! docker info 2>/dev/null | grep -qi 'runtimes:.*nvidia'; then
    say "WARNING: the daemon lists no nvidia runtime. On the docker host install nvidia-container-toolkit, then"
    say "         'sudo nvidia-ctk runtime configure --runtime=docker && sudo systemctl restart docker'."
  fi
}

pick_model() {
  step "2. Model"
  if [ -z "$model" ]; then
    say "strong = Qwen3.6-27B 4-bit | alt = gpt-oss-20b | small = 8-12B 4-bit (small-model prompt)"
    model="$(ask 'Which model' strong)"
  fi
  case "$model" in strong|alt|small) ;; *) fail "unknown model '$model' (use strong, alt or small)";; esac
  ENV_FILE="$model.env"
  load_env
  say "using $ENV_FILE: file=$MODEL_FILE alias=$MODEL_ALIAS context=$CTX_TOKENS image=$LLAMA_IMAGE"
}

check_gpu() {
  step "3. GPU on the docker host (checked through docker, pulls the image if needed)"
  local out
  out="$(docker run --rm --gpus all --entrypoint nvidia-smi "$LLAMA_IMAGE" \
        --query-gpu=name,memory.total --format=csv,noheader 2>&1)" \
    || fail "a container could not use the GPU on the docker host:\n$out\nCheck the NVIDIA driver and nvidia-container-toolkit on that host."
  say "GPU: $(printf '%s' "$out" | head -1)"
}

check_files() {
  step "4. Model file on the docker host"
  local out
  while :; do
    if out="$(docker run --rm -v "$MODELS_DIR:/models:ro" --entrypoint ls "$LLAMA_IMAGE" -lh "/models/$MODEL_FILE" 2>&1)"; then
      say "found $MODELS_DIR/$MODEL_FILE on the docker host: $(printf '%s' "$out" | awk '{print $5}')"
      break
    fi
    say "Not found: $MODELS_DIR/$MODEL_FILE (path on the docker host; the env files carry example paths)."
    [ -t 0 ] || fail "set MODELS_DIR to the folder with $MODEL_FILE on the docker host"
    say "Download it on the docker host from Hugging Face, for example:"
    say "  pip install -U huggingface_hub && huggingface-cli download <repo-with-$MODEL_FILE> $MODEL_FILE --local-dir <folder>"
    MODELS_DIR="$(ask 'Folder on the docker host with the .gguf files (Ctrl-C to stop)' "$MODELS_DIR")"
    export MODELS_DIR
  done
  case "$CACHE_DIR" in "$MODELS_DIR"*|/*|[A-Za-z]:*) ;; *) CACHE_DIR="$MODELS_DIR/cache"; export CACHE_DIR;; esac
}

deploy() {
  step "5. Deploy (one model, one request slot)"
  compose pull
  compose up -d --force-recreate || fail "docker compose up failed"
}

wait_ready() {
  step "6. Waiting for the model to load (a 27B model can take minutes)"
  local port="${LLAMA_PORT:-8080}" i
  for i in $(seq 1 120); do
    if [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$DOCKER_ADDR:$port/health")" = "200" ]; then
      say "server is ready"; return 0
    fi
    printf '.'; sleep 5
  done
  say; docker logs --tail 30 jllm-eval-llama
  fail "no answer from http://$DOCKER_ADDR:$port/health in 10 minutes. See the log above; if the container is fine, open port $port in the firewall of the docker host."
}

report() {
  step "7. What the server reports"
  local port="${LLAMA_PORT:-8080}" id slots nctx
  id="$(curl -s -m 10 "http://$DOCKER_ADDR:$port/v1/models" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  nctx="$(curl -s -m 10 "http://$DOCKER_ADDR:$port/props" | grep -o '"n_ctx":[0-9]*' | head -1 | cut -d: -f2)"
  slots="$(curl -s -m 10 "http://$DOCKER_ADDR:$port/props" | grep -o '"total_slots":[0-9]*' | head -1 | cut -d: -f2)"
  say "model id: ${id:-?}   n_ctx: ${nctx:-?}   slots: ${slots:-?}"
  [ "$id" = "${MODEL_ALIAS:-}" ] || say "WARNING: id differs from the expected alias '${MODEL_ALIAS:-}'"
  [ "${slots:-1}" = "1" ] || say "WARNING: $slots slots: cache results will be flagged unreliable (need 1)"
  step "8. Next: on the machine that runs the eval (from core/jllm-eval)"
  say "check:  curl http://$DOCKER_ADDR:$port/v1/models"
  say "set in src/test/resources/eval.properties (or with -D):"
  say "  eval.remote.gpu.baseUrl=http://$DOCKER_ADDR:$port/v1"
  say "  eval.remote.gpu.model=${id:-$MODEL_ALIAS}"
  say "  eval.remote.gpu.contextTokens=${nctx:-$CTX_TOKENS}   (not more than n_ctx)"
  say "  eval.remote.gpu.smallModel=$([ "$model" = small ] && echo true || echo false)"
  say "then, with ANTHROPIC_API_KEY set for the judge:"
  say "  mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=remote.gpu"
  say "If the eval machine cannot reach the address, open port $port in the firewall of the docker host."
}

case "$cmd" in
  up)     check_docker; pick_model; check_gpu; check_files; deploy; wait_ready; report;;
  status) check_docker; model="${model:-strong}"; ENV_FILE="$model.env"; load_env; report;;
  down)   check_docker; ENV_FILE="${model:-strong}.env"; load_env; compose down;;
  *)      fail "usage: $0 up [strong|alt|small] | status | down";;
esac
