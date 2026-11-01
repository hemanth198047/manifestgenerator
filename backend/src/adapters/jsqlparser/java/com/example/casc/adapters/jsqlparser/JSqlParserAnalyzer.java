package com.example.casc.adapters.jsqlparser;

import com.example.casc.ingest.RawMetadataBundle.LineageHint;
import com.example.casc.ingest.SqlDmlAnalyzer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Table-level lineage for INSERT, UPDATE, MERGE and CREATE TABLE AS statements, using JSqlParser (an optional adapter).
 * The target is the table being written; the sources are every other table the statement reads. Column-level lineage is NOT
 * inferred, other statements are not handled (null is returned and the built-in reader reports them), and nothing is executed.
 * It uses only the stable core of the JSqlParser API (parse, the statement types above, Table and TablesNamesFinder) so that it
 * works across the 4.x and 5.x lines; TablesNamesFinder is called by reflection because its method name differs between them.
 * JSqlParser has little dialect support, so statements it cannot parse come back as null.
 */
public final class JSqlParserAnalyzer implements SqlDmlAnalyzer {

    @Override public String id() { return "jsqlparser"; }

    @Override
    public List<LineageHint> analyze(String statementSql, String dialect, String source, String location) {
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(statementSql);
        } catch (Exception e) {
            return null;                          // not parseable by JSqlParser; the message is not reused because it can quote SQL text
        }
        Table target;
        if (st instanceof Insert insert) {
            target = insert.getTable();
        } else if (st instanceof Update update) {
            target = update.getTable();
        } else if (st instanceof Merge merge) {
            target = merge.getTable();
        } else if (st instanceof CreateTable create) {
            target = create.getTable();
        } else {
            return null;
        }
        if (target == null) {
            return null;
        }
        String targetName = clean(target.getFullyQualifiedName());
        Set<String> sources = new LinkedHashSet<>();
        for (String name : tableNames(st)) {
            String n = clean(name);
            if (!n.equalsIgnoreCase(targetName) && !n.isEmpty()) {
                sources.add(n);
            }
        }
        List<LineageHint> hints = new ArrayList<>();
        for (String s : sources) {
            hints.add(new LineageHint(targetName, s, source, location + ", table level"));
        }
        return hints;
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace("\"", "").replace("`", "").replace("[", "").replace("]", "").trim();
    }

    /** getTableList in the 4.x line, getTables in 5.x (getTableList stays for a while there, deprecated). */
    private static Collection<String> tableNames(Statement st) {
        TablesNamesFinder finder = new TablesNamesFinder();
        for (String method : new String[] {"getTableList", "getTables"}) {
            try {
                Method m = TablesNamesFinder.class.getMethod(method, Statement.class);
                Object result = m.invoke(finder, st);
                if (result instanceof Collection<?> c) {
                    List<String> out = new ArrayList<>();
                    for (Object o : c) {
                        out.add(String.valueOf(o));
                    }
                    return out;
                }
            } catch (ReflectiveOperationException e) {
                // try the next name
            }
        }
        return List.of();
    }
}
