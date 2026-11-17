#!/usr/bin/env bash
# Guided deployment of the llama.cpp server used by jllm-eval (Linux host with an NVIDIA GPU). Run from core/jllm-eval.
#
#   ./docker/llm.sh up [strong|alt|small]   check the host, load one model, wait until it answers, print the eval commands
#   ./docker/llm.sh status                  show what the server reports
#   ./docker/llm.sh down                    stop and remove the container
#
# Optional environment: MODELS_DIR (folder with the .gguf files), CACHE_DIR, LLAMA_PORT, HOST_ADDR (address the eval
# machine uses to reach this host). They override the values in <model>.env.
# The image is pulled, not built. The eval never starts or stops this server: you do, with this script.
set -u
cd "$(dirname "$0")"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ask()  { local a; read -r -p "$1 [$2]: " a; printf '%s' "${a:-$2}"; }

cmd="${1:-up}"
model="${2:-}"

compose() { docker compose --env-file "$ENV_FILE" "$@"; }

load_env() {
  # values already in the environment win over the env file
  [ -f "$ENV_FILE" ] || fail "$ENV_FILE not found"
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue;; esac
    [ -z "${!k:-}" ] && export "$k=$v"
  done < "$ENV_FILE"
}

check_host() {
  step "1. Host checks"
  command -v docker >/dev/null || fail "docker is not installed (https://docs.docker.com/engine/install/)"
  docker compose version >/dev/null 2>&1 || fail "'docker compose' plugin is missing"
  docker info >/dev/null 2>&1 || fail "cannot talk to the docker daemon (is it running? are you in the docker group?)"
  say "docker: ok"
  if command -v nvidia-smi >/dev/null && nvidia-smi -L >/dev/null 2>&1; then
    say "GPU: $(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader | head -1)"
  else
    fail "no NVIDIA GPU visible (nvidia-smi failed). Install the NVIDIA driver."
  fi
  if ! docker info 2>/dev/null | grep -qi nvidia; then
    say "WARNING: the docker daemon lists no nvidia runtime. Install nvidia-container-toolkit, then"
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
  say "using $ENV_FILE: file=$MODEL_FILE alias=$MODEL_ALIAS context=$CTX_TOKENS"
}

check_files() {
  step "3. Model files"
  if [ -z "${MODELS_DIR_SET:-}" ] && [ ! -d "$MODELS_DIR" ]; then
    say "MODELS_DIR '$MODELS_DIR' does not exist on this host (the env files carry Windows paths)."
    MODELS_DIR="$(ask 'Folder with the .gguf files' "$HOME/models")"
    export MODELS_DIR
  fi
  mkdir -p "$MODELS_DIR" 2>/dev/null || true
  case "$CACHE_DIR" in [A-Za-z]:*|'') CACHE_DIR="$MODELS_DIR/cache"; export CACHE_DIR;; esac
  mkdir -p "$CACHE_DIR" || fail "cannot create $CACHE_DIR"
  if [ ! -f "$MODELS_DIR/$MODEL_FILE" ]; then
    say "Missing: $MODELS_DIR/$MODEL_FILE"
    say "Download it from Hugging Face, for example:"
    say "  pip install -U huggingface_hub"
    say "  huggingface-cli download <repo-with-$MODEL_FILE> $MODEL_FILE --local-dir \"$MODELS_DIR\""
    fail "put the file in $MODELS_DIR and run again"
  fi
  say "found $MODELS_DIR/$MODEL_FILE ($(du -h "$MODELS_DIR/$MODEL_FILE" | cut -f1))"
}

deploy() {
  step "4. Deploy (one model, one request slot)"
  compose pull
  compose up -d --force-recreate || fail "docker compose up failed"
}

wait_ready() {
  step "5. Waiting for the model to load (a 27B model can take minutes)"
  local port="${LLAMA_PORT:-8080}" i
  for i in $(seq 1 120); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$port/health")" = "200" ]; then
      say "server is ready"; return 0
    fi
    printf '.'; sleep 5
  done
  say; docker logs --tail 30 jllm-eval-llama
  fail "the server did not become ready in 10 minutes (log above)"
}

report() {
  step "6. What the server reports"
  local port="${LLAMA_PORT:-8080}"
  local id slots nctx
  id="$(curl -s "http://localhost:$port/v1/models" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  nctx="$(curl -s "http://localhost:$port/props" | grep -o '"n_ctx":[0-9]*' | head -1 | cut -d: -f2)"
  slots="$(curl -s "http://localhost:$port/props" | grep -o '"total_slots":[0-9]*' | head -1 | cut -d: -f2)"
  say "model id: ${id:-?}   n_ctx: ${nctx:-?}   slots: ${slots:-?}"
  [ "$id" = "${MODEL_ALIAS:-}" ] || say "WARNING: id differs from the expected alias '${MODEL_ALIAS:-}'"
  [ "${slots:-1}" = "1" ] || say "WARNING: $slots slots: cache results will be flagged unreliable (need 1)"
  local addr="${HOST_ADDR:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
  step "7. Next: on the machine that runs the eval"
  say "check:  curl http://${addr:-<this-host>}:$port/v1/models"
  say "set in core/jllm-eval/src/test/resources/eval.properties (or with -D):"
  say "  eval.remote.gpu.baseUrl=http://${addr:-<this-host>}:$port/v1"
  say "  eval.remote.gpu.model=${id:-$MODEL_ALIAS}"
  say "  eval.remote.gpu.contextTokens=${nctx:-$CTX_TOKENS}   (not more than n_ctx)"
  say "  eval.remote.gpu.smallModel=$([ "$model" = small ] && echo true || echo false)"
  say "then, with ANTHROPIC_API_KEY set for the judge:"
  say "  mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=remote.gpu"
  say "open port $port in this host's firewall if the check from the eval machine fails."
}

case "$cmd" in
  up)     check_host; pick_model; check_files; deploy; wait_ready; report;;
  status) model="${model:-strong}"; ENV_FILE="$model.env"; load_env; report;;
  down)   ENV_FILE="${model:-strong}.env"; load_env; compose down;;
  *)      fail "usage: $0 up [strong|alt|small] | status | down";;
esac
