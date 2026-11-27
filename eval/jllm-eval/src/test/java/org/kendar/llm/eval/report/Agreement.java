package org.kendar.llm.eval.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Step 9 tooling: agreement between the judge and the human labels, per rubric. Rows without a human verdict are
 * ignored. False-pass rate: share of human-fail items the judge passed; false-fail rate: share of human-pass items the
 * judge failed.
 */
public final class Agreement {

    public static final double MIN_AGREEMENT = 0.85;
    public static final double MAX_FALSE_PASS = 0.05;

    private Agreement() {
    }

    public record RubricAgreement(String rubric, int labeled, double agreement, double falsePassRate,
                                  double falseFailRate, boolean flagged, String reason) {
    }

    /** @param labels CSV or JSON written by {@link LabelExport} with humanVerdict filled in (pass/fail) */
    public static List<RubricAgreement> compute(Path labels) {
        // per rubric: agree, total, humanFail, falsePass, humanPass, falseFail
        Map<String, int[]> m = new TreeMap<>();
        for (LabelExport.Row r : LabelExport.read(labels)) {
            String h = r.humanVerdict() == null ? "" : r.humanVerdict().strip().toLowerCase(Locale.ROOT);
            if (!h.equals("pass") && !h.equals("fail")) continue;
            boolean judgePass = r.judgeVerdict().equalsIgnoreCase("pass");
            boolean humanPass = h.equals("pass");
            int[] a = m.computeIfAbsent(r.rubric(), k -> new int[6]);
            a[1]++;
            if (judgePass == humanPass) a[0]++;
            if (humanPass) {
                a[4]++;
                if (!judgePass) a[5]++;
            } else {
                a[2]++;
                if (judgePass) a[3]++;
            }
        }
        List<RubricAgreement> out = new ArrayList<>();
        m.forEach((rubric, a) -> {
            double agree = (double) a[0] / a[1];
            double fp = a[2] == 0 ? 0 : (double) a[3] / a[2];
            double ff = a[4] == 0 ? 0 : (double) a[5] / a[4];
            List<String> why = new ArrayList<>();
            if (agree < MIN_AGREEMENT) why.add(String.format(Locale.ROOT, "agreement %.0f%% below 85%%", agree * 100));
            if (fp > MAX_FALSE_PASS) why.add(String.format(Locale.ROOT, "false passes %.0f%% above 5%%", fp * 100));
            out.add(new RubricAgreement(rubric, a[1], agree, fp, ff, !why.isEmpty(), String.join("; ", why)));
        });
        return out;
    }

    /** Plain-text table of the result. */
    public static String format(List<RubricAgreement> rs) {
        StringBuilder sb = new StringBuilder("rubric | labeled | agreement | false-pass | false-fail | flag\n");
        for (RubricAgreement r : rs)
            sb.append(String.format(Locale.ROOT, "%s | %d | %.1f%% | %.1f%% | %.1f%% | %s%n", r.rubric(), r.labeled(),
                    r.agreement() * 100, r.falsePassRate() * 100, r.falseFailRate() * 100,
                    r.flagged() ? "FLAGGED: " + r.reason() : "ok"));
        return sb.toString();
    }
}
