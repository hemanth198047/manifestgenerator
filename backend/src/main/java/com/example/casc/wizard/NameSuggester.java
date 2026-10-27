package com.example.casc.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proposes a readable logical name from a physical name, for example TRADE_QTY -> "Trade Quantity".
 * It only splits words and expands a small fixed list of common abbreviations. The result is a suggestion
 * that the user must review; it is never applied automatically.
 */
public final class NameSuggester {
    private NameSuggester() {}

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("qty", "Quantity"), Map.entry("amt", "Amount"), Map.entry("num", "Number"), Map.entry("nbr", "Number"),
            Map.entry("id", "Identifier"), Map.entry("dt", "Date"), Map.entry("desc", "Description"), Map.entry("cd", "Code"),
            Map.entry("nm", "Name"), Map.entry("addr", "Address"), Map.entry("ccy", "Currency"), Map.entry("px", "Price"),
            Map.entry("ts", "Timestamp"), Map.entry("pct", "Percent"), Map.entry("cnt", "Count"));

    public static String suggest(String physical) {
        if (physical == null || physical.isBlank()) {
            return "";
        }
        String last = physical.substring(physical.lastIndexOf('.') + 1).replace("[]", "");
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            boolean separator = c == '_' || c == '-' || c == ' ';
            boolean camel = i > 0 && Character.isUpperCase(c) && Character.isLowerCase(last.charAt(i - 1));
            if (separator || camel) {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
            }
            if (!separator) {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            words.add(cur.toString());
        }
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT);
            String word = ABBREVIATIONS.getOrDefault(lower, Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.toString();
    }
}
