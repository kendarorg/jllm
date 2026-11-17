# Observability

The library depends on `slf4j-api` only: no Micrometer, no OpenTelemetry. It reports through two small interfaces,
and the adapters below are **recipes**, not shipped modules.

## Metrics

`HarnessMetrics` is called for every model call, token usage, tool call, turn, compaction, context action and
approval. `HarnessPricing` turns token counts into an estimated cost (USD per million tokens).

```java
builder.metrics(new HarnessMetrics() {
    public void modelCall(Duration d, String outcome) { }          // ok, error, cancelled
    public void tokens(Usage u) { }
    public void toolCall(String tool, String outcome, Duration d) { }
    public void turn() { }
    public void compaction(int tier) { }
    public void context(String kind) { }
    public void approval(String kind, boolean approved) { }
});
builder.pricing(new HarnessPricing(3.0, 15.0, 0.30, 3.75));
```

### Recipe: Micrometer

```java
final class MicrometerMetrics implements HarnessMetrics {
    private final MeterRegistry r;
    MicrometerMetrics(MeterRegistry r) { this.r = r; }
    public void modelCall(Duration d, String outcome) { r.timer("jllm.model.call", "outcome", outcome).record(d); }
    public void toolCall(String tool, String outcome, Duration d) {
        r.timer("jllm.tool.call", "tool", tool, "outcome", outcome).record(d);
    }
    public void tokens(Usage u) {
        r.counter("jllm.tokens", "type", "input").increment(u.inputTokens());
        r.counter("jllm.tokens", "type", "output").increment(u.outputTokens());
        r.counter("jllm.tokens", "type", "cache_read").increment(u.cacheReadTokens());
        r.counter("jllm.tokens", "type", "cache_write").increment(u.cacheCreationTokens());
    }
    public void turn() { r.counter("jllm.turns").increment(); }
    public void compaction(int tier) { r.counter("jllm.compactions", "tier", String.valueOf(tier)).increment(); }
    public void context(String kind) { r.counter("jllm.context", "kind", kind).increment(); }
    public void approval(String kind, boolean ok) {
        r.counter("jllm.approvals", "kind", kind, "approved", String.valueOf(ok)).increment();
    }
}
```

Keep tag values low-cardinality: tool names and outcomes are, chat ids are not. `context(String)` has a default empty
implementation; the other methods are required.

## Tracing

`POST_LLM` and `POST_TOOL` hooks carry `durationMs`, `usage` and an error flag, which is all a tracer needs. The
OpenTelemetry sketch is in [EXTENDING](../guides/extending.md#tracing): a plugin registers the two hooks and creates a
span per model call and per tool call.

## Logs

Logging goes through SLF4J; bring your backend. For a durable record of what the agent did, record events or use hooks
([Governance and audit](governance-and-audit.md)) rather than relying on application logs.

## Cost control

Pricing gives an estimate per chat. Limits that bound spend: `maxTurns` (model calls per user turn), `maxTokens`
(output per response), the context window with its compaction strategy, and a cheaper fast model for summaries and
classification ([CACHES](../reference/caches.md)). Tools are sorted once and fixed per chat, so the provider's prompt
cache stays valid.
