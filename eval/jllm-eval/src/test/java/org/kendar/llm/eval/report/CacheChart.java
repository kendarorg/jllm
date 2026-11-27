package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Inline SVG line chart of the cache ratio per turn, one line per strategy. */
final class CacheChart {

    private static final String[] COLORS = {"#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e"};
    private static final int W = 640, H = 320, L = 50, R = 20, T = 20, B = 40;

    private CacheChart() {
    }

    /** @param strategies the summary's {@code strategies} object */
    static String svg(JsonNode strategies) {
        int maxTurns = 1;
        for (JsonNode s : strategies) maxTurns = Math.max(maxTurns, s.path("turnCacheRatio").size());
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(W).append("\" height=\"").append(H)
                .append("\" viewBox=\"0 0 ").append(W).append(' ').append(H)
                .append("\" font-family=\"sans-serif\" font-size=\"11\">");
        sb.append("<rect width=\"").append(W).append("\" height=\"").append(H).append("\" fill=\"#fff\"/>");
        for (int i = 0; i <= 4; i++) {
            double v = i / 4.0;
            double y = y(v);
            sb.append(String.format(Locale.ROOT,
                    "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#ddd\"/>"
                            + "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%.2f</text>",
                    L, y, W - R, y, L - 6, y + 4, v));
        }
        sb.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\">turn (model request)</text>",
                (L + W - R) / 2, H - 6));
        sb.append(String.format(Locale.ROOT, "<text x=\"14\" y=\"%d\" transform=\"rotate(-90 14 %d)\" "
                + "text-anchor=\"middle\">cache read ratio</text>", H / 2, H / 2));
        int ci = 0;
        Iterator<Map.Entry<String, JsonNode>> it = strategies.fields();
        while (it.hasNext()) {
            var e = it.next();
            String color = COLORS[ci % COLORS.length];
            JsonNode pts = e.getValue().path("turnCacheRatio");
            StringBuilder poly = new StringBuilder();
            for (int i = 0; i < pts.size(); i++) {
                double x = maxTurns == 1 ? L : L + (double) i * (W - L - R) / (maxTurns - 1);
                poly.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y(pts.get(i).asDouble())));
            }
            if (pts.size() > 0) {
                sb.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" points=\"")
                        .append(poly.toString().strip()).append("\"/>");
            }
            sb.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\"/>"
                            + "<text x=\"%d\" y=\"%d\">%s</text>", L + 10 + ci * 120, H - B + 2, color,
                    L + 24 + ci * 120, H - B + 11, Doc.esc(e.getKey())));
            ci++;
        }
        return sb.append("</svg>").toString();
    }

    private static double y(double v) {
        return T + (1 - Math.max(0, Math.min(1, v))) * (H - T - B);
    }
}
