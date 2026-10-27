package com.example.casc.ingest;

import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything extracted from the sources, with provenance, before any manifest is generated.
 * Row values from data dumps are never kept; only column names, inferred types and counts.
 * Nothing here is serialized into a manifest directly.
 */
public record RawMetadataBundle(List<SourceInfo> sources, List<RawTable> tables, List<RawTerm> terms,
                                List<RawNote> notes, List<LineageHint> lineage, List<Finding> findings) {

    /** status: INGESTED, UNAVAILABLE or REJECTED. */
    public record SourceInfo(String name, String format, String status, String detail) {}

    public record RawColumn(String name, String dataType, boolean typeInferred, Boolean nullable,
                            boolean primaryKey, String description, String location) {}

    /** kind: TABLE or VIEW. database and schema may be null. */
    public record RawTable(String database, String schema, String name, String kind, String description,
                           List<RawColumn> columns, String source, String location) {}

    /** A candidate logical term or field definition that is not tied to a table. Needs review. */
    public record RawTerm(String name, String description, String source, String location) {}

    /** kind: HEADING or LINEAGE_TEXT. Text only; no structure is inferred. */
    public record RawNote(String kind, String text, String source, String location) {}

    /** Simple single-table view lineage. A hint for review, never serialized into a manifest. */
    public record LineageHint(String target, String source, String origin, String location) {}

    public static RawMetadataBundle merge(List<RawMetadataBundle> parts) {
        Builder b = new Builder();
        for (RawMetadataBundle p : parts) {
            b.sources.addAll(p.sources);
            b.tables.addAll(p.tables);
            b.terms.addAll(p.terms);
            b.notes.addAll(p.notes);
            b.lineage.addAll(p.lineage);
            b.findings.addAll(p.findings);
        }
        return b.build();
    }

    public Map<String, Object> toTree() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("sources", map(sources, s -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", s.name());
            o.put("format", s.format());
            o.put("status", s.status());
            o.put("detail", s.detail());
            return o;
        }));
        t.put("tables", map(tables, x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            put(o, "database", x.database());
            put(o, "schema", x.schema());
            o.put("name", x.name());
            o.put("kind", x.kind());
            put(o, "description", x.description());
            o.put("source", x.source());
            o.put("location", x.location());
            o.put("columns", map(x.columns(), c -> {
                Map<String, Object> co = new LinkedHashMap<>();
                co.put("name", c.name());
                put(co, "dataType", c.dataType());
                if (c.dataType() != null) {
                    co.put("typeInferred", c.typeInferred());
                }
                put(co, "nullable", c.nullable());
                co.put("primaryKey", c.primaryKey());
                put(co, "description", c.description());
                co.put("location", c.location());
                return co;
            }));
            return o;
        }));
        t.put("terms", map(terms, x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", x.name());
            put(o, "description", x.description());
            o.put("source", x.source());
            o.put("location", x.location());
            return o;
        }));
        t.put("notes", map(notes, x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("kind", x.kind());
            o.put("text", x.text());
            o.put("source", x.source());
            o.put("location", x.location());
            return o;
        }));
        t.put("lineageHints", map(lineage, x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("target", x.target());
            o.put("source", x.source());
            o.put("origin", x.origin());
            o.put("location", x.location());
            return o;
        }));
        t.put("findings", map(findings, Finding::toTree));
        return t;
    }

    private static void put(Map<String, Object> o, String k, Object v) {
        if (v != null) {
            o.put(k, v);
        }
    }

    private static <T> List<Object> map(List<T> l, java.util.function.Function<T, Object> f) {
        List<Object> out = new ArrayList<>();
        for (T x : l) {
            out.add(f.apply(x));
        }
        return out;
    }

    /** Mutable collector used by the ingestion strategies. */
    public static final class Builder {
        final List<SourceInfo> sources = new ArrayList<>();
        final List<RawTable> tables = new ArrayList<>();
        final List<RawTerm> terms = new ArrayList<>();
        final List<RawNote> notes = new ArrayList<>();
        final List<LineageHint> lineage = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();

        public Builder source(String name, String format, String status, String detail) {
            sources.add(new SourceInfo(name, format, status, detail));
            return this;
        }

        public Builder table(RawTable t) {
            tables.add(t);
            return this;
        }

        public Builder term(RawTerm t) {
            terms.add(t);
            return this;
        }

        public Builder note(String kind, String text, String source, String location) {
            notes.add(new RawNote(kind, text, source, location));
            return this;
        }

        public Builder lineage(LineageHint h) {
            lineage.add(h);
            return this;
        }

        public Builder error(String code, String path, String message, String fix) {
            findings.add(Finding.error(code, path, message, fix));
            return this;
        }

        public Builder warn(String code, String path, String message, String fix) {
            findings.add(Finding.warning(code, path, message, fix));
            return this;
        }

        public Builder info(String code, String path, String message, String fix) {
            findings.add(Finding.info(code, path, message, fix));
            return this;
        }

        public int tableCount() {
            return tables.size();
        }

        public int termCount() {
            return terms.size();
        }

        public RawMetadataBundle build() {
            return new RawMetadataBundle(List.copyOf(sources), List.copyOf(tables), List.copyOf(terms),
                    List.copyOf(notes), List.copyOf(lineage), List.copyOf(findings));
        }
    }
}
