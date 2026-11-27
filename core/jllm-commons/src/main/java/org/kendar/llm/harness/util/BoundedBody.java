package org.kendar.llm.harness.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * HTTP body handlers with a size limit: a body over the limit is abandoned (the subscription is cancelled, the
 * connection closed) and {@code HttpClient.send} fails with {@link TooLargeException}, instead of the body being read
 * into memory.
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    /** The response was larger than the limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * The {@link TooLargeException} behind an exception of {@code HttpClient.send} (which wraps the handler's failure),
     * or null when the failure is something else.
     */
    public static TooLargeException tooLarge(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TooLargeException e) return e;
            if (t.getCause() == t) break;
        }
        return null;
    }

    /** The body as bytes; at most {@code max}. */
    public static HttpResponse.BodyHandler<byte[]> ofBytes(long max) {
        return info -> {
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > max) return new Subscriber(0, "Response of " + declared + " bytes exceeds the limit of " + max);
            return new Subscriber(max, null);
        };
    }

    /** The body as UTF-8 text; at most {@code max} bytes. */
    public static HttpResponse.BodyHandler<String> ofString(long max) {
        return info -> HttpResponse.BodySubscribers.mapping(ofBytes(max).apply(info),
                b -> new String(b, StandardCharsets.UTF_8));
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long max;
        private final String refusal;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long total;

        Subscriber(long max, String refusal) {
            this.max = max;
            this.refusal = refusal;
        }

        @Override
        public CompletableFuture<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (refusal != null) {
                s.cancel();
                result.completeExceptionally(new TooLargeException(refusal));
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                total += b.remaining();
                if (total > max) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLargeException("Response exceeds the limit of " + max + " bytes"));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
