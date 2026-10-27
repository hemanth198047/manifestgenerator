package com.example.casc.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for the SQL subset. It understands comments, single-quoted strings, double-quoted and backtick
 * identifiers, [bracket] identifiers (SQL Server and Sybase), dollar-quoted strings and numbers.
 * It does not understand Oracle q'[...]' literals or vendor-specific string prefixes.
 */
final class SqlTokenizer {
    private SqlTokenizer() {}

    enum Type { WORD, QIDENT, STRING, NUMBER, SYMBOL }

    record Token(Type type, String text, int line) {
        boolean isWord(String w) {
            return type == Type.WORD && text.equalsIgnoreCase(w);
        }

        boolean isSymbol(char c) {
            return type == Type.SYMBOL && text.length() == 1 && text.charAt(0) == c;
        }

        boolean isName() {
            return type == Type.WORD || type == Type.QIDENT;
        }
    }

    static final class SqlException extends Exception {
        private static final long serialVersionUID = 1L;
        final int line;

        SqlException(String message, int line) {
            super(message);
            this.line = line;
        }
    }

    private static boolean wordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '#' || c == '@';
    }

    private static boolean wordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == '@' || c == '$';
    }

    static List<Token> tokenize(String s, String dialect) throws SqlException {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        int line = 1;
        while (i < n) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : '\0';
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int start = line;
                i += 2;
                while (true) {
                    if (i + 1 >= n) {
                        throw new SqlException("Unterminated block comment", start);
                    }
                    if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    if (s.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                int start = line;
                StringBuilder b = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) {
                        throw new SqlException(c == '\'' ? "Unterminated string literal" : "Unterminated quoted identifier", start);
                    }
                    char q = s.charAt(i);
                    if (q == c) {
                        if (i + 1 < n && s.charAt(i + 1) == c) {
                            b.append(c);
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    if (q == '\n') {
                        line++;
                    }
                    b.append(q);
                    i++;
                }
                out.add(new Token(c == '\'' ? Type.STRING : Type.QIDENT, b.toString(), start));
            } else if (c == '[' && bracketIdentifier(dialect, s, i, out)) {
                int start = line;
                int end = s.indexOf(']', i + 1);
                if (end < 0) {
                    throw new SqlException("Unterminated [bracket] identifier", start);
                }
                out.add(new Token(Type.QIDENT, s.substring(i + 1, end), start));
                i = end + 1;
            } else if (c == '$' && dollarQuote(s, i) > 0) {
                int j = i + 1;
                while (s.charAt(j) != '$') {
                    j++;
                }
                String tag = s.substring(i, j + 1);
                int close = s.indexOf(tag, j + 1);
                if (close < 0) {
                    throw new SqlException("Unterminated dollar-quoted string", line);
                }
                String body = s.substring(j + 1, close);
                out.add(new Token(Type.STRING, body, line));
                for (int k = 0; k < body.length(); k++) {
                    if (body.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = close + tag.length();
            } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(next))) {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    int k = i + 1;
                    if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                        k++;
                    }
                    if (k < n && Character.isDigit(s.charAt(k))) {
                        i = k;
                        while (i < n && Character.isDigit(s.charAt(i))) {
                            i++;
                        }
                    }
                }
                out.add(new Token(Type.NUMBER, s.substring(start, i), line));
            } else if (wordStart(c)) {
                int start = i;
                while (i < n && wordPart(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.WORD, s.substring(start, i), line));
            } else {
                out.add(new Token(Type.SYMBOL, String.valueOf(c), line));
                i++;
            }
        }
        return out;
    }

    /**
     * sqlserver and sybase: always an identifier. auto: an identifier unless the bracket directly follows a name
     * or ')' with no space (as in INT[] or arr[1]). Other dialects: never.
     */
    private static boolean bracketIdentifier(String dialect, String s, int i, List<Token> before) {
        if (dialect.equals("sqlserver") || dialect.equals("sybase")) {
            return true;
        }
        if (!dialect.equals("auto")) {
            return false;
        }
        if (before.isEmpty() || i == 0 || Character.isWhitespace(s.charAt(i - 1))) {
            return true;
        }
        Token last = before.get(before.size() - 1);
        return !(last.isName() || last.isSymbol(')'));
    }

    /** Returns the index of the closing '$' of a $tag$ opener at i, or -1. */
    private static int dollarQuote(String s, int i) {
        int j = i + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        return j < s.length() && s.charAt(j) == '$' ? j : -1;
    }
}
