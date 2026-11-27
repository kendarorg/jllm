#!/usr/bin/env bash
# One-iteration smoke run of jllm-eval; target (tested model) and judge each pick their own provider, model and key.
# Providers: anthropic | nvidia | ollama | llamacpp. The fast model is the target.
#
#   ./run-smoke.sh                 # pick one of the *.env presets in envs/
#   ./run-smoke.sh nvidia.env      # use that preset
#   ./run-smoke.sh none            # no preset: environment and built-in defaults only
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
#
# Run pinning and reliability:
#   DIRTY_TREE   refuse | warn | allow (default warn): what to do when tracked files differ from HEAD (the eval results
#                folder excluded). refuse stops before the warm-up call and lists the files. A baseline update
#                (UPDATE_BASELINE=1) from a dirty tree is always refused.
#   PASS_K       comma list of k for pass@k / pass^k; empty = 1 and MAX_TRIALS. A k above a case's trials is not measured.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="$SCRIPT_DIR/envs"

# ---- preset
PRESET="${1:-}"
if [[ -z "$PRESET" ]]; then
  shopt -s nullglob
  presets=("$ENV_DIR"/*.env)
  shopt -u nullglob
  if (( ${#presets[@]} )); then
    [[ -t 0 ]] || { echo "no preset given and no terminal to choose one; pass <file>.env or none" >&2; exit 1; }
    echo "Presets:"
    echo "  0) none"
    for i in "${!presets[@]}"; do echo "  $((i + 1))) $(basename "${presets[$i]}")"; done
    read -rp "Choose [0-${#presets[@]}]: " choice
    [[ "$choice" =~ ^[0-9]+$ ]] && (( choice <= ${#presets[@]} )) || { echo "invalid choice '$choice'" >&2; exit 1; }
    PRESET="none"
    (( choice == 0 )) || PRESET="${presets[$((choice - 1))]}"
  else
    PRESET="none"
  fi
fi
if [[ "$PRESET" != none ]]; then
  [[ -f "$PRESET" ]] || PRESET="$ENV_DIR/$PRESET"
  [[ -f "$PRESET" ]] || { echo "preset not found: $PRESET" >&2; exit 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || { echo "$PRESET: bad line '$line'" >&2; exit 1; }
    k="${BASH_REMATCH[1]}"; v="${BASH_REMATCH[2]}"
    [[ "$k" != *API_KEY* ]] || { echo "$PRESET: keys must not be in a preset ($k)" >&2; exit 1; }
    if [[ "$v" =~ ^\"(.*)\"$ || "$v" =~ ^\'(.*)\'$ ]]; then v="${BASH_REMATCH[1]}"; fi
    [[ -n "${!k+x}" ]] || printf -v "$k" '%s' "$v"
  done < "$PRESET"
fi

# ---- provider defaults
# require <name>: the model id and the address are never assumed; a missing one stops the run
require()          { if [[ -z "${!1:-}" ]]; then echo "$1 is not set: give it in the environment or in the preset" >&2; exit 1; fi; }
default_ctx()      { case "$1" in anthropic) echo 200000;; *) echo 16000;; esac; }
default_rpm()      { case "$1" in anthropic) echo 300;; nvidia) echo 10;; ollama|llamacpp) echo 0;; esac; }
default_thinking() { case "$1" in ollama) echo minimal;; *) echo "";; esac; }
default_par()      { case "$1" in anthropic) echo 20;; nvidia) echo 2;; ollama|llamacpp) echo 1;; esac; }
key_env()          { case "$1" in anthropic) echo ANTHROPIC_API_KEY;; nvidia) echo NVIDIA_API_KEY;; ollama|llamacpp) echo "";; esac; }
check_provider()   { case "$2" in anthropic|nvidia|ollama|llamacpp) ;; *) echo "$1: must be anthropic, nvidia, ollama or llamacpp (got '$2')" >&2; exit 1;; esac; }

# ---- settings
TARGET_PROVIDER="${TARGET_PROVIDER:-anthropic}"
check_provider TARGET_PROVIDER "$TARGET_PROVIDER"
JUDGE_PROVIDER="${JUDGE_PROVIDER:-$TARGET_PROVIDER}"
check_provider JUDGE_PROVIDER "$JUDGE_PROVIDER"

require TARGET_MODEL
require TARGET_BASE
TARGET_BASE="${TARGET_BASE%/}"
TARGET_CTX_TOKENS="${TARGET_CTX_TOKENS:-$(default_ctx "$TARGET_PROVIDER")}"
TARGET_RPM="${TARGET_RPM:-$(default_rpm "$TARGET_PROVIDER")}"
TARGET_THINKING="${TARGET_THINKING-$(default_thinking "$TARGET_PROVIDER")}"
TARGET_MODEL_NAME="${TARGET_MODEL_NAME:-}"

require JUDGE_MODEL
require JUDGE_BASE
JUDGE_BASE="${JUDGE_BASE%/}"
JUDGE_CTX_TOKENS="${JUDGE_CTX_TOKENS:-$(default_ctx "$JUDGE_PROVIDER")}"
JUDGE_RPM="${JUDGE_RPM:-$(default_rpm "$JUDGE_PROVIDER")}"
JUDGE_THINKING="${JUDGE_THINKING-$(default_thinking "$JUDGE_PROVIDER")}"
JUDGE_MODEL_NAME="${JUDGE_MODEL_NAME:-}"

COMPACTION_CTX_TOKENS="${COMPACTION_CTX_TOKENS:-65536}"   # context of the compaction group only
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"                  # per model request, and warm-up
CASE_TIMEOUT_SECONDS="${CASE_TIMEOUT_SECONDS:-$TIMEOUT_SECONDS}"  # cap on each user turn of a case
TEST_GROUPS="${TEST_GROUPS:-all}"   # "all" or a comma separated list of: smoke,full,strategy,compaction,unit
TEST_GROUPS="${TEST_GROUPS// /}"
if [[ ",$TEST_GROUPS," == *,all,* ]]; then TEST_GROUPS="smoke,full,strategy,compaction,unit"; fi
TESTS="${TESTS:-}"                  # optional comma separated test classes
TRIALS="${TRIALS:-1}"
DIRTY_TREE="${DIRTY_TREE:-warn}"
case "$DIRTY_TREE" in refuse|warn|allow) ;; *) echo "DIRTY_TREE must be refuse, warn or allow (got '$DIRTY_TREE')" >&2; exit 1;; esac
PASS_K="${PASS_K:-}"
MAX_TRIALS="${MAX_TRIALS:-2}"       # cap on trials of any case, including cases that set their own count
UPDATE_BASELINE="${UPDATE_BASELINE:-0}"   # 1: store this run as the model baseline
IGNORE_TEST_FAILURES="${IGNORE_TEST_FAILURES:-1}"   # 1: failed cases never fail the Maven build
PARALLELISM="${PARALLELISM:-$(default_par "$TARGET_PROVIDER")}"   # test classes run at once; 1 = strictly sequential
# added to the system prompt of every case; "none" turns it off. Some hosted models write a tool call as text.
if [[ "$TARGET_PROVIDER" == nvidia ]]; then
  SYSTEM_PROMPT_EXTRA="${SYSTEM_PROMPT_EXTRA:-Call tools only through the structured tool-calling interface with JSON arguments. Never write a tool call as plain text or inside your reasoning.}"
else
  SYSTEM_PROMPT_EXTRA="${SYSTEM_PROMPT_EXTRA:-none}"
fi

# ---- paths
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
LOG_DIR="$MODULE_DIR/eval-results"
LOG_FILE="$LOG_DIR/smoke-$TARGET_PROVIDER-$(date +%Y%m%d-%H%M%S).log"

JUDGE_BASE="${JUDGE_BASE%/}"

# ---- preflight
command -v mvn >/dev/null || { echo "mvn not found in PATH" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not found in PATH" >&2; exit 1; }

# ---- keys
# ask_key <role> <provider> <hint> <allow-empty>: prints the key read from the terminal
ask_key() {
  if [[ ! -t 0 ]]; then
    echo "${1^^}_API_KEY is not set and no terminal is available to ask for it" >&2
    exit 1
  fi
  local k
  read -rsp "$1 API key for $2$3: " k
  echo >&2
  [[ -n "$k" || "$4" == 1 ]] || { echo "empty $1 API key" >&2; exit 1; }
  printf '%s' "$k"
}

# key_from_env <role> <provider>: the role variable, then the provider variable
key_from_env() {
  local role_var="${1^^}_API_KEY" prov_var
  prov_var="$(key_env "$2")"
  if [[ -n "${!role_var:-}" ]]; then printf '%s' "${!role_var}"
  elif [[ -n "$prov_var" && -n "${!prov_var:-}" ]]; then printf '%s' "${!prov_var}"
  fi
}

# ollama and llamacpp always use the key "none"
fixed_key() { [[ "$1" == ollama || "$1" == llamacpp ]]; }
TARGET_API_KEY=none
if ! fixed_key "$TARGET_PROVIDER"; then
  TARGET_API_KEY="$(key_from_env target "$TARGET_PROVIDER")"
  [[ -n "$TARGET_API_KEY" ]] || TARGET_API_KEY="$(ask_key target "$TARGET_PROVIDER" '' 0)"
fi

JUDGE_API_KEY=none
if ! fixed_key "$JUDGE_PROVIDER"; then
  JUDGE_API_KEY="$(key_from_env judge "$JUDGE_PROVIDER")"
  if [[ -z "$JUDGE_API_KEY" ]]; then
    if [[ "$JUDGE_PROVIDER" == "$TARGET_PROVIDER" ]]; then
      JUDGE_API_KEY="$(ask_key judge "$JUDGE_PROVIDER" ' (empty = same as target)' 1)"
      [[ -n "$JUDGE_API_KEY" ]] || JUDGE_API_KEY="$TARGET_API_KEY"
    else
      JUDGE_API_KEY="$(ask_key judge "$JUDGE_PROVIDER" '' 0)"
    fi
  fi
fi

if fixed_key "$JUDGE_PROVIDER"; then
  JUDGE_KEY_LABEL=none
elif [[ "$JUDGE_PROVIDER" == "$TARGET_PROVIDER" && "$JUDGE_API_KEY" == "$TARGET_API_KEY" ]]; then
  JUDGE_KEY_LABEL=shared
else
  JUDGE_KEY_LABEL=own
fi

# ---- preflight against the endpoints (keys are passed by header, never printed)
# check_model <role> <provider> <base> <key> <model>
check_model() {
  local served
  if [[ "$2" == anthropic ]]; then
    served="$(curl -fsS -m 20 -H "x-api-key: $4" -H "anthropic-version: 2023-06-01" "$3/v1/models?limit=1000")"
  elif [[ -n "$4" ]]; then
    served="$(curl -fsS -m 20 -H "Authorization: Bearer $4" "$3/v1/models")"
  else
    served="$(curl -fsS -m 20 "$3/v1/models")"
  fi || { echo "$1: $2 not reachable or key rejected at $3" >&2; exit 1; }
  grep -q "\"id\":\"$5\"" <<<"$served" || {
    echo "$1: model '$5' not served by $2 at $3. Override with ${1^^}_MODEL=<id>." >&2
    exit 1; }
}
check_model target "$TARGET_PROVIDER" "$TARGET_BASE" "$TARGET_API_KEY" "$TARGET_MODEL"
check_model judge "$JUDGE_PROVIDER" "$JUDGE_BASE" "$JUDGE_API_KEY" "$JUDGE_MODEL"

mkdir -p "$LOG_DIR"
echo "Log: $LOG_FILE"
echo "Preset: $([[ "$PRESET" == none ]] && echo none || basename "$PRESET")"
echo "Target: $TARGET_PROVIDER $TARGET_MODEL${TARGET_MODEL_NAME:+ as $TARGET_MODEL_NAME}  thinking: ${TARGET_THINKING:-default}  ctx: $TARGET_CTX_TOKENS  rpm: $TARGET_RPM"
echo "Judge:  $JUDGE_PROVIDER $JUDGE_MODEL${JUDGE_MODEL_NAME:+ as $JUDGE_MODEL_NAME}  thinking: ${JUDGE_THINKING:-default}  ctx: $JUDGE_CTX_TOKENS  rpm: $JUDGE_RPM  key: $JUDGE_KEY_LABEL"
echo "Pinning: dirty tree: $DIRTY_TREE  pass@k: ${PASS_K:-default (1 and max trials)}"
echo "Compaction ctx: $COMPACTION_CTX_TOKENS  groups: $TEST_GROUPS  tests: ${TESTS:-all}  trials: $TRIALS  max trials: $MAX_TRIALS  timeout: ${TIMEOUT_SECONDS}s  case timeout cap: ${CASE_TIMEOUT_SECONDS}s  parallelism: $PARALLELISM"

# ---- eval settings
EVAL_ARGS=()
EXPORTED=()
export_secret() { export "$1=$2"; EXPORTED+=("$1"); }

if [[ "$TARGET_PROVIDER" == anthropic ]]; then
  EVAL_ARGS+=(
    -Deval.target=anthropic
    -Deval.anthropic.baseUrl="$TARGET_BASE"
    -Deval.anthropic.model="$TARGET_MODEL"
    -Deval.anthropic.contextTokens="$TARGET_CTX_TOKENS"
    -Deval.anthropic.timeoutSeconds="$TIMEOUT_SECONDS"
    -Deval.anthropic.maxRpm="$TARGET_RPM")
  [[ -z "$TARGET_THINKING" ]] || EVAL_ARGS+=(-Deval.anthropic.thinking="$TARGET_THINKING")
  export_secret EVAL_ANTHROPIC_APIKEY "$TARGET_API_KEY"
else
  r="eval.remote.$TARGET_PROVIDER"
  EVAL_ARGS+=(
    -Deval.target="remote.$TARGET_PROVIDER"
    -D"$r.protocol"=openai
    -D"$r.baseUrl"="$TARGET_BASE/v1"
    -D"$r.model"="$TARGET_MODEL"
    -D"$r.contextTokens"="$TARGET_CTX_TOKENS"
    -D"$r.smallModel"=false
    -D"$r.timeoutSeconds"="$TIMEOUT_SECONDS"
    -D"$r.maxRpm"="$TARGET_RPM")
  [[ -z "$TARGET_THINKING" ]] || EVAL_ARGS+=(-D"$r.thinking"="$TARGET_THINKING")
  [[ -z "$TARGET_API_KEY" ]] || export_secret "EVAL_REMOTE_${TARGET_PROVIDER^^}_APIKEY" "$TARGET_API_KEY"
fi

if [[ "$JUDGE_PROVIDER" == anthropic ]]; then
  EVAL_ARGS+=(-Deval.judge=anthropic -Deval.judge.anthropic.model="$JUDGE_MODEL")
  if [[ "$TARGET_PROVIDER" != anthropic ]]; then
    EVAL_ARGS+=(
      -Deval.anthropic.baseUrl="$JUDGE_BASE"
      -Deval.anthropic.contextTokens="$JUDGE_CTX_TOKENS"
      -Deval.anthropic.timeoutSeconds="$TIMEOUT_SECONDS"
      -Deval.anthropic.maxRpm="$JUDGE_RPM")
  fi
  [[ -z "$JUDGE_THINKING" ]] || EVAL_ARGS+=(-Deval.judge.anthropic.thinking="$JUDGE_THINKING")
  export_secret EVAL_JUDGE_ANTHROPIC_APIKEY "$JUDGE_API_KEY"
else
  EVAL_ARGS+=(
    -Deval.judge=openai
    -Deval.judge.openai.name="$JUDGE_PROVIDER"
    -Deval.judge.openai.baseUrl="$JUDGE_BASE/v1"
    -Deval.judge.openai.model="$JUDGE_MODEL"
    -Deval.judge.openai.contextTokens="$JUDGE_CTX_TOKENS"
    -Deval.judge.openai.timeoutSeconds="$TIMEOUT_SECONDS"
    -Deval.judge.openai.maxRpm="$JUDGE_RPM")
  [[ -z "$JUDGE_THINKING" ]] || EVAL_ARGS+=(-Deval.judge.openai.thinking="$JUDGE_THINKING")
  [[ -z "$JUDGE_API_KEY" ]] || export_secret EVAL_JUDGE_OPENAI_APIKEY "$JUDGE_API_KEY"
fi

[[ -z "$TARGET_MODEL_NAME" ]] || EVAL_ARGS+=(-Deval.target.modelName="$TARGET_MODEL_NAME")
[[ -z "$JUDGE_MODEL_NAME" ]] || EVAL_ARGS+=(-Deval.judge.modelName="$JUDGE_MODEL_NAME")
[[ "$SYSTEM_PROMPT_EXTRA" == none ]] || export_secret EVAL_SYSTEMPROMPT_EXTRA "$SYSTEM_PROMPT_EXTRA"
[[ -z "$TESTS" ]] || EVAL_ARGS+=(-Dtest="$TESTS")
[[ -z "$PASS_K" ]] || EVAL_ARGS+=(-Deval.passK="$PASS_K")

# ---- run
set +e
mvn -B -ntp -f "$REPO_ROOT/pom.xml" -Peval -pl eval/jllm-eval -am test \
  -Dgroups="$TEST_GROUPS" \
  -Drun.parallelism="$PARALLELISM" \
  -Drun.parallel.enabled="$([[ "$PARALLELISM" -gt 1 ]] && echo true || echo false)" \
  -Drun.parallel.classes="$([[ "$PARALLELISM" -gt 1 ]] && echo concurrent || echo same_thread)" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore="$([[ "$IGNORE_TEST_FAILURES" == 1 ]] && echo true || echo false)" \
  "${EVAL_ARGS[@]}" \
  -Deval.fast=target \
  -Deval.trials="$TRIALS" \
  -Deval.maxTrials="$MAX_TRIALS" \
  -Deval.updateBaseline="$([[ "$UPDATE_BASELINE" == 1 ]] && echo true || echo false)"   -Deval.dirtyTree="$DIRTY_TREE" \
  -Deval.caseTimeoutSeconds="$CASE_TIMEOUT_SECONDS" \
  -Deval.compaction.contextTokens="$COMPACTION_CTX_TOKENS" \
  -Deval.timeout.warmup="$TIMEOUT_SECONDS" \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

unset TARGET_API_KEY JUDGE_API_KEY ANTHROPIC_API_KEY NVIDIA_API_KEY "${EXPORTED[@]}"

echo
echo "Exit: $status   Log: $LOG_FILE"
echo "Results: $LOG_DIR"
exit "$status"
