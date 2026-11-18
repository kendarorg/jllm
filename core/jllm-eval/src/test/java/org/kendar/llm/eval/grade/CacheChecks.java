package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.eval.run.RecordedRequest;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.llm.Usage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Cache grading over the main-loop requests of a trial. Ratios need the server's cached-token usage; the prefix
 * stability checks work on the requests alone and always run.
 */
public final class CacheChecks {

    /** Part of the request where two consecutive requests differ. */
    public enum Part {SYSTEM, TOOLS, MESSAGES}

    /**
     * A change in the part of the request a cache keys on, between two consecutive main requests.
     *
     * @param request        index of the later request
     * @param firstChanged   index of the first changed message (when MESSAGES is in parts), else -1
     * @param before         that message in the earlier request, null when it did not exist
     * @param after          that message in the later request, null when it no longer exists
     */
    public record Break(int request, Set<Part> parts, int firstChanged, JsonNode before, JsonNode after) {
        @Override
        public String toString() {
            return "request " + request + " changed " + parts + (firstChanged >= 0 ? " from message " + firstChanged : "");
        }
    }

    /** Which breaks are acceptable, with a description for reports. */
    public record ExpectedBreak(String description, Predicate<Break> allows) {

        /** No break is expected. */
        public static ExpectedBreak none() {
            return new ExpectedBreak("no break", b -> false);
        }

        /** Only the system part changes (mode switch). */
        public static ExpectedBreak systemOnly() {
            return new ExpectedBreak("system part only", b -> b.parts().equals(EnumSet.of(Part.SYSTEM)));
        }

        /** Only the tool list changes (tool toggle). */
        public static ExpectedBreak toolsOnly() {
            return new ExpectedBreak("tool part only", b -> b.parts().equals(EnumSet.of(Part.TOOLS)));
        }

        /** Only a message that became a mask receipt (mnemonic). */
        public static ExpectedBreak newlyMasked() {
            return new ExpectedBreak("only at a newly masked message", b -> b.parts().equals(EnumSet.of(Part.MESSAGES))
                    && b.after() != null && Msgs.masked(b.after()) && (b.before() == null || !Msgs.masked(b.before())));
        }

        public ExpectedBreak or(ExpectedBreak o) {
            return new ExpectedBreak(description + " or " + o.description, b -> allows.test(b) || o.allows.test(b));
        }
    }

    private CacheChecks() {
    }

    // ---- measurements ---------------------------------------------------------------------------------------

    /** The serialized request as a cache sees it: system, tools, then the messages. */
    public static String serialize(RecordedRequest r) {
        String messages = r.messages().toString();
        // the closing bracket changes on every appended message, so it is not part of the cached prefix
        return r.prefix() + "\u0001" + messages.substring(0, messages.length() - 1);
    }

    /** Length in characters of the common beginning of two serialized requests. */
    public static int sharedPrefixLength(RecordedRequest a, RecordedRequest b) {
        String x = serialize(a);
        String y = serialize(b);
        int n = Math.min(x.length(), y.length());
        int i = 0;
        while (i < n && x.charAt(i) == y.charAt(i)) i++;
        return i;
    }

    /** Cached share of the context per main request ({@code NaN} when the request has no usage or no tokens). */
    public static List<Double> cachedRatios(TrialTrace trace) {
        List<Double> out = new ArrayList<>();
        for (RecordedRequest r : trace.mainRequests()) {
            Usage u = r.usage();
            out.add(u == null || u.contextTokens() <= 0 ? Double.NaN : (double) u.cacheReadTokens() / u.contextTokens());
        }
        return out;
    }

    /** True when at least one main request reports cached or cache-creation tokens. */
    public static boolean measurable(TrialTrace trace) {
        return trace.mainRequests().stream().anyMatch(r -> r.usage() != null
                && (r.usage().cacheReadTokens() > 0 || r.usage().cacheCreationTokens() > 0));
    }

    /** Changes between consecutive main requests. A request that merely appends messages is not a break. */
    public static List<Break> breaks(TrialTrace trace) {
        List<RecordedRequest> reqs = trace.mainRequests();
        List<Break> out = new ArrayList<>();
        for (int i = 1; i < reqs.size(); i++) {
            RecordedRequest a = reqs.get(i - 1);
            RecordedRequest b = reqs.get(i);
            Set<Part> parts = EnumSet.noneOf(Part.class);
            if (!Objects.equals(nz(a.pinnedSystem()), nz(b.pinnedSystem()))
                    || !Objects.equals(nz(a.extraSystem()), nz(b.extraSystem()))) parts.add(Part.SYSTEM);
            if (!a.toolNames().equals(b.toolNames())) parts.add(Part.TOOLS);
            int common = Math.min(a.messages().size(), b.messages().size());
            int k = 0;
            while (k < common && a.messages().get(k).equals(b.messages().get(k))) k++;
            boolean changed = k < common || b.messages().size() < a.messages().size();
            if (changed) parts.add(Part.MESSAGES);
            if (parts.isEmpty()) continue;
            out.add(new Break(b.index(), parts, changed ? k : -1,
                    changed && k < a.messages().size() ? a.messages().get(k) : null,
                    changed && k < b.messages().size() ? b.messages().get(k) : null));
        }
        return out;
    }

    // ---- checks ---------------------------------------------------------------------------------------------

    /**
     * Mean cached share over the main requests after the first is at least {@code min}.
     *
     * @param sharedServer true when the server is shared or multi-slot: the outcome is then WARN, never FAIL
     */
    public static Check cachedRatioAtLeast(double min, boolean sharedServer) {
        String n = String.format(Locale.ROOT, "cachedRatioAtLeast(%.2f)", min);
        return Check.of(n, Check.Tier.CACHE, ctx -> {
            if (!measurable(ctx.trace())) {
                return CheckResult.notMeasurable(n, "the server reports no cached tokens");
            }
            List<Double> all = cachedRatios(ctx.trace());
            List<Double> later = all.size() > 1 ? all.subList(1, all.size()) : List.of();
            double mean = later.stream().filter(d -> !d.isNaN()).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            if (Double.isNaN(mean)) return CheckResult.notMeasurable(n, "fewer than two requests with usage");
            String detail = String.format(Locale.ROOT, "mean cached ratio %.2f over %d request(s)", mean, later.size());
            boolean ok = mean >= min;
            if (sharedServer) {
                return CheckResult.warn(n, "shared server, not graded: " + detail + (ok ? "" : ", below " + min));
            }
            return ok ? CheckResult.pass(n, detail) : CheckResult.fail(n, detail + ", below " + min);
        });
    }

    /** Every break between consecutive requests is allowed by {@code expected}. */
    public static Check breaksAsExpected(ExpectedBreak expected) {
        String n = "breaksAsExpected(" + expected.description() + ")";
        return Check.of(n, Check.Tier.CACHE, ctx -> {
            if (ctx.trace().mainRequests().size() < 2) return CheckResult.notMeasurable(n, "fewer than two requests");
            List<Break> all = breaks(ctx.trace());
            for (Break b : all) {
                if (!expected.allows().test(b)) return CheckResult.fail(n, "unexpected break: " + b);
            }
            return CheckResult.pass(n, all.size() + " break(s), all expected");
        });
    }

    /** At least one break allowed by {@code expected} happened (the planted cause did break the cache). */
    public static Check breakOccurs(ExpectedBreak expected) {
        String n = "breakOccurs(" + expected.description() + ")";
        return Check.of(n, Check.Tier.CACHE, ctx -> {
            if (ctx.trace().mainRequests().size() < 2) return CheckResult.notMeasurable(n, "fewer than two requests");
            return breaks(ctx.trace()).stream().anyMatch(expected.allows())
                    ? CheckResult.pass(n, "expected break found")
                    : CheckResult.fail(n, "no break of kind '" + expected.description() + "'; breaks: " + breaks(ctx.trace()));
        });
    }

    /** Between consecutive main requests the shared prefix covers at least {@code minFraction} of the earlier one. */
    public static Check prefixStable(double minFraction) {
        String n = String.format(Locale.ROOT, "prefixStable(%.2f)", minFraction);
        return Check.of(n, Check.Tier.CACHE, ctx -> {
            List<RecordedRequest> reqs = ctx.trace().mainRequests();
            if (reqs.size() < 2) return CheckResult.notMeasurable(n, "fewer than two requests");
            double worst = 1.0;
            int at = -1;
            for (int i = 1; i < reqs.size(); i++) {
                int len = serialize(reqs.get(i - 1)).length();
                double f = len == 0 ? 1.0 : (double) sharedPrefixLength(reqs.get(i - 1), reqs.get(i)) / len;
                if (f < worst) {
                    worst = f;
                    at = reqs.get(i).index();
                }
            }
            String detail = String.format(Locale.ROOT, "lowest shared prefix %.2f%s", worst, at >= 0 ? " at request " + at : "");
            return worst >= minFraction ? CheckResult.pass(n, detail) : CheckResult.fail(n, detail + ", below " + minFraction);
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
