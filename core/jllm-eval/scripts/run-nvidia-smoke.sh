#!/usr/bin/env bash
# One-iteration smoke run of jllm-eval against NVIDIA NIM hosted models (free tier).
# Target, judge and fast model all use the same model on the same endpoint (self-graded run).
# Asks for the NVIDIA API key (hidden input) unless NVIDIA_API_KEY is already set.
set -euo pipefail

# ---- settings
NVIDIA_BASE="${NVIDIA_BASE:-https://integrate.api.nvidia.com/v1}"
MODEL="${MODEL:-nvidia/nemotron-3.5-lightning-30b-a3b}"
CTX_TOKENS="${CTX_TOKENS:-16000}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
CASE_TIMEOUT_SECONDS="${CASE_TIMEOUT_SECONDS:-$TIMEOUT_SECONDS}"
TEST_GROUPS="${TEST_GROUPS:-smoke,full,strategy,unit}"
TRIALS="${TRIALS:-1}"
MAX_TRIALS="${MAX_TRIALS:-2}"
IGNORE_TEST_FAILURES="${IGNORE_TEST_FAILURES:-1}"

# ---- paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
LOG_DIR="$MODULE_DIR/eval-results"
LOG_FILE="$LOG_DIR/nvidia-smoke-$(date +%Y%m%d-%H%M%S).log"

# ---- preflight
command -v mvn >/dev/null || { echo "mvn not found in PATH" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not found in PATH" >&2; exit 1; }

# ---- token
if [[ -z "${NVIDIA_API_KEY:-}" ]]; then
  if [[ ! -t 0 ]]; then
    echo "NVIDIA_API_KEY is not set and no terminal is available to ask for it" >&2
    exit 1
  fi
  read -rsp "NVIDIA API key (nvapi-...): " NVIDIA_API_KEY
  echo
fi
[[ -n "$NVIDIA_API_KEY" ]] || { echo "empty API key" >&2; exit 1; }

# ---- preflight against the endpoint (key is passed by header, never printed)
served="$(curl -fsS -m 20 -H "Authorization: Bearer $NVIDIA_API_KEY" "$NVIDIA_BASE/models")" || {
  echo "NVIDIA endpoint not reachable or key rejected at $NVIDIA_BASE" >&2; exit 1; }
grep -q "\"id\":\"$MODEL\"" <<<"$served" || {
  echo "Model '$MODEL' not served by NVIDIA NIM. Override with MODEL=<org/model>." >&2
  exit 1; }

mkdir -p "$LOG_DIR"
echo "Log: $LOG_FILE"
echo "Model: $MODEL  ctx: $CTX_TOKENS  groups: $TEST_GROUPS  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s"

# ---- run
export EVAL_REMOTE_NVIDIA_APIKEY="$NVIDIA_API_KEY"
export EVAL_JUDGE_OPENAI_APIKEY="$NVIDIA_API_KEY"

set +e
mvn -B -ntp -f "$REPO_ROOT/pom.xml" -Peval -pl core/jllm-eval -am test \
  -Dgroups="$TEST_GROUPS" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore="$([[ "$IGNORE_TEST_FAILURES" == 1 ]] && echo true || echo false)" \
  -Deval.target=remote.nvidia \
  -Deval.remote.nvidia.protocol=openai \
  -Deval.remote.nvidia.baseUrl="$NVIDIA_BASE" \
  -Deval.remote.nvidia.model="$MODEL" \
  -Deval.remote.nvidia.contextTokens="$CTX_TOKENS" \
  -Deval.remote.nvidia.smallModel=false \
  -Deval.remote.nvidia.timeoutSeconds="$TIMEOUT_SECONDS" \
  -Deval.judge=openai \
  -Deval.judge.openai.name=nvidia \
  -Deval.judge.openai.baseUrl="$NVIDIA_BASE" \
  -Deval.judge.openai.model="$MODEL" \
  -Deval.judge.openai.contextTokens="$CTX_TOKENS" \
  -Deval.judge.openai.timeoutSeconds="$TIMEOUT_SECONDS" \
  -Deval.fast=target \
  -Deval.trials="$TRIALS" \
  -Deval.maxTrials="$MAX_TRIALS" \
  -Deval.caseTimeoutSeconds="$CASE_TIMEOUT_SECONDS" \
  -Deval.timeout.warmup="$TIMEOUT_SECONDS" \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

unset NVIDIA_API_KEY EVAL_REMOTE_NVIDIA_APIKEY EVAL_JUDGE_OPENAI_APIKEY

echo
echo "Exit: $status   Log: $LOG_FILE"
echo "Results: $LOG_DIR"
exit "$status"
