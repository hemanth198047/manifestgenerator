package com.example.casc.compare;

import com.example.casc.json.Json;
import com.example.casc.compare.JsonCompare.FileSummary;
import com.example.casc.compare.JsonCompare.Report;
import com.example.casc.compare.JsonCompare.Row;
import com.example.casc.compare.JsonCompare.SectionInfo;
import java.math.BigDecimal;
import java.util.List;

/** Renders a comparison as one self-contained HTML page (no external files, works offline, light and dark theme, printable). */
public final class HtmlReport {
    private HtmlReport() {}

    private static final int SHOW = 160;

    public static String render(Report r, String title, String generated) {
        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        h.append("<title>").append(esc(title)).append("</title>\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");
        h.append("<header><h1>").append(esc(title)).append("</h1><p class=\"sub\">Generated ").append(esc(generated)).append(" &middot; ")
                .append(r.sources().size()).append(" files &middot; baseline: <strong>").append(esc(r.sources().get(0).name())).append("</strong>");
        if (r.section() != null && !r.section().isBlank()) {
            h.append(" &middot; section: <code>").append(esc(r.section())).append("</code>");
        }
        h.append("</p></header>\n<main>\n");

        double similarity = r.totalPaths() == 0 ? 100.0 : 100.0 * r.identical() / r.totalPaths();
        h.append("<section aria-labelledby=\"s-sum\"><h2 id=\"s-sum\">Summary</h2><div class=\"cards\">");
        card(h, String.valueOf(r.sources().size()), "files compared", "");
        card(h, String.valueOf(r.totalPaths()), "distinct values (paths)", "");
        card(h, String.valueOf(r.identical()), "identical in every file", "ok");
        card(h, String.valueOf(r.differing()), "differ somewhere", r.differing() == 0 ? "ok" : "bad");
        card(h, String.format("%.1f%%", similarity), "identical overall", r.differing() == 0 ? "ok" : "");
        h.append("</div>");
        if (r.differing() == 0) {
            h.append("<p class=\"note okbox\">All compared files are identical.</p>");
        }
        h.append("</section>\n");

        h.append("<section aria-labelledby=\"s-files\"><h2 id=\"s-files\">Files compared with the baseline</h2><div class=\"scroll\"><table>\n<thead><tr>");
        for (String c : List.of("#", "File", "Size", "Values", "Added", "Removed", "Changed", "Same", "Match with baseline")) {
            h.append("<th>").append(c).append("</th>");
        }
        h.append("</tr></thead><tbody>\n");
        for (int i = 0; i < r.files().size(); i++) {
            FileSummary f = r.files().get(i);
            h.append("<tr><td>").append(i + 1).append("</td><td>").append(esc(f.name())).append(i == 0 ? " <span class=\"tag\">baseline</span>" : "").append("</td><td>")
                    .append(size(f.bytes())).append("</td><td>").append(f.values()).append("</td>");
            if (i == 0) {
                h.append("<td colspan=\"5\" class=\"muted\">the file the others are compared with</td>");
            } else {
                int denom = f.same() + f.changed() + f.removed() + f.added();
                double pct = denom == 0 ? 100.0 : 100.0 * f.same() / denom;
                h.append("<td class=\"add\">").append(f.added()).append("</td><td class=\"rem\">").append(f.removed()).append("</td><td class=\"chg\">")
                        .append(f.changed()).append("</td><td>").append(f.same()).append("</td><td><div class=\"bar\" role=\"img\" aria-label=\"")
                        .append(String.format("%.1f", pct)).append(" percent match\"><span style=\"width:").append(String.format("%.1f", pct).replace(',', '.'))
                        .append("%\"></span></div><span class=\"pct\">").append(String.format("%.1f%%", pct)).append("</span></td>");
            }
            h.append("</tr>\n");
        }
        h.append("</tbody></table></div><p class=\"legend\"><span class=\"add\">Added</span> = only in this file, not in the baseline &middot; <span class=\"rem\">Removed</span> = in the baseline, missing here &middot; ")
                .append("<span class=\"chg\">Changed</span> = in both with a different value &middot; Same = in both and equal.</p></section>\n");

        h.append("<section aria-labelledby=\"s-sec\"><h2 id=\"s-sec\">Sections</h2><p class=\"muted\">Number of values per top-level section in each file, and how many rows differ.</p><div class=\"scroll\"><table>\n<thead><tr><th>Section</th>");
        for (int i = 0; i < r.sources().size(); i++) {
            h.append("<th title=\"").append(esc(r.sources().get(i).name())).append("\">File ").append(i + 1).append("</th>");
        }
        h.append("<th>Differing rows</th></tr></thead><tbody>\n");
        for (SectionInfo s : r.sections()) {
            h.append("<tr><td><code>").append(esc(s.name())).append("</code></td>");
            for (int n : s.valuesPerFile()) {
                h.append("<td>").append(n == 0 ? "<span class=\"muted\">&mdash;</span>" : String.valueOf(n)).append("</td>");
            }
            h.append("<td class=\"").append(s.differingRows() == 0 ? "" : "chg").append("\">").append(s.differingRows()).append("</td></tr>\n");
        }
        h.append("</tbody></table></div></section>\n");

        h.append("<section aria-labelledby=\"s-diff\"><h2 id=\"s-diff\">Differences</h2>\n");
        if (r.differing() == 0) {
            h.append("<p class=\"muted\">Nothing differs.</p>");
        } else {
            h.append("<div class=\"toolbar\" role=\"group\" aria-label=\"Filter the differences\">")
                    .append("<button type=\"button\" class=\"f on\" data-f=\"all\">All</button>")
                    .append("<button type=\"button\" class=\"f\" data-f=\"CHANGED\">Changed</button>")
                    .append("<button type=\"button\" class=\"f\" data-f=\"ADDED\">Added</button>")
                    .append("<button type=\"button\" class=\"f\" data-f=\"REMOVED\">Removed</button>")
                    .append("<input id=\"q\" type=\"search\" placeholder=\"Search path or value\" aria-label=\"Search path or value\">")
                    .append("<span id=\"count\" class=\"muted\" aria-live=\"polite\"></span></div>\n");
            if (r.truncated()) {
                h.append("<p class=\"note warnbox\">Showing the first ").append(r.rows().size()).append(" of ").append(r.differing())
                        .append(" differing rows. Raise the limit with <code>--max-rows</code> to see more.</p>");
            }
            h.append("<div class=\"scroll tall\"><table id=\"diff\">\n<thead><tr><th>Status</th><th>Path</th>");
            for (int i = 0; i < r.sources().size(); i++) {
                h.append("<th title=\"").append(esc(r.sources().get(i).name())).append("\">").append(i == 0 ? "Baseline" : "File " + (i + 1)).append("<br><span class=\"fn\">")
                        .append(esc(shortName(r.sources().get(i).name()))).append("</span></th>");
            }
            h.append("</tr></thead><tbody>\n");
            for (Row row : r.rows()) {
                h.append("<tr data-s=\"").append(row.status()).append("\"><td><span class=\"badge ").append(row.status()).append("\">").append(label(row.status()))
                        .append("</span></td><td class=\"path\"><code>").append(esc(row.path())).append("</code></td>");
                Object base = row.values().get(0);
                for (int j = 0; j < row.values().size(); j++) {
                    Object v = row.values().get(j);
                    String cls = v == JsonCompare.MISSING ? "miss" : j > 0 && base != JsonCompare.MISSING && !equal(base, v) ? "diff" : j > 0 && base == JsonCompare.MISSING ? "diff" : "";
                    h.append("<td class=\"v ").append(cls).append("\">").append(cell(v)).append("</td>");
                }
                h.append("</tr>\n");
            }
            h.append("</tbody></table></div>");
        }
        h.append("</section>\n</main>\n<footer>Compared with casc-generator. Arrays of objects are matched by ");
        h.append(esc(String.join(", ", r.keyFields()))).append(" when every item has one; otherwise by position.</footer>\n");
        h.append("<script>\n").append(JS).append("</script>\n</body>\n</html>\n");
        return h.toString();
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return java.util.Objects.equals(a, b);
    }

    private static String label(String status) {
        return switch (status) {
            case "ADDED" -> "+ Added";
            case "REMOVED" -> "&minus; Removed";
            default -> "&ne; Changed";
        };
    }

    private static void card(StringBuilder h, String big, String small, String cls) {
        h.append("<div class=\"card ").append(cls).append("\"><div class=\"big\">").append(esc(big)).append("</div><div class=\"small\">").append(esc(small)).append("</div></div>");
    }

    private static String cell(Object v) {
        if (v == JsonCompare.MISSING) {
            return "<span class=\"muted\">&mdash; missing</span>";
        }
        String full = v instanceof String s ? s : v instanceof BigDecimal b ? b.toPlainString() : v instanceof java.util.Map<?, ?> || v instanceof List<?> ? emptyContainer(v) : String.valueOf(v);
        String shown = full.length() > SHOW ? full.substring(0, SHOW) + "\u2026" : full;
        String type = v instanceof String ? "" : " <span class=\"t\">" + (v == null ? "null" : v instanceof Boolean ? "bool" : v instanceof Number ? "num" : "") + "</span>";
        if (v == null) {
            return "<em>null</em>";
        }
        return "<span" + (full.length() > SHOW ? " title=\"" + esc(full) + "\"" : "") + ">" + (full.isEmpty() ? "<em>(empty text)</em>" : esc(shown)) + "</span>"
                + (v instanceof Boolean || v instanceof Number ? type : "");
    }

    private static String emptyContainer(Object v) {
        return v instanceof java.util.Map<?, ?> ? "{}" : "[]";
    }

    /** The last two path segments (folder/file) so that files with the same name in different folders can be told apart. */
    static String shortName(String name) {
        String n = name.replace('\\', '/');
        int last = n.lastIndexOf('/');
        int prev = last > 0 ? n.lastIndexOf('/', last - 1) : -1;
        String tail = prev >= 0 ? n.substring(prev + 1) : n;
        return tail.length() > 40 ? "\u2026" + tail.substring(tail.length() - 39) : tail;
    }

    private static String size(long b) {
        return b < 1024 ? b + " B" : b < 1024 * 1024 ? String.format("%.1f KB", b / 1024.0) : String.format("%.1f MB", b / 1048576.0);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** The same comparison as JSON, for scripts. */
    public static String toJson(Report r) {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("baseline", r.sources().get(0).name());
        if (r.section() != null && !r.section().isBlank()) {
            root.put("section", r.section());
        }
        root.put("totalPaths", (long) r.totalPaths());
        root.put("identical", (long) r.identical());
        root.put("differing", (long) r.differing());
        List<Object> files = new java.util.ArrayList<>();
        for (FileSummary f : r.files()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", f.name());
            m.put("bytes", f.bytes());
            m.put("values", (long) f.values());
            m.put("added", (long) f.added());
            m.put("removed", (long) f.removed());
            m.put("changed", (long) f.changed());
            m.put("same", (long) f.same());
            files.add(m);
        }
        root.put("files", files);
        List<Object> rows = new java.util.ArrayList<>();
        for (Row row : r.rows()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("path", row.path());
            m.put("status", row.status());
            List<Object> vals = new java.util.ArrayList<>();
            for (Object v : row.values()) {
                if (v == JsonCompare.MISSING) {
                    java.util.Map<String, Object> miss = new java.util.LinkedHashMap<>();
                    miss.put("missing", Boolean.TRUE);
                    vals.add(miss);
                } else {
                    vals.add(v);
                }
            }
            m.put("values", vals);
            rows.add(m);
        }
        root.put("differences", rows);
        root.put("truncated", r.truncated());
        return Json.write(root) + "\n";
    }

    private static final String CSS = """
            :root{--bg:#f7f8fa;--panel:#fff;--text:#1d2330;--muted:#667085;--line:#e3e6ec;--accent:#2f5fd0;--add:#117a3d;--addbg:#e3f6ea;--rem:#b3261e;--rembg:#fde8e6;--chg:#9a5b00;--chgbg:#fff1d6;--ok:#117a3d;--bad:#b3261e}
            @media (prefers-color-scheme:dark){:root{--bg:#10131a;--panel:#171b24;--text:#e6e9ef;--muted:#98a2b3;--line:#2a3140;--accent:#7aa2ff;--add:#5fd38d;--addbg:#12301f;--rem:#ff8a80;--rembg:#3a1a18;--chg:#ffc266;--chgbg:#38290d;--ok:#5fd38d;--bad:#ff8a80}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
            header{padding:28px 32px 12px}h1{margin:0 0 4px;font-size:24px}h2{font-size:17px;margin:0 0 12px}.sub{margin:0;color:var(--muted)}
            main{padding:0 32px 32px;display:grid;gap:20px}section{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px 20px}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px}.card{border:1px solid var(--line);border-radius:10px;padding:12px 14px}
            .card .big{font-size:26px;font-weight:650}.card .small{color:var(--muted);font-size:12px}.card.ok .big{color:var(--ok)}.card.bad .big{color:var(--bad)}
            .scroll{overflow-x:auto}.scroll.tall{max-height:70vh;overflow:auto}table{border-collapse:collapse;width:100%}
            th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}th{font-size:12px;text-transform:uppercase;letter-spacing:.03em;color:var(--muted);background:var(--panel);position:sticky;top:0;z-index:1}
            td.v{max-width:340px;word-break:break-word}td.v.diff{background:var(--chgbg)}td.v.miss{background:var(--rembg)}td.path{min-width:220px;max-width:420px;word-break:break-all}
            code{font:12px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}.muted{color:var(--muted)}.fn{font-weight:400;text-transform:none;letter-spacing:0}
            .add{color:var(--add)}.rem{color:var(--rem)}.chg{color:var(--chg)}.tag{background:var(--accent);color:#fff;border-radius:999px;padding:1px 8px;font-size:11px}
            .badge{display:inline-block;border-radius:999px;padding:1px 9px;font-size:12px;font-weight:600;white-space:nowrap}
            .badge.ADDED{background:var(--addbg);color:var(--add)}.badge.REMOVED{background:var(--rembg);color:var(--rem)}.badge.CHANGED{background:var(--chgbg);color:var(--chg)}
            .bar{display:inline-block;width:110px;height:8px;background:var(--line);border-radius:6px;overflow:hidden;vertical-align:middle;margin-right:8px}.bar span{display:block;height:100%;background:var(--add)}
            .toolbar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px}.f{border:1px solid var(--line);background:var(--panel);color:var(--text);border-radius:999px;padding:5px 14px;cursor:pointer;font:inherit}
            .f.on{background:var(--accent);border-color:var(--accent);color:#fff}input[type=search]{flex:1;min-width:200px;border:1px solid var(--line);border-radius:8px;padding:6px 10px;background:var(--panel);color:var(--text);font:inherit}
            .note{border-radius:8px;padding:8px 12px;margin:10px 0}.okbox{background:var(--addbg);color:var(--add)}.warnbox{background:var(--chgbg);color:var(--chg)}
            .legend{color:var(--muted);font-size:12px;margin:10px 0 0}.t{color:var(--muted);font-size:11px}footer{padding:0 32px 32px;color:var(--muted);font-size:12px}
            @media print{body{background:#fff;color:#000}.toolbar{display:none}.scroll.tall{max-height:none}section{break-inside:avoid-page}}
            """;

    private static final String JS = """
            (function(){var rows=[].slice.call(document.querySelectorAll('#diff tbody tr'));if(!rows.length)return;
            var f='all',q='',btns=[].slice.call(document.querySelectorAll('.f')),box=document.getElementById('q'),cnt=document.getElementById('count');
            function apply(){var n=0;rows.forEach(function(r){var ok=(f==='all'||r.getAttribute('data-s')===f)&&(!q||r.textContent.toLowerCase().indexOf(q)>-1);r.style.display=ok?'':'none';if(ok)n++;});
            cnt.textContent=n+' of '+rows.length+' rows shown';}
            btns.forEach(function(b){b.addEventListener('click',function(){f=b.getAttribute('data-f');btns.forEach(function(x){x.classList.toggle('on',x===b);});apply();});});
            box.addEventListener('input',function(){q=box.value.toLowerCase();apply();});apply();})();
            """;
}
