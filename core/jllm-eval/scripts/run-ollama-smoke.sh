#!/usr/bin/env bash
# One-iteration smoke run of jllm-eval against a local Ollama server.
# Target, judge and fast model all use the same model on the same server (self-graded run).
set -euo pipefail

# ---- settings
OLLAMA_BASE="${OLLAMA_BASE:-http://localhost:11434}"
MODEL="${MODEL:-qwen3:30b}"
CTX_TOKENS="${CTX_TOKENS:-16000}"          # must not exceed the context Ollama actually allocates (num_ctx)
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"          # per model request, and warm-up
CASE_TIMEOUT_SECONDS="${CASE_TIMEOUT_SECONDS:-$TIMEOUT_SECONDS}"  # cap on each user turn of a case
#TEST_GROUPS="${TEST_GROUPS:-smoke}"
TEST_GROUPS="${TEST_GROUPS:-smoke,full,strategy,unit}"
#TESTS="${TESTS:-InfrastructureSmokeTest,ExampleToolEvalTest}"
TESTS=""
TRIALS="${TRIALS:-1}"
MAX_TRIALS="${MAX_TRIALS:-2}"              # cap on trials of any case, including cases that set their own count
# 1 (default): failed cases are reported but never fail the Maven build, so the run always completes and writes its summary
IGNORE_TEST_FAILURES="${IGNORE_TEST_FAILURES:-1}"
# minimal = Ollama reasoning_effort "none": Qwen3 thinking would eat the token budget and leave empty replies
THINKING="${THINKING:-minimal}"

# ---- paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
LOG_DIR="$MODULE_DIR/eval-results"
LOG_FILE="$LOG_DIR/ollama-smoke-$(date +%Y%m%d-%H%M%S).log"

# ---- preflight
command -v mvn >/dev/null || { echo "mvn not found in PATH" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not found in PATH" >&2; exit 1; }

served="$(curl -fsS -m 10 "$OLLAMA_BASE/v1/models")" || {
  echo "Ollama not reachable at $OLLAMA_BASE (start it with: ollama serve)" >&2; exit 1; }
grep -q "\"id\":\"$MODEL\"" <<<"$served" || {
  echo "Model '$MODEL' not served by Ollama. Available: $(ollama list 2>/dev/null | awk 'NR>1{print $1}' | tr '\n' ' ')" >&2
  exit 1; }

mkdir -p "$LOG_DIR"
echo "Log: $LOG_FILE"
echo "Model: $MODEL  ctx: $CTX_TOKENS  groups: $TEST_GROUPS  tests: ${TESTS:-all}  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  case timeout cap: ${CASE_TIMEOUT_SECONDS}s"

# ---- run
TEST_FILTER=()
if [[ -n "$TESTS" ]]; then TEST_FILTER=(-Dtest="$TESTS"); fi

set +e
mvn -B -ntp -f "$REPO_ROOT/pom.xml" -Peval -pl core/jllm-eval -am test \
  -Dgroups="$TEST_GROUPS" \
  ${TEST_FILTER[@]+"${TEST_FILTER[@]}"} \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore="$([[ "$IGNORE_TEST_FAILURES" == 1 ]] && echo true || echo false)" \
  -Deval.target=remote.ollama \
  -Deval.remote.ollama.protocol=openai \
  -Deval.remote.ollama.baseUrl="$OLLAMA_BASE/v1" \
  -Deval.remote.ollama.model="$MODEL" \
  -Deval.remote.ollama.contextTokens="$CTX_TOKENS" \
  -Deval.remote.ollama.smallModel=false \
  -Deval.remote.ollama.timeoutSeconds="$TIMEOUT_SECONDS" \
  -Deval.remote.ollama.thinking="$THINKING" \
  -Deval.judge=openai \
  -Deval.judge.openai.name=ollama \
  -Deval.judge.openai.baseUrl="$OLLAMA_BASE/v1" \
  -Deval.judge.openai.model="$MODEL" \
  -Deval.judge.openai.contextTokens="$CTX_TOKENS" \
  -Deval.judge.openai.timeoutSeconds="$TIMEOUT_SECONDS" \
  -Deval.judge.openai.thinking="$THINKING" \
  -Deval.fast=target \
  -Deval.trials="$TRIALS" \
  -Deval.maxTrials="$MAX_TRIALS" \
  -Deval.caseTimeoutSeconds="$CASE_TIMEOUT_SECONDS" \
  -Deval.timeout.warmup="$TIMEOUT_SECONDS" \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

echo
echo "Exit: $status   Log: $LOG_FILE"
echo "Results: $LOG_DIR"
exit "$status"
