package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.LineageHint;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SqlTokenizer.SqlException;
import com.example.casc.ingest.SqlTokenizer.Token;
import com.example.casc.ingest.SqlTokenizer.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Restricted SQL reader for Oracle, DB2, PostgreSQL, Sybase, Snowflake, Teradata and SQL Server DDL.
 * Nothing is ever executed. Supported:
 *   CREATE [modifiers] TABLE name (columns and table constraints)   columns, types, NOT NULL/NULL, PRIMARY KEY, COMMENT
 *   ALTER TABLE name ADD ...                                         added columns and primary keys
 *   COMMENT ON TABLE|VIEW|COLUMN ... IS 'text'
 *   CREATE VIEW name [(columns)] AS SELECT ...                       view name; column names when explicit or simple;
 *                                                                    lineage hints for a simple single-table SELECT
 * Everything else is reported with its line: DML (INSERT, UPDATE, DELETE, MERGE, SELECT) is NOT analysed and no
 * lineage is inferred from it; DROP/RENAME/other ALTER actions are not applied; unknown statements are listed.
 * Other column options (DEFAULT, REFERENCES, CHECK, IDENTITY ...) and table options are not extracted.
 * Identifiers keep the case written in the source. Names with more than three parts are rejected.
 */
public final class SqlStrategy implements SourceIngestionStrategy {
    private static final Set<String> STOP = Set.of("NOT", "NULL", "PRIMARY", "DEFAULT", "REFERENCES", "UNIQUE", "CHECK",
            "CONSTRAINT", "COMMENT", "GENERATED", "IDENTITY", "AUTOINCREMENT", "COLLATE", "ENCODE", "COMPRESS", "FORMAT", "TITLE");
    private static final Set<String> CREATE_MODS = Set.of("OR", "REPLACE", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP", "VOLATILE",
            "SET", "MULTISET", "TRANSIENT", "UNLOGGED", "MATERIALIZED", "SECURE", "RECURSIVE", "FORCE", "NOFORCE");
    private static final Set<String> CONSTRAINT_WORDS = Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK");
    private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "SELECT", "WITH", "CALL", "EXEC",
            "EXECUTE", "UPSERT", "LOAD", "COPY");
    private static final Set<String> STRUCTURE = Set.of("DROP", "TRUNCATE", "RENAME");
    private static final Set<String> HARMLESS = Set.of("GRANT", "REVOKE", "USE", "SET", "BEGIN", "COMMIT", "ROLLBACK",
            "START", "ANALYZE", "COLLECT", "RUNSTATS", "DECLARE", "PRINT", "SAVEPOINT", "LOCK", "END");
    private static final Set<String> ALTER_ACTIONS = Set.of("DROP", "ALTER", "MODIFY", "RENAME", "SET", "CHANGE");
    private static final Set<String> NOT_COLUMNS = Set.of("CASE", "CAST", "NULL", "DISTINCT", "ALL", "EXISTS", "NOT",
            "INTERVAL", "DATE", "TIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "USER", "SYSDATE");
    private static final Set<String> CLAUSES = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "QUALIFY", "FETCH", "WINDOW");
    private static final Set<String> MULTI = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "NATURAL", "LATERAL");

    @Override public String id() { return "sql"; }

    @Override public Set<String> extensions() { return Set.of("sql", "ddl"); }

    // ------------------------------------------------------------------ model

    private static final class Col {
        String name;
        String type;
        String desc;
        Boolean nullable;
        boolean pk;
        int line;
    }

    private static final class Tbl {
        String db;
        String schema;
        String name;
        String kind;
        String desc;
        int line;
        final Map<String, Col> cols = new LinkedHashMap<>();
    }

    private static final class Fail extends Exception {
        private static final long serialVersionUID = 1L;

        Fail(String m) {
            super(m);
        }
    }

    private static final class Ctx {
        final String source;
        final Builder out;
        final Map<String, Tbl> tables = new LinkedHashMap<>();
        int line;
        int unsupported;
        int ignoredConstraints;

        Ctx(String source, Builder out) {
            this.source = source;
            this.out = out;
        }

        String loc() {
            return source + ":line " + line;
        }

        void unsupported(String message, String fix) {
            unsupported++;
            out.warn("SQL_UNSUPPORTED", loc(), message, fix);
        }

        static String key(String schema, String name) {
            return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "|" + name.toLowerCase(Locale.ROOT);
        }

        Tbl find(List<String> parts) throws Fail {
            String[] id = ident(parts);
            Tbl t = tables.get(key(id[1], id[2]));
            if (t != null || id[1] != null) {
                return t;
            }
            Tbl only = null;
            for (Tbl c : tables.values()) {
                if (c.name.equalsIgnoreCase(id[2])) {
                    if (only != null) {
                        throw new Fail("Table name '" + id[2] + "' is ambiguous; qualify it with a schema");
                    }
                    only = c;
                }
            }
            return only;
        }
    }

    /** Returns {database, schema, name}. */
    private static String[] ident(List<String> p) throws Fail {
        if (p.size() > 3) {
            throw new Fail("Names with more than three parts are not supported");
        }
        String db = p.size() == 3 ? p.get(0) : null;
        String schema = p.size() >= 2 ? p.get(p.size() - 2) : null;
        return new String[] {db, schema, p.get(p.size() - 1)};
    }

    private static final class Cur {
        final List<Token> t;
        int p;

        Cur(List<Token> t) {
            this.t = t;
        }

        boolean more() { return p < t.size(); }

        Token peek() { return p < t.size() ? t.get(p) : null; }

        Token at(int k) { return p + k < t.size() ? t.get(p + k) : null; }

        Token next() { return t.get(p++); }

        boolean word(String w) {
            if (more() && t.get(p).isWord(w)) {
                p++;
                return true;
            }
            return false;
        }

        boolean peekWord(String w) { return more() && t.get(p).isWord(w); }

        boolean peekSym(char c) { return more() && t.get(p).isSymbol(c); }

        List<Token> rest() {
            List<Token> r = new ArrayList<>(t.subList(p, t.size()));
            p = t.size();
            return r;
        }

        List<String> name() throws Fail {
            if (!more() || !peek().isName()) {
                throw new Fail("Expected a name");
            }
            List<String> parts = new ArrayList<>();
            parts.add(next().text());
            while (peekSym('.') && at(1) != null && at(1).isName()) {
                next();
                parts.add(next().text());
            }
            return parts;
        }

        /** Current token must be '('; returns the tokens inside and moves past the matching ')'. */
        List<Token> group() throws Fail {
            if (!peekSym('(')) {
                throw new Fail("Expected '('");
            }
            int depth = 0;
            int start = p + 1;
            for (int i = p; i < t.size(); i++) {
                if (t.get(i).isSymbol('(')) {
                    depth++;
                } else if (t.get(i).isSymbol(')')) {
                    depth--;
                    if (depth == 0) {
                        p = i + 1;
                        return new ArrayList<>(t.subList(start, i));
                    }
                }
            }
            throw new Fail("Unbalanced parentheses");
        }
    }

    private static List<List<Token>> split(List<Token> toks) {
        List<List<Token>> out = new ArrayList<>();
        List<Token> cur = new ArrayList<>();
        int depth = 0;
        for (Token t : toks) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            }
            if (depth == 0 && t.isSymbol(',')) {
                out.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty() || !out.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static String up(Token t) {
        return t.text().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ entry point

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String dialect = o.dialect();
        List<Token> tokens;
        try {
            tokens = SqlTokenizer.tokenize(preprocess(in.text(), dialect), dialect);
        } catch (SqlException e) {
            b.error("SQL_SYNTAX", in.name() + ":line " + e.line, e.getMessage(),
                    "Fix the SQL text at that line; no statement from this file was analysed.");
            b.source(in.name(), "sql", "REJECTED", e.getMessage());
            return b.build();
        }
        Ctx x = new Ctx(in.name(), b);
        List<List<Token>> statements = statements(tokens);
        for (List<Token> st : statements) {
            x.line = st.get(0).line();
            try {
                statement(new Cur(st), x);
            } catch (Fail f) {
                b.warn("SQL_PARSE_FAILED", x.loc(), f.getMessage() + "; this statement was not applied.",
                        "Check the statement against the supported subset in the documentation.");
            }
        }
        if (x.ignoredConstraints > 0) {
            b.info("SQL_CONSTRAINT_IGNORED", in.name(), x.ignoredConstraints
                    + " UNIQUE, FOREIGN KEY or CHECK constraint(s) were not extracted.", "Only PRIMARY KEY is extracted.");
        }
        int views = 0;
        for (Tbl t : x.tables.values()) {
            List<RawColumn> cols = new ArrayList<>();
            for (Col c : t.cols.values()) {
                cols.add(new RawColumn(c.name, c.type, false, c.nullable, c.pk, c.desc, "line " + c.line));
            }
            b.table(new RawTable(t.db, t.schema, t.name, t.kind, t.desc, List.copyOf(cols), in.name(), "line " + t.line));
            if (t.kind.equals("VIEW")) {
                views++;
            }
        }
        if (b.tableCount() == 0) {
            b.warn("SQL_NOTHING_EXTRACTED", in.name(), "No CREATE TABLE or CREATE VIEW statements were found.",
                    "Provide DDL, or a data dictionary as CSV.");
        }
        b.source(in.name(), "sql", "INGESTED", "dialect " + dialect + ": " + statements.size() + " statement(s), "
                + (b.tableCount() - views) + " table(s), " + views + " view(s), " + x.unsupported + " unsupported");
        return b.build();
    }

    static String preprocess(String text, String dialect) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean go = dialect.equals("auto") || dialect.equals("sqlserver") || dialect.equals("sybase");
        boolean slash = dialect.equals("auto") || dialect.equals("oracle");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if ((go && t.equalsIgnoreCase("GO")) || (slash && t.equals("/"))) {
                lines[i] = ";";
            }
        }
        return String.join("\n", lines);
    }

    private static List<List<Token>> statements(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        List<Token> cur = new ArrayList<>();
        int depth = 0;
        for (Token t : tokens) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && t.isSymbol(';')) {
                if (!cur.isEmpty()) {
                    out.add(cur);
                }
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    // ------------------------------------------------------------------ statements

    private void statement(Cur c, Ctx x) throws Fail {
        Token first = c.peek();
        if (first.type() != Type.WORD) {
            x.unsupported("Statement does not start with a keyword and was not analysed.", "Remove it or provide DDL.");
            return;
        }
        String w = up(first);
        if (w.equals("CREATE")) {
            c.next();
            create(c, x);
        } else if (w.equals("ALTER")) {
            c.next();
            alter(c, x);
        } else if (w.equals("COMMENT")) {
            comment(c, x);
        } else if (DML.contains(w)) {
            x.unsupported(w + " statements are not analysed in this release; no lineage is inferred from them.",
                    "Document lineage separately; analysing DML needs an approved SQL parser.");
        } else if (STRUCTURE.contains(w)) {
            x.unsupported(w + " is not applied; the structure shown may differ from the final database.",
                    "Review the script order or supply the final DDL.");
        } else if (HARMLESS.contains(w)) {
            x.out.info("SQL_SKIPPED", x.loc(), w + " statement skipped (does not define structure).", "None needed.");
        } else {
            x.unsupported("Statement type " + w + " is not recognised and was not analysed.", "Provide DDL in the supported subset.");
        }
    }

    private void create(Cur c, Ctx x) throws Fail {
        while (c.more() && c.peek().type() == Type.WORD && CREATE_MODS.contains(up(c.peek()))) {
            c.next();
        }
        if (c.peekWord("EXTERNAL")) {
            x.unsupported("External tables are not analysed.", "Provide the table definition as a normal CREATE TABLE.");
        } else if (c.word("TABLE")) {
            createTable(c, x);
        } else if (c.word("VIEW")) {
            createView(c, x);
        } else {
            String what = c.more() ? up(c.peek()) : "(nothing)";
            x.out.info("SQL_SKIPPED", x.loc(), "CREATE " + what + " is not analysed (does not define tables or views).", "None needed.");
        }
    }

    private void ifNotExists(Cur c) {
        if (c.peekWord("IF")) {
            c.next();
            c.word("NOT");
            c.word("EXISTS");
        }
    }

    private Tbl newTable(List<String> parts, String kind, int line) throws Fail {
        String[] id = ident(parts);
        Tbl t = new Tbl();
        t.db = id[0];
        t.schema = id[1];
        t.name = id[2];
        t.kind = kind;
        t.line = line;
        return t;
    }

    private void register(Ctx x, Tbl t) {
        String key = Ctx.key(t.schema, t.name);
        if (x.tables.containsKey(key)) {
            x.out.warn("SQL_DUPLICATE_TABLE", x.loc(), "'" + t.name + "' is defined more than once; the later definition replaces the earlier one.",
                    "Remove the duplicate definition.");
        }
        x.tables.put(key, t);
    }

    private void createTable(Cur c, Ctx x) throws Fail {
        ifNotExists(c);
        Tbl t = newTable(c.name(), "TABLE", x.line);
        if (!c.more()) {
            throw new Fail("Expected a column list after the table name");
        }
        if (c.peekSym(',')) {
            while (c.more() && !c.peekSym('(')) {
                c.next();   // Teradata style table options before the column list, e.g. ", NO FALLBACK"
            }
        }
        if (!c.peekSym('(')) {
            register(x, t);
            x.unsupported("CREATE TABLE ... AS SELECT / LIKE is not analysed; table '" + t.name + "' was recorded without columns.",
                    "Provide the column definitions or a data dictionary.");
            return;
        }
        List<List<Token>> elements = split(c.group());
        elements(elements, t, x);
        if (c.more()) {
            x.out.info("SQL_TABLE_OPTIONS_IGNORED", x.loc(), "Options after the column list of '" + t.name + "' were not analysed.", "None needed.");
        }
        register(x, t);
    }

    /** Columns first, then constraints, so a PRIMARY KEY listed early still finds its columns. */
    private void elements(List<List<Token>> elems, Tbl t, Ctx x) throws Fail {
        List<List<Token>> constraints = new ArrayList<>();
        for (List<Token> e : elems) {
            if (e.isEmpty()) {
                throw new Fail("Empty element in a column list");
            }
            Token f = e.get(0);
            if (f.type() == Type.WORD && CONSTRAINT_WORDS.contains(up(f))) {
                constraints.add(e);
            } else {
                column(e, t, x);
            }
        }
        for (List<Token> e : constraints) {
            List<Token> k = e;
            if (up(k.get(0)).equals("CONSTRAINT") && k.size() > 2) {
                k = k.subList(2, k.size());
            }
            if (k.size() > 1 && up(k.get(0)).equals("PRIMARY") && k.get(1).isWord("KEY")) {
                Cur kc = new Cur(new ArrayList<>(k.subList(2, k.size())));
                for (List<Token> part : split(kc.group())) {
                    Col col = part.isEmpty() ? null : t.cols.get(part.get(0).text().toLowerCase(Locale.ROOT));
                    if (col == null) {
                        x.out.warn("SQL_UNKNOWN_TARGET", x.loc(), "PRIMARY KEY names a column that is not defined on '" + t.name + "'.",
                                "Check the column name.");
                    } else {
                        col.pk = true;
                    }
                }
            } else {
                x.ignoredConstraints++;
            }
        }
    }

    private void column(List<Token> e, Tbl t, Ctx x) throws Fail {
        Token nameTok = e.get(0);
        if (!nameTok.isName()) {
            throw new Fail("Expected a column name");
        }
        Col col = new Col();
        col.name = nameTok.text();
        col.line = nameTok.line();
        int i = 1;
        int depth = 0;
        StringBuilder type = new StringBuilder();
        Token prev = null;
        while (i < e.size()) {
            Token k = e.get(i);
            if (depth == 0 && k.type() == Type.WORD && STOP.contains(up(k))) {
                break;
            }
            if (k.isSymbol('(')) {
                depth++;
            } else if (k.isSymbol(')')) {
                depth--;
            }
            boolean punctuation = k.type() == Type.SYMBOL;
            boolean prevOpen = prev != null && (prev.isSymbol('(') || prev.isSymbol(',') || prev.isSymbol('.'));
            if (type.length() > 0 && !punctuation && !prevOpen) {
                type.append(' ');
            }
            type.append(k.type() == Type.STRING ? "'" + k.text() + "'" : k.text());
            prev = k;
            i++;
        }
        col.type = type.length() == 0 ? null : type.toString();
        depth = 0;
        while (i < e.size()) {
            Token k = e.get(i);
            if (k.isSymbol('(')) {
                depth++;
            } else if (k.isSymbol(')')) {
                depth--;
            }
            if (depth == 0 && k.type() == Type.WORD) {
                String w = up(k);
                Token n1 = i + 1 < e.size() ? e.get(i + 1) : null;
                if (w.equals("NOT") && n1 != null && n1.isWord("NULL")) {
                    col.nullable = Boolean.FALSE;
                    i += 2;
                    continue;
                }
                if (w.equals("NULL")) {
                    col.nullable = Boolean.TRUE;
                    i++;
                    continue;
                }
                if (w.equals("PRIMARY") && n1 != null && n1.isWord("KEY")) {
                    col.pk = true;
                    i += 2;
                    continue;
                }
                if (w.equals("COMMENT")) {
                    i++;
                    if (i < e.size() && e.get(i).isSymbol('=')) {
                        i++;
                    }
                    if (i < e.size() && e.get(i).type() == Type.STRING) {
                        col.desc = e.get(i).text();
                        i++;
                    }
                    continue;
                }
                if (w.equals("DEFAULT")) {
                    i++;
                    if (i < e.size() && e.get(i).isWord("NULL")) {
                        i++;
                    }
                    continue;
                }
            }
            i++;
        }
        if (t.cols.containsKey(col.name.toLowerCase(Locale.ROOT))) {
            x.out.warn("SQL_DUPLICATE_COLUMN", x.source + ":line " + col.line,
                    "Column '" + col.name + "' is defined more than once on '" + t.name + "'; the first definition was kept.",
                    "Remove the duplicate.");
            return;
        }
        t.cols.put(col.name.toLowerCase(Locale.ROOT), col);
    }

    private void alter(Cur c, Ctx x) throws Fail {
        if (!c.word("TABLE")) {
            x.out.info("SQL_SKIPPED", x.loc(), "ALTER statement skipped (only ALTER TABLE is analysed).", "None needed.");
            return;
        }
        if (c.peekWord("IF")) {
            c.next();
            c.word("EXISTS");
        }
        c.word("ONLY");
        List<String> parts = c.name();
        List<Token> rest = c.rest();
        if (rest.isEmpty()) {
            throw new Fail("Expected an ALTER TABLE action");
        }
        if (!rest.get(0).isWord("ADD")) {
            x.unsupported("ALTER TABLE " + up(rest.get(0)) + " is not applied; the structure shown may be outdated.",
                    "Supply the final DDL, or review the script order.");
            return;
        }
        Tbl t = x.find(parts);
        if (t == null) {
            x.out.warn("SQL_UNKNOWN_TARGET", x.loc(), "ALTER TABLE refers to a table that is not defined in the supplied sources; nothing was recorded.",
                    "Add the source that creates the table.");
            return;
        }
        List<Token> after = rest.subList(1, rest.size());
        List<List<Token>> elems;
        if (!after.isEmpty() && after.get(0).isSymbol('(') && new Cur(new ArrayList<>(after)).group().size() == after.size() - 2) {
            elems = split(new ArrayList<>(after.subList(1, after.size() - 1)));
        } else {
            elems = new ArrayList<>();
            for (List<Token> part : split(new ArrayList<>(after))) {
                List<Token> p = new ArrayList<>(part);
                if (!p.isEmpty() && p.get(0).isWord("ADD")) {
                    p.remove(0);
                }
                if (!p.isEmpty() && p.get(0).isWord("COLUMN")) {
                    p.remove(0);
                }
                if (p.size() > 2 && p.get(0).isWord("IF") && p.get(1).isWord("NOT") && p.get(2).isWord("EXISTS")) {
                    p = new ArrayList<>(p.subList(3, p.size()));
                }
                if (!p.isEmpty() && p.get(0).type() == Type.WORD && ALTER_ACTIONS.contains(up(p.get(0)))) {
                    x.unsupported("ALTER TABLE " + up(p.get(0)) + " is not applied; the structure shown may be outdated.",
                            "Supply the final DDL, or review the script order.");
                    continue;
                }
                elems.add(p);
            }
        }
        elements(elems, t, x);
    }

    private void comment(Cur c, Ctx x) throws Fail {
        c.next();
        if (!c.word("ON") || !c.more()) {
            x.out.info("SQL_SKIPPED", x.loc(), "COMMENT statement skipped.", "None needed.");
            return;
        }
        String kind = up(c.next());
        if (kind.equals("MATERIALIZED") && c.peekWord("VIEW")) {
            c.next();
            kind = "VIEW";
        }
        if (!kind.equals("TABLE") && !kind.equals("VIEW") && !kind.equals("COLUMN")) {
            x.out.info("SQL_SKIPPED", x.loc(), "COMMENT ON " + kind + " is not analysed.", "None needed.");
            return;
        }
        List<String> parts = c.name();
        if (!c.word("IS")) {
            c.word("AS");
        }
        String text;
        if (c.word("NULL")) {
            text = null;
        } else if (c.more() && c.peek().type() == Type.STRING) {
            text = c.next().text();
        } else {
            throw new Fail("Expected a quoted comment text");
        }
        if (kind.equals("COLUMN")) {
            if (parts.size() < 2) {
                throw new Fail("COMMENT ON COLUMN needs table.column");
            }
            Tbl t = x.find(parts.subList(0, parts.size() - 1));
            Col col = t == null ? null : t.cols.get(parts.get(parts.size() - 1).toLowerCase(Locale.ROOT));
            if (col == null) {
                x.out.warn("SQL_UNKNOWN_TARGET", x.loc(), "COMMENT ON COLUMN refers to a column that is not defined in the supplied sources.",
                        "Add the source that defines the column.");
            } else {
                col.desc = text;
            }
        } else {
            Tbl t = x.find(parts);
            if (t == null) {
                x.out.warn("SQL_UNKNOWN_TARGET", x.loc(), "COMMENT ON " + kind + " refers to an object that is not defined in the supplied sources.",
                        "Add the source that defines it.");
            } else {
                t.desc = text;
            }
        }
    }

    // ------------------------------------------------------------------ views

    private record Item(String qualifier, String column, String outName) {}

    private void createView(Cur c, Ctx x) throws Fail {
        ifNotExists(c);
        List<String> parts = c.name();
        Tbl v = newTable(parts, "VIEW", x.line);
        List<String> explicit = null;
        if (c.peekSym('(')) {
            explicit = new ArrayList<>();
            for (List<Token> part : split(c.group())) {
                if (part.size() != 1 || !part.get(0).isName()) {
                    throw new Fail("Expected plain column names in the view column list");
                }
                explicit.add(part.get(0).text());
            }
        }
        while (c.more() && !c.peekWord("AS")) {
            c.next();
        }
        if (!c.word("AS")) {
            throw new Fail("Expected AS in CREATE VIEW");
        }
        List<Token> sel = c.rest();
        List<Item> items = null;
        List<String> from = null;
        String alias = null;
        boolean single = false;
        int[] bounds = selectBounds(sel);
        if (bounds != null) {
            items = items(sel.subList(bounds[0], bounds[1]));
            Cur f = new Cur(new ArrayList<>(sel.subList(bounds[1] + 1, sel.size())));
            if (f.more() && f.peek().isName() && !f.peekSym('(')) {
                from = f.name();
                if (f.word("AS")) {
                    alias = f.more() && f.peek().isName() ? f.next().text() : null;
                } else if (f.more() && f.peek().isName() && !(f.peek().type() == Type.WORD
                        && (CLAUSES.contains(up(f.peek())) || MULTI.contains(up(f.peek()))))) {
                    alias = f.next().text();
                }
                single = true;
                for (Token t : f.rest()) {
                    if (t.isSymbol(',') || (t.type() == Type.WORD && MULTI.contains(up(t)))) {
                        single = false;
                    }
                }
            }
            for (Token t : sel.subList(bounds[1] + 1, sel.size())) {
                if (t.type() == Type.WORD && (MULTI.contains(up(t)))) {
                    single = false;
                }
            }
        }
        List<String> names = new ArrayList<>();
        if (explicit != null) {
            names = explicit;
        } else if (items != null) {
            for (Item i : items) {
                names.add(i.outName());
            }
        }
        if (names.isEmpty()) {
            x.out.warn("SQL_VIEW_COLUMNS_UNKNOWN", x.loc(), "Columns of view '" + v.name + "' could not be determined (complex or unsupported SELECT).",
                    "Give the view an explicit column list, or supply a data dictionary.");
        }
        for (String n : names) {
            Col col = new Col();
            col.name = n;
            col.line = x.line;
            v.cols.putIfAbsent(n.toLowerCase(Locale.ROOT), col);
        }
        boolean hints = items != null && single && from != null && (explicit == null || explicit.size() == items.size());
        if (hints) {
            String table = String.join(".", from);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                String q = it.qualifier();
                if (q != null && !q.equalsIgnoreCase(alias == null ? "" : alias) && !q.equalsIgnoreCase(from.get(from.size() - 1))) {
                    hints = false;
                    break;
                }
            }
            if (hints) {
                for (int i = 0; i < items.size(); i++) {
                    String target = (explicit != null ? explicit.get(i) : items.get(i).outName());
                    x.out.lineage(new LineageHint(v.name + "." + target, table + "." + items.get(i).column(),
                            x.source, "line " + x.line));
                }
            }
        }
        if (!hints) {
            x.out.info("SQL_LINEAGE_NOT_INFERRED", x.loc(), "No lineage was inferred for view '" + v.name
                    + "' (joins, expressions, set operations or unsupported SELECT form).", "Document lineage separately.");
        }
        register(x, v);
    }

    /** Returns {firstItemIndex, fromIndex} for "SELECT [DISTINCT|ALL] items FROM ..." or null. */
    private static int[] selectBounds(List<Token> sel) {
        if (sel.isEmpty() || !sel.get(0).isWord("SELECT")) {
            return null;
        }
        int start = 1;
        if (start < sel.size() && (sel.get(start).isWord("DISTINCT") || sel.get(start).isWord("ALL"))) {
            start++;
        }
        int depth = 0;
        for (int i = start; i < sel.size(); i++) {
            Token t = sel.get(i);
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && t.isWord("FROM")) {
                return i > start ? new int[] {start, i} : null;
            }
        }
        return null;
    }

    /** Returns the items if every one is a plain [qualifier.]column [[AS] alias]; otherwise null. */
    private static List<Item> items(List<Token> toks) {
        List<Item> out = new ArrayList<>();
        for (List<Token> part : split(new ArrayList<>(toks))) {
            int n = part.size();
            if (n == 0 || !part.get(0).isName()) {
                return null;
            }
            if (part.get(0).type() == Type.WORD && NOT_COLUMNS.contains(up(part.get(0)))) {
                return null;
            }
            int i = 0;
            String qualifier = null;
            String column = part.get(i++).text();
            if (i + 1 < n && part.get(i).isSymbol('.') && part.get(i + 1).isName()) {
                qualifier = column;
                column = part.get(i + 1).text();
                i += 2;
            }
            String out_ = column;
            if (i < n && part.get(i).isWord("AS")) {
                i++;
            }
            if (i < n) {
                if (!part.get(i).isName() || i != n - 1) {
                    return null;
                }
                out_ = part.get(i).text();
            }
            out.add(new Item(qualifier, column, out_));
        }
        return out.isEmpty() ? null : out;
    }
}
