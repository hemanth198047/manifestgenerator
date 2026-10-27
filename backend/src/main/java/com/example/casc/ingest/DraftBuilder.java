package com.example.casc.ingest;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.CasCManifest.Column;
import com.example.casc.domain.CasCManifest.Database;
import com.example.casc.domain.CasCManifest.Interface;
import com.example.casc.domain.CasCManifest.InterfaceDataElement;
import com.example.casc.domain.CasCManifest.Schema;
import com.example.casc.domain.CasCManifest.Table;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a RawMetadataBundle into an UNREVIEWED draft manifest.
 * Included: databases, schemas, tables and columns; and, only when the user chooses an interface direction,
 * one interface per API operation with its field paths (DISTRIBUTION uses response fields, SOURCING uses request
 * fields; this selection is a starting point for review).
 * Deliberately NOT inferred: logical services, logical data elements (terms stay candidates), data entities (API
 * structures stay candidates), table type, PII, sensitivity attestation, system keys and lineage.
 */
public final class DraftBuilder {
    private DraftBuilder() {}

    public record SystemContext(String databaseLogicalName, DatabaseType databaseType, String defaultSchema,
                                InterfaceType interfaceDirection) {
        public SystemContext(String databaseLogicalName, DatabaseType databaseType, String defaultSchema) {
            this(databaseLogicalName, databaseType, defaultSchema, null);
        }
    }

    /** manifest is null when the draft cannot be built; findings explain why. */
    public record Result(CasCManifest manifest, List<Finding> findings, List<String> unresolved) {}

    public static Result build(RawMetadataBundle bundle, SystemContext ctx) {
        List<Finding> findings = new ArrayList<>();
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
        boolean hasInterfaces = !bundle.interfaces().isEmpty();
        boolean withInterfaces = hasInterfaces && ctx.interfaceDirection() != null;
        if (tables == 0 && !withInterfaces) {
            if (hasInterfaces) {
                findings.add(Finding.error("DRAFT_DIRECTION_REQUIRED", "system.interfaceDirection",
                        "The sources contain interface candidates but no tables. Choose whether the system consumes (SOURCING) "
                                + "or provides (DISTRIBUTION) this data to draft them.", "Provide the interface direction."));
            } else {
                findings.add(Finding.error("DRAFT_NO_TABLES", "sources", "The sources contain no tables or interfaces to build a draft from.",
                        "Add DDL, a data dictionary, a data file or an OpenAPI document."));
            }
        }
        if (tables > 0) {
            if (ctx.databaseLogicalName() == null || ctx.databaseLogicalName().isBlank()) {
                findings.add(Finding.error("DRAFT_DB_NAME_REQUIRED", "system.databaseLogicalName",
                        "The TAI logical database name is required to build a draft.", "Provide the database logical name."));
            }
            if (needsSchema && (ctx.defaultSchema() == null || ctx.defaultSchema().isBlank())) {
                findings.add(Finding.error("DRAFT_SCHEMA_REQUIRED", "system.defaultSchema",
                        "Some tables have no schema (for example CSV data files). A default schema name is required.",
                        "Provide the schema that these tables belong to."));
            }
        }
        if (!findings.isEmpty()) {
            return new Result(null, findings, List.of());
        }
        List<Database> databases = null;
        int views = 0;
        if (tables > 0) {
            Map<String, List<Table>> bySchema = new LinkedHashMap<>();
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
            databases = List.of(new Database(ctx.databaseLogicalName(), ctx.databaseType(), null, schemas));
        } else {
            views = (int) bundle.tables().stream().filter(t -> !t.kind().equals("TABLE")).count();
        }
        List<Interface> interfaces = null;
        if (withInterfaces) {
            String wanted = ctx.interfaceDirection() == InterfaceType.DISTRIBUTION ? "response" : "request";
            interfaces = new ArrayList<>();
            int empty = 0;
            for (RawInterface ri : bundle.interfaces()) {
                List<InterfaceDataElement> els = new ArrayList<>();
                for (RawField f : ri.fields()) {
                    if (wanted.equals(f.role())) {
                        els.add(new InterfaceDataElement(f.path(), null, null, f.description(), null, null, null));
                    }
                }
                if (els.isEmpty()) {
                    empty++;
                }
                interfaces.add(new Interface(ri.name(), ctx.interfaceDirection(), null, null, els.isEmpty() ? null : els));
            }
            if (empty > 0) {
                findings.add(Finding.info("DRAFT_INTERFACE_EMPTY", "sources", empty + " interface(s) have no " + wanted
                        + " fields to list.", "Add their fields by hand if needed."));
            }
        } else if (hasInterfaces) {
            findings.add(Finding.info("DRAFT_INTERFACES_NOT_INCLUDED", "sources", bundle.interfaces().size()
                    + " interface candidate(s) were not included because no interface direction was chosen.",
                    "Choose SOURCING or DISTRIBUTION to draft them."));
        }
        CasCManifest m = new CasCManifest(null, null, interfaces, null, databases, null);
        if (views > 0) {
            findings.add(Finding.info("DRAFT_VIEW_EXCLUDED", "sources", views + " view(s) were not included in the draft.",
                    "Add them by hand if the target specification expects views."));
        }
        if (!bundle.terms().isEmpty()) {
            findings.add(Finding.info("DRAFT_TERMS_NOT_INCLUDED", "sources", bundle.terms().size()
                    + " candidate term(s) were not turned into logical data elements.", "Review and add them deliberately."));
        }
        if (!bundle.structures().isEmpty()) {
            findings.add(Finding.info("DRAFT_STRUCTURES_NOT_INCLUDED", "sources", bundle.structures().size()
                    + " candidate structure(s) were not turned into data entities.", "Review and add them deliberately."));
        }
        List<String> unresolved = List.of("This draft was generated automatically from the sources. Review the system context "
                + "(EON ID, logical services, database type), table types, interface directions, logical data elements, "
                + "sensitivity attestations and PII before using it.");
        return new Result(m, findings, unresolved);
    }
}
