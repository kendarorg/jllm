#!/usr/bin/env bash
# One-iteration smoke run of jllm-eval against the Anthropic API, Claude Haiku 5.5 as target and fast model.
# The judge is Haiku 5.5 by default (JUDGE_MODEL overrides it); with the same model as target the run is self-graded.
# Asks for the Anthropic API key (hidden input) unless ANTHROPIC_API_KEY is already set.
set -euo pipefail

# ---- settings
ANTHROPIC_BASE="${ANTHROPIC_BASE:-https://api.anthropic.com}"
MODEL="${MODEL:-claude-haiku-5-5}"
JUDGE_MODEL="${JUDGE_MODEL:-claude-haiku-5-5}"
THINKING="${THINKING:-}"          # target effort: minimal|low|medium|high; empty = the model's default
# Sonnet 5.5 at medium effort (about 19x the Haiku cost; the judge stays on Haiku):
# MODEL="${MODEL:-claude-sonnet-5-5}"
# THINKING="${THINKING:-medium}"
CTX_TOKENS="${CTX_TOKENS:-200000}"
COMPACTION_CTX_TOKENS="${COMPACTION_CTX_TOKENS:-65536}"   # context of the compaction group only
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
CASE_TIMEOUT_SECONDS="${CASE_TIMEOUT_SECONDS:-$TIMEOUT_SECONDS}"
TEST_GROUPS="${TEST_GROUPS:-all}"   # "all" or a comma separated list of: smoke,full,strategy,compaction,unit
TEST_GROUPS="${TEST_GROUPS// /}"
if [[ ",$TEST_GROUPS," == *,all,* ]]; then TEST_GROUPS="smoke,full,strategy,compaction,unit"; fi
TRIALS="${TRIALS:-1}"
MAX_TRIALS="${MAX_TRIALS:-2}"
IGNORE_TEST_FAILURES="${IGNORE_TEST_FAILURES:-1}"
RPM_LIMIT="${RPM_LIMIT:-120}"      # requests per minute to the API (target, judge and fast share it); 0 = unpaced
PARALLELISM="${PARALLELISM:-10}"   # test classes run at once; 1 = strictly sequential

# ---- paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
LOG_DIR="$MODULE_DIR/eval-results"
LOG_FILE="$LOG_DIR/anthropic-smoke-$(date +%Y%m%d-%H%M%S).log"

# ---- preflight
command -v mvn >/dev/null || { echo "mvn not found in PATH" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not found in PATH" >&2; exit 1; }

# ---- key
if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  if [[ ! -t 0 ]]; then
    echo "ANTHROPIC_API_KEY is not set and no terminal is available to ask for it" >&2
    exit 1
  fi
  read -rsp "Anthropic API key (sk-ant-...): " ANTHROPIC_API_KEY
  echo
fi
[[ -n "$ANTHROPIC_API_KEY" ]] || { echo "empty API key" >&2; exit 1; }

# ---- preflight against the API (key is passed by header, never printed)
served="$(curl -fsS -m 20 -H "x-api-key: $ANTHROPIC_API_KEY" -H "anthropic-version: 2023-06-01" \
  "$ANTHROPIC_BASE/v1/models?limit=1000")" || {
  echo "Anthropic API not reachable or key rejected at $ANTHROPIC_BASE" >&2; exit 1; }
for m in "$MODEL" "$JUDGE_MODEL"; do
  grep -q "\"id\":\"$m\"" <<<"$served" || {
    echo "Model '$m' not served by the Anthropic API. Override with MODEL=<id> / JUDGE_MODEL=<id>." >&2
    exit 1; }
done

mkdir -p "$LOG_DIR"
echo "Log: $LOG_FILE"
echo "Model: $MODEL  thinking: ${THINKING:-default}  judge: $JUDGE_MODEL  ctx: $CTX_TOKENS  compaction ctx: $COMPACTION_CTX_TOKENS  groups: $TEST_GROUPS  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  rpm: $RPM_LIMIT  parallelism: $PARALLELISM"

# ---- run
export EVAL_ANTHROPIC_APIKEY="$ANTHROPIC_API_KEY"

set +e
mvn -B -ntp -f "$REPO_ROOT/pom.xml" -Peval -pl core/jllm-eval -am test \
  -Dgroups="$TEST_GROUPS" \
  -Drun.parallelism="$PARALLELISM" \
  -Drun.parallel.enabled="$([[ "$PARALLELISM" -gt 1 ]] && echo true || echo false)" \
  -Drun.parallel.classes="$([[ "$PARALLELISM" -gt 1 ]] && echo concurrent || echo same_thread)" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore="$([[ "$IGNORE_TEST_FAILURES" == 1 ]] && echo true || echo false)" \
  -Deval.target=anthropic \
  -Deval.anthropic.baseUrl="$ANTHROPIC_BASE" \
  -Deval.anthropic.model="$MODEL" \
  -Deval.anthropic.contextTokens="$CTX_TOKENS" \
  -Deval.anthropic.timeoutSeconds="$TIMEOUT_SECONDS" \
  -Deval.anthropic.maxRpm="$RPM_LIMIT" \
  ${THINKING:+-Deval.anthropic.thinking="$THINKING"} \
  -Deval.judge=anthropic \
  -Deval.judge.anthropic.model="$JUDGE_MODEL" \
  -Deval.fast=target \
  -Deval.trials="$TRIALS" \
  -Deval.maxTrials="$MAX_TRIALS" \
  -Deval.caseTimeoutSeconds="$CASE_TIMEOUT_SECONDS" \
  -Deval.compaction.contextTokens="$COMPACTION_CTX_TOKENS" \
  -Deval.timeout.warmup="$TIMEOUT_SECONDS" \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

unset ANTHROPIC_API_KEY EVAL_ANTHROPIC_APIKEY

echo
echo "Exit: $status   Log: $LOG_FILE"
echo "Results: $LOG_DIR"
exit "$status"
