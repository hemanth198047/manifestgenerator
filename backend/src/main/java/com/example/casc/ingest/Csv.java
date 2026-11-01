package com.example.casc.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 style reader for CSV, TSV and other single-character delimiters.
 * Supports quoted fields, doubled quotes, delimiters and line breaks (LF, CRLF or CR) inside quotes; such line breaks are stored as LF.
 * Blank lines are skipped. It rejects, rather than guesses at: a quote inside an unquoted field,
 * text after a closing quote, and an unterminated quoted field.
 */
public final class Csv {
    private Csv() {}

    /** Parsed records with the line each one started on. Public so tests and tools in any package or class loader can use it. */
    public record Result(List<List<String>> records, List<Integer> lines, boolean truncated) {}

    public static final class CsvException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int line;

        CsvException(String message, int line) {
            super(message + " at line " + line);
            this.line = line;
        }

        /** Line (1-based) where the problem was found. */
        public int line() {
            return line;
        }
    }

    /** Reads at most maxRecords records; truncated is true if more non-blank records follow. */
    public static Result parse(String text, char delim, int maxRecords) throws CsvException {
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        int n = text.length();
        int i = text.startsWith("\uFEFF") ? 1 : 0;
        int line = 1;
        int recLine = 1;
        boolean atFieldStart = true;
        boolean hasContent = false;
        StringBuilder field = new StringBuilder();
        List<String> cur = new ArrayList<>();
        boolean truncated = false;

        while (i < n) {
            char c = text.charAt(i);
            if (!hasContent && c != '\n' && c != '\r') {
                recLine = line;
            }
            if (atFieldStart && c == '"') {
                int startLine = line;
                i++;
                atFieldStart = false;
                hasContent = true;
                while (true) {
                    if (i >= n) {
                        throw new CsvException("Unterminated quoted field", startLine);
                    }
                    char q = text.charAt(i);
                    if (q == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    if (q == '\r') {
                        // A line break inside a quoted value is stored as LF whether the file used CRLF, CR or LF,
                        // so the same metadata gives the same output on every operating system.
                        if (i + 1 < n && text.charAt(i + 1) == '\n') {
                            i++;
                        }
                        q = '\n';
                    }
                    if (q == '\n') {
                        line++;
                    }
                    field.append(q);
                    i++;
                }
                if (i < n) {
                    char a = text.charAt(i);
                    if (a != delim && a != '\n' && a != '\r') {
                        throw new CsvException("Unexpected character after a closing quote", line);
                    }
                }
                continue;
            }
            if (c == delim) {
                cur.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
                hasContent = true;
                i++;
                continue;
            }
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                if (hasContent) {
                    cur.add(field.toString());
                    records.add(cur);
                    lines.add(recLine);
                    if (records.size() > maxRecords) {
                        records.remove(records.size() - 1);
                        lines.remove(lines.size() - 1);
                        return new Result(records, lines, true);
                    }
                }
                cur = new ArrayList<>();
                field.setLength(0);
                atFieldStart = true;
                hasContent = false;
                line++;
                continue;
            }
            if (c == '"') {
                throw new CsvException("Unexpected quote inside an unquoted field", line);
            }
            field.append(c);
            atFieldStart = false;
            hasContent = true;
            i++;
        }
        if (hasContent) {
            cur.add(field.toString());
            records.add(cur);
            lines.add(recLine);
            if (records.size() > maxRecords) {
                records.remove(records.size() - 1);
                lines.remove(lines.size() - 1);
                truncated = true;
            }
        }
        return new Result(records, lines, truncated);
    }
}
