package com.example.casc.ingest;

import static com.example.casc.TestKit.*;

import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawStructure;
import com.example.casc.manifest.ManifestMapper;
import java.util.List;

public final class OpenApiTests {
    private OpenApiTests() {}

    private static RawMetadataBundle api(String json) {
        return IngestTests.run("api.json", json);
    }

    private static RawInterface iface(RawMetadataBundle b, String name) {
        return b.interfaces().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no interface " + name));
    }

    private static List<String> paths(List<RawField> fields, String role) {
        return fields.stream().filter(f -> role == null || role.equals(f.role())).map(RawField::path).toList();
    }

    public static void register() {
        test("openapi: the sample becomes candidate interfaces and structures, never tables", () -> {
            RawMetadataBundle b = api(resource("/static/samples/sample-openapi.json"));
            eq(0, b.tables().size());
            eq("INGESTED", b.sources().get(0).status());
            contains(b.sources().get(0).detail(), "OpenAPI 3.0.3: 2 operation(s), 2 schema(s)");
            RawInterface list = iface(b, "listTrades");
            eq("GET", list.method());
            eq("/trades", list.path());
            eq("List trades", list.description());
            eq("#/paths/~1trades/get", list.location());
            eq(List.of("tradeId", "quantity", "client.clientId", "client.name", "tags[]"), paths(list.fields(), "response"));
            eq(0, paths(list.fields(), "request").size());
            RawInterface create = iface(b, "createTrade");
            eq(List.of("tradeId", "quantity", "client.clientId", "client.name", "tags[]"), paths(create.fields(), "request"));
            eq("number(double)", list.fields().get(1).dataType());
            eq("Unique trade identifier", list.fields().get(0).description());
            eq(List.of("Trade", "Client"), b.structures().stream().map(RawStructure::name).toList());
            eq("A single trade", b.structures().get(0).description());
            eq(List.of("tradeId", "quantity", "client.clientId", "client.name", "tags[]"), paths(b.structures().get(0).fields(), null));
            IngestTests.expect(b, "OPENAPI_INTERPRETATION");
            eq("Trade API 1.2.0", b.notes().get(0).text());
        });

        test("openapi: Swagger 2.0, operation naming, method order and responses", () -> {
            RawMetadataBundle b = api("{\"swagger\":\"2.0\",\"paths\":{\"/a\":{\"post\":{\"parameters\":[{\"in\":\"body\",\"name\":\"b\","
                    + "\"schema\":{\"$ref\":\"#/definitions/In\"}}],\"responses\":{\"default\":{\"schema\":{\"type\":\"object\","
                    + "\"properties\":{\"ok\":{\"type\":\"boolean\"}}}}}},\"get\":{\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/In\"}}}}}},"
                    + "\"definitions\":{\"In\":{\"properties\":{\"id\":{\"type\":\"integer\",\"format\":\"int64\"}}}}}");
            eq(List.of("GET /a", "POST /a"), b.interfaces().stream().map(RawInterface::name).toList());
            eq(List.of("id"), paths(iface(b, "GET /a").fields(), "response"));
            eq(List.of("id"), paths(iface(b, "POST /a").fields(), "request"));
            eq(List.of("ok"), paths(iface(b, "POST /a").fields(), "response"));
            eq("integer(int64)", iface(b, "GET /a").fields().get(0).dataType());
            contains(b.sources().get(0).detail(), "Swagger 2.0");
        });

        test("openapi: allOf merges, oneOf is flagged, arrays of objects use [] paths", () -> {
            RawMetadataBundle b = api("{\"openapi\":\"3.0.0\",\"paths\":{\"/x\":{\"get\":{\"responses\":{\"200\":{\"content\":{\"application/json\":"
                    + "{\"schema\":{\"allOf\":[{\"$ref\":\"#/components/schemas/Base\"},{\"type\":\"object\",\"properties\":{\"items\":"
                    + "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}},"
                    + "\"pick\":{\"oneOf\":[{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}},{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}}}]}}}]}}}}}}}},"
                    + "\"components\":{\"schemas\":{\"Base\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}}}");
            eq(List.of("id", "items[].sku", "pick.a", "pick.b"), paths(iface(b, "GET /x").fields(), "response"));
            IngestTests.expect(b, "OPENAPI_COMPOSITION");
        });

        test("openapi: cycles, external and broken references are contained and reported", () -> {
            RawMetadataBundle b = api("{\"openapi\":\"3.0.0\",\"paths\":{\"/n\":{\"get\":{\"responses\":{\"200\":{\"content\":{\"application/json\":"
                    + "{\"schema\":{\"$ref\":\"#/components/schemas/Node\"}}}}}}},\"/e\":{\"get\":{\"responses\":{\"200\":{\"content\":{\"application/json\":"
                    + "{\"schema\":{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"https://other.example/s.json#/X?key=SECRET\"},"
                    + "\"y\":{\"$ref\":\"#/components/schemas/Nope\"},\"ok\":{\"type\":\"string\"}}}}}}}}}},"
                    + "\"components\":{\"schemas\":{\"Node\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                    + "\"child\":{\"$ref\":\"#/components/schemas/Node\"}}}}}}");
            eq(List.of("name"), paths(iface(b, "GET /n").fields(), "response"));
            eq(List.of("ok"), paths(iface(b, "GET /e").fields(), "response"));
            IngestTests.expect(b, "OPENAPI_CYCLE");
            IngestTests.expect(b, "OPENAPI_EXTERNAL_REF");
            IngestTests.expect(b, "OPENAPI_BAD_REF");
            excludes(b.findings().toString(), "SECRET");
        });

        test("openapi: wrong documents are rejected with a reason", () -> {
            IngestTests.expect(api("{\"hello\":1}"), "OPENAPI_NOT_RECOGNISED");
            IngestTests.expect(api("[1,2]"), "OPENAPI_NOT_RECOGNISED");
            IngestTests.expect(api("{broken"), "JSON_SYNTAX");
            IngestTests.expect(api("{\"openapi\":\"3.0.0\",\"paths\":{}}"), "OPENAPI_NO_OPERATIONS");
            RawMetadataBundle yaml = IngestTests.run("api.yaml", "openapi: 3.0.0");
            IngestTests.expect(yaml, "FORMAT_UNAVAILABLE");
            contains(yaml.findings().get(0).suggestion(), "JSON");
        });

        test("openapi draft: interfaces only when a direction is chosen; response or request fields by direction", () -> {
            RawMetadataBundle b = api(resource("/static/samples/sample-openapi.json"));
            DraftBuilder.Result none = DraftBuilder.build(b, new DraftBuilder.SystemContext(null, null, null));
            eq("DRAFT_DIRECTION_REQUIRED", none.findings().get(0).code());
            yes(none.manifest() == null, "no draft without a direction");

            DraftBuilder.Result dist = DraftBuilder.build(b, new DraftBuilder.SystemContext(null, null, null, InterfaceType.DISTRIBUTION));
            var interfaces = dist.manifest().interfaces();
            eq(List.of("listTrades", "createTrade"), interfaces.stream().map(i -> i.name()).toList());
            eq(InterfaceType.DISTRIBUTION, interfaces.get(0).type());
            eq(5, interfaces.get(0).interfaceDataElements().size());
            eq("client.clientId", interfaces.get(0).interfaceDataElements().get(2).physicalName());
            eq(null, interfaces.get(1).interfaceDataElements());
            yes(dist.manifest().databases() == null, "no database without tables");
            yes(dist.findings().stream().anyMatch(f -> f.code().equals("DRAFT_STRUCTURES_NOT_INCLUDED")), "structures stay candidates");
            yes(dist.findings().stream().anyMatch(f -> f.code().equals("DRAFT_INTERFACE_EMPTY")), "empty interface noted");

            DraftBuilder.Result src = DraftBuilder.build(b, new DraftBuilder.SystemContext(null, null, null, InterfaceType.SOURCING));
            eq(null, src.manifest().interfaces().get(0).interfaceDataElements());
            eq(5, src.manifest().interfaces().get(1).interfaceDataElements().size());

            ExportService export = ExportService.standard();
            ExportResult r = export.export(new ManifestMapper.ReadResult(dist.manifest(), List.of()), dist.unresolved(), "yaml");
            yes(r.exportable(), r.report().toTree().toString());
            contains(r.content(), "type: \"DISTRIBUTION\"");
            contains(r.content(), "physicalName: \"client.clientId\"");
        });

        test("openapi draft: tables and interfaces together, interfaces skipped without a direction", () -> {
            RawMetadataBundle b = SourceIngestionService.standard().ingest(List.of(
                    SourceInput.of("schema.sql", resource("/static/samples/sample-schema.sql")),
                    SourceInput.of("api.json", resource("/static/samples/sample-openapi.json"))), IngestOptions.defaults());
            DraftBuilder.Result without = DraftBuilder.build(b, new DraftBuilder.SystemContext("DB", null, null));
            yes(without.manifest() != null && without.manifest().interfaces() == null, "tables only");
            yes(without.findings().stream().anyMatch(f -> f.code().equals("DRAFT_INTERFACES_NOT_INCLUDED")), "reported");
            DraftBuilder.Result both = DraftBuilder.build(b, new DraftBuilder.SystemContext("DB", null, null, InterfaceType.DISTRIBUTION));
            eq(1, both.manifest().databases().size());
            eq(2, both.manifest().interfaces().size());
            eq("DRAFT_DB_NAME_REQUIRED", DraftBuilder.build(b, new DraftBuilder.SystemContext("", null, null, InterfaceType.DISTRIBUTION)).findings().get(0).code());
        });
    }
}
