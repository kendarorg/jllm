package org.kendar.llm.eval.report;

import java.util.ArrayList;
import java.util.List;

/** Minimal document model rendered to Markdown and standalone HTML. */
final class Doc {

    private record Table(List<String> header, List<List<String>> rows) {
    }

    private record Part(String kind, String text, Table table) {
    }

    private final String title;
    private final List<Part> parts = new ArrayList<>();

    Doc(String title) {
        this.title = title;
    }

    Doc h(String t) { parts.add(new Part("h", t, null)); return this; }

    Doc p(String t) { parts.add(new Part("p", t, null)); return this; }

    Doc warn(String t) { parts.add(new Part("warn", t, null)); return this; }

    Doc svg(String s) { parts.add(new Part("svg", s, null)); return this; }

    Doc table(List<String> header, List<List<String>> rows) {
        parts.add(new Part("table", null, new Table(header, rows)));
        return this;
    }

    String markdown() {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        for (Part p : parts) {
            switch (p.kind) {
                case "h" -> sb.append("## ").append(p.text).append("\n\n");
                case "p" -> sb.append(p.text).append("\n\n");
                case "warn" -> sb.append("> WARNING: ").append(p.text).append("\n\n");
                case "svg" -> sb.append("(chart: see the HTML report)\n\n");
                default -> {
                    sb.append("| ").append(String.join(" | ", p.table.header.stream().map(Doc::cell).toList()))
                            .append(" |\n|").append("---|".repeat(p.table.header.size())).append('\n');
                    for (List<String> r : p.table.rows)
                        sb.append("| ").append(String.join(" | ", r.stream().map(Doc::cell).toList())).append(" |\n");
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    String html() {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>")
                .append(esc(title)).append("</title><style>")
                .append("body{font-family:sans-serif;margin:2em;max-width:1100px}")
                .append("table{border-collapse:collapse;margin:1em 0}")
                .append("td,th{border:1px solid #bbb;padding:4px 10px;text-align:left}th{background:#eee}")
                .append(".warn{background:#fff3cd;border:1px solid #e0a800;padding:8px}</style></head><body><h1>")
                .append(esc(title)).append("</h1>\n");
        for (Part p : parts) {
            switch (p.kind) {
                case "h" -> sb.append("<h2>").append(esc(p.text)).append("</h2>\n");
                case "p" -> sb.append("<p>").append(esc(p.text)).append("</p>\n");
                case "warn" -> sb.append("<p class=\"warn\">WARNING: ").append(esc(p.text)).append("</p>\n");
                case "svg" -> sb.append(p.text).append('\n');
                default -> {
                    sb.append("<table><tr>");
                    p.table.header.forEach(c -> sb.append("<th>").append(esc(c)).append("</th>"));
                    sb.append("</tr>\n");
                    for (List<String> r : p.table.rows) {
                        sb.append("<tr>");
                        r.forEach(c -> sb.append("<td>").append(esc(c)).append("</td>"));
                        sb.append("</tr>\n");
                    }
                    sb.append("</table>\n");
                }
            }
        }
        return sb.append("</body></html>\n").toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
