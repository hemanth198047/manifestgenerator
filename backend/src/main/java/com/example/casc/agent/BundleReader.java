package com.example.casc.agent;

import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.LineageHint;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawNote;
import com.example.casc.ingest.RawMetadataBundle.RawStructure;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.RawMetadataBundle.RawTerm;
import com.example.casc.ingest.RawMetadataBundle.SourceInfo;
import com.example.casc.validation.Finding;
import com.example.casc.validation.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads back the bundle that ingest_sources returned (its toTree form). Strict: wrong types and unknown fields are
 * rejected with the path of the problem, so a hand-edited or hallucinated bundle cannot slip through.
 */
final class BundleReader {
    private BundleReader() {}

    private static final Set<String> TOP = Set.of("sources", "tables", "terms", "notes", "lineageHints", "interfaces",
            "structures", "findings", "hasErrors");

    static RawMetadataBundle fromTree(Object tree) {
        Map<String, Object> root = Fields.obj(tree, "parsedMetadata");
        Fields.only(root, "parsedMetadata", TOP);
        RawMetadataBundle.Builder b = new RawMetadataBundle.Builder();
        int i = 0;
        for (Object o : Fields.arr(root.get("sources"), "parsedMetadata.sources")) {
            String p = "parsedMetadata.sources[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "format", "status", "detail"));
            b.source(Fields.req(m, "name", p), Fields.req(m, "format", p), Fields.req(m, "status", p), Fields.opt(m, "detail", p));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("tables"), "parsedMetadata.tables")) {
            String p = "parsedMetadata.tables[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("database", "schema", "name", "kind", "description", "source", "location", "columns"));
            String kind = Fields.req(m, "kind", p);
            if (!kind.equals("TABLE") && !kind.equals("VIEW")) {
                throw new IllegalArgumentException(p + ".kind: expected TABLE or VIEW");
            }
            List<RawColumn> cols = new ArrayList<>();
            int j = 0;
            for (Object co : Fields.arr(m.get("columns"), p + ".columns")) {
                String cp = p + ".columns[" + j++ + "]";
                Map<String, Object> c = Fields.obj(co, cp);
                Fields.only(c, cp, Set.of("name", "dataType", "typeInferred", "nullable", "primaryKey", "description", "location"));
                cols.add(new RawColumn(Fields.req(c, "name", cp), Fields.nullable(c, "dataType", cp), Fields.bool(c, "typeInferred", cp, false),
                        Fields.boolOrNull(c, "nullable", cp), Fields.bool(c, "primaryKey", cp, false), Fields.nullable(c, "description", cp),
                        Fields.opt(c, "location", cp)));
            }
            b.table(new RawTable(Fields.nullable(m, "database", p), Fields.nullable(m, "schema", p), Fields.req(m, "name", p), kind,
                    Fields.nullable(m, "description", p), List.copyOf(cols), Fields.req(m, "source", p), Fields.opt(m, "location", p)));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("terms"), "parsedMetadata.terms")) {
            String p = "parsedMetadata.terms[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "description", "source", "location"));
            b.term(new RawTerm(Fields.req(m, "name", p), Fields.nullable(m, "description", p), Fields.req(m, "source", p), Fields.opt(m, "location", p)));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("notes"), "parsedMetadata.notes")) {
            String p = "parsedMetadata.notes[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("kind", "text", "source", "location"));
            b.note(Fields.req(m, "kind", p), Fields.req(m, "text", p), Fields.req(m, "source", p), Fields.opt(m, "location", p));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("lineageHints"), "parsedMetadata.lineageHints")) {
            String p = "parsedMetadata.lineageHints[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("target", "source", "origin", "location"));
            b.lineage(new LineageHint(Fields.req(m, "target", p), Fields.req(m, "source", p), Fields.req(m, "origin", p), Fields.opt(m, "location", p)));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("interfaces"), "parsedMetadata.interfaces")) {
            String p = "parsedMetadata.interfaces[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "method", "path", "description", "source", "location", "fields"));
            b.iface(new RawInterface(Fields.req(m, "name", p), Fields.nullable(m, "method", p), Fields.nullable(m, "path", p),
                    Fields.nullable(m, "description", p), fields(m.get("fields"), p + ".fields"), Fields.req(m, "source", p), Fields.opt(m, "location", p)));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("structures"), "parsedMetadata.structures")) {
            String p = "parsedMetadata.structures[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "description", "source", "location", "fields"));
            b.structure(new RawStructure(Fields.req(m, "name", p), Fields.nullable(m, "description", p), fields(m.get("fields"), p + ".fields"),
                    Fields.req(m, "source", p), Fields.opt(m, "location", p)));
        }
        i = 0;
        for (Object o : Fields.arr(root.get("findings"), "parsedMetadata.findings")) {
            String p = "parsedMetadata.findings[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("code", "severity", "path", "message", "suggestion"));
            Severity sev;
            try {
                sev = Severity.valueOf(Fields.req(m, "severity", p));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(p + ".severity: expected ERROR, WARNING or INFO");
            }
            b.findingRaw(new Finding(Fields.req(m, "code", p), sev, Fields.opt(m, "path", p), Fields.opt(m, "message", p), Fields.opt(m, "suggestion", p)));
        }
        return b.build();
    }

    private static List<RawField> fields(Object node, String path) {
        List<RawField> out = new ArrayList<>();
        int i = 0;
        for (Object o : Fields.arr(node, path)) {
            String p = path + "[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("path", "dataType", "description", "role"));
            out.add(new RawField(Fields.req(m, "path", p), Fields.nullable(m, "dataType", p), Fields.nullable(m, "description", p), Fields.nullable(m, "role", p)));
        }
        return out;
    }
}
