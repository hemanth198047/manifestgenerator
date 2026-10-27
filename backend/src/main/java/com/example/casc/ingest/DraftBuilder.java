package com.example.casc.ingest;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.CasCManifest.Column;
import com.example.casc.domain.CasCManifest.Database;
import com.example.casc.domain.CasCManifest.Schema;
import com.example.casc.domain.CasCManifest.Table;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a RawMetadataBundle into an UNREVIEWED draft manifest containing databases, schemas, tables and columns only.
 * Deliberately NOT inferred: logical services, logical data elements (terms stay candidates), table type, PII,
 * sensitivity attestation, system keys and lineage. The validator then reports what the user still has to decide.
 */
public final class DraftBuilder {
    private DraftBuilder() {}

    public record SystemContext(String databaseLogicalName, DatabaseType databaseType, String defaultSchema) {}

    /** manifest is null when the draft cannot be built; findings explain why. */
    public record Result(CasCManifest manifest, List<Finding> findings, List<String> unresolved) {}

    public static Result build(RawMetadataBundle bundle, SystemContext ctx) {
        List<Finding> findings = new ArrayList<>();
        if (ctx.databaseLogicalName() == null || ctx.databaseLogicalName().isBlank()) {
            findings.add(Finding.error("DRAFT_DB_NAME_REQUIRED", "system.databaseLogicalName",
                    "The TAI logical database name is required to build a draft.", "Provide the database logical name."));
        }
        int tables = 0;
        boolean needsSchema = false;
        for (RawTable t : bundle.tables()) {
            if (t.kind().equals("TABLE")) {
                tables++;
                if (t.schema() == null) {
                    needsSchema = true;
                }
            }
        }
        if (tables == 0) {
            findings.add(Finding.error("DRAFT_NO_TABLES", "sources", "The sources contain no tables to build a draft from.",
                    "Add DDL, a data dictionary or a data file."));
        }
        if (needsSchema && (ctx.defaultSchema() == null || ctx.defaultSchema().isBlank())) {
            findings.add(Finding.error("DRAFT_SCHEMA_REQUIRED", "system.defaultSchema",
                    "Some tables have no schema (for example CSV data files). A default schema name is required.",
                    "Provide the schema that these tables belong to."));
        }
        if (!findings.isEmpty()) {
            return new Result(null, findings, List.of());
        }
        Map<String, List<Table>> bySchema = new LinkedHashMap<>();
        int views = 0;
        for (RawTable t : bundle.tables()) {
            if (!t.kind().equals("TABLE")) {
                views++;
                continue;
            }
            List<Column> cols = new ArrayList<>();
            for (RawColumn c : t.columns()) {
                cols.add(new Column(c.name(), null, null, c.description(), null, null, null, null));
            }
            String schema = t.schema() != null ? t.schema() : ctx.defaultSchema();
            bySchema.computeIfAbsent(schema, k -> new ArrayList<>())
                    .add(new Table(t.name(), null, null, t.description(), cols));
        }
        List<Schema> schemas = new ArrayList<>();
        for (Map.Entry<String, List<Table>> e : bySchema.entrySet()) {
            schemas.add(new Schema(e.getKey(), null, e.getValue()));
        }
        CasCManifest m = new CasCManifest(null, null, null, null,
                List.of(new Database(ctx.databaseLogicalName(), ctx.databaseType(), null, schemas)), null);
        if (views > 0) {
            findings.add(Finding.info("DRAFT_VIEW_EXCLUDED", "sources", views + " view(s) were not included in the draft.",
                    "Add them by hand if the target specification expects views."));
        }
        if (!bundle.terms().isEmpty()) {
            findings.add(Finding.info("DRAFT_TERMS_NOT_INCLUDED", "sources", bundle.terms().size()
                    + " candidate term(s) were not turned into logical data elements.", "Review and add them deliberately."));
        }
        List<String> unresolved = List.of("This draft was generated automatically from the sources. Review the system context "
                + "(EON ID, logical services, database type), table types, logical data elements, sensitivity attestations "
                + "and PII before using it.");
        return new Result(m, findings, unresolved);
    }
}
