package com.example.casc.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Table names are compared by the bare table name only. A prefix such as a2.dbo. (database.schema.) is ignored, and quoted names
 * ([dbo].[Orders], "dbo"."Orders", `a2`.`Orders`) are reduced the same way, so "a2.dbo.TAPSAccount", "dbo.TAPSAccount" and "TAPSAccount" are one table.
 */
public final class TableNames {
    private TableNames() {}

    public static String bare(String name) {
        if (name == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char close = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (close != 0) {
                cur.append(c);
                if (c == close) {
                    close = 0;
                }
            } else if (c == '"' || c == '`') {
                close = c;
                cur.append(c);
            } else if (c == '[') {
                close = ']';
                cur.append(c);
            } else if (c == '.') {
                parts.add(cur.toString());
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        String last = parts.get(parts.size() - 1).trim();
        if (last.length() >= 2 && (last.startsWith("\"") && last.endsWith("\"") || last.startsWith("`") && last.endsWith("`") || last.startsWith("[") && last.endsWith("]"))) {
            last = last.substring(1, last.length() - 1).trim();
        }
        return last;
    }
}
