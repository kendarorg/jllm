package org.kendar.llm.harness.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The size estimate shared by the context strategies: characters / {@value #CHARS_PER_TOKEN}, corrected per agent by
 * the ratio between the prompt the provider measured and the estimate of the request that was sent.
 */
public final class TokenEstimate {

    /** Characters per token of the estimate (before calibration against measured usage). */
    public static final double CHARS_PER_TOKEN = 3.5;
    private static final double MIN_RATIO = 0.5;
    private static final double MAX_RATIO = 2.0;

    /** {@code chatId \n agentKey} to {lastChars, ratio}. */
    private final Map<String, double[]> cal = new ConcurrentHashMap<>();

    public static long tokens(long chars) {
        return chars <= 0 ? 0 : (long) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** Measured tokens / estimated tokens of the agent's last request; 1 until a comparable measure arrives. */
    public double ratio(String chatId, String agentKey) {
        double[] c = cal.get(key(chatId, agentKey));
        return c == null ? 1.0 : c[1];
    }

    /** The request about to be sent holds {@code chars} characters (messages and the fixed part). */
    public void sent(String chatId, String agentKey, long chars) {
        cal.compute(key(chatId, agentKey), (k, c) -> {
            if (c == null) return new double[]{chars, 1.0};
            c[0] = chars;
            return c;
        });
    }

    /** The provider measured the last request sent as {@code tokens}; a ratio outside 0.5..2 is not comparable. */
    public void measured(String chatId, String agentKey, long tokens) {
        if (tokens <= 0) return;
        cal.computeIfPresent(key(chatId, agentKey), (k, c) -> {
            if (c[0] > 0) {
                double r = tokens / (c[0] / CHARS_PER_TOKEN);
                if (r >= MIN_RATIO && r <= MAX_RATIO) c[1] = r;
                c[0] = 0; // one measure per request sent
            }
            return c;
        });
    }

    /** The provider refused a request the estimate let through: trust the estimate less from now on. */
    public void overflowed(String chatId, String agentKey) {
        cal.compute(key(chatId, agentKey), (k, c) -> {
            if (c == null) c = new double[]{0, 1.0};
            c[1] = Math.min(MAX_RATIO, Math.max(c[1], 1.0) * 1.3);
            c[0] = 0;
            return c;
        });
    }

    public void release(String chatId, String agentKey) {
        cal.remove(key(chatId, agentKey));
    }

    public void forget(String chatId) {
        cal.keySet().removeIf(k -> k.startsWith(chatId + "\n"));
    }

    private static String key(String chatId, String agentKey) {
        return chatId + "\n" + agentKey;
    }
}
