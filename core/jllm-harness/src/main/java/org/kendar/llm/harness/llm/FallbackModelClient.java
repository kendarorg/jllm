package org.kendar.llm.harness.llm;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tries the providers of a chain in order: when one fails (after its own retries) with a {@link ModelException}, the
 * next is tried. Cancellations and context overflows are never retried on another provider.
 */
public final class FallbackModelClient implements ModelClient {

    /** A provider of the chain; the client is created on first use. */
    public record Candidate(String name, Supplier<ModelClient> client) {
    }

    private final List<Candidate> chain;
    private volatile Consumer<String> onFallback = n -> {
    };

    public FallbackModelClient(List<Candidate> chain) {
        if (chain.isEmpty()) throw new IllegalArgumentException("Empty provider chain");
        this.chain = List.copyOf(chain);
    }

    /** Called with the name of the provider that is about to be tried after a failure. */
    public FallbackModelClient onFallback(Consumer<String> listener) {
        this.onFallback = listener == null ? n -> {
        } : listener;
        return this;
    }

    private interface Call<T> {
        T run(ModelClient c);
    }

    private <T> T run(Call<T> call) {
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            try {
                if (i > 0) onFallback.accept(chain.get(i).name());
                return call.run(chain.get(i).client().get());
            } catch (ModelCancelledException e) {
                throw e;
            } catch (ModelException e) {
                if (ModelException.isContextOverflow(e)) throw e;
                last = e;
            } catch (IllegalArgumentException | IllegalStateException e) {
                // a fallback that cannot be built (missing key...) is skipped
                last = e;
            }
        }
        throw last;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return run(c -> c.complete(request));
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return run(c -> c.complete(request, cancel));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return run(c -> c.simpleComplete(system, user, maxTokens));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 Consumer<Usage> usageSink) {
        return run(c -> c.simpleComplete(system, user, maxTokens, temperature, usageSink));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Consumer<Usage> usageSink) {
        return run(c -> c.simpleComplete(system, user, maxTokens, usageSink));
    }
}
