package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.*;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import java.util.List;
import java.util.Map;

final class DomainTests {
    private DomainTests() {}

    static ReadResult read(String json) throws JsonException {
        return ManifestMapper.fromJson(json);
    }

    static void register() {
        test("enums parse exact spelling only", () -> {
            eq(TargetTableType.TRANSACTION, Enums.parse(TargetTableType.class, "Transaction").orElseThrow());
            yes(Enums.parse(TargetTableType.class, "transaction").isEmpty(), "lower case must be rejected");
            yes(Enums.parse(InterfaceType.class, "Sourcing").isEmpty(), "mixed case must be rejected");
            eq(DataSubjectCategory.THIRD_PARTY, Enums.parse(DataSubjectCategory.class, "Third Party").orElseThrow());
        });

        test("missing boolean differs from false", () -> {
            var missing = new CasCManifest.LogicalService("A", null, null, null);
            var off = new CasCManifest.LogicalService("A", Boolean.FALSE, null, null);
            yes(missing.isDeployedOnPrem() == null, "missing must be null");
            eq(Boolean.FALSE, off.isDeployedOnPrem());
        });

        test("json parses and rejects malformed input", () -> {
            Object v = Json.parse("{\"a\": [1, 2.5, true, null, \"x\\u00e9\\n\"]}");
            eq(java.util.Arrays.asList(1L, new java.math.BigDecimal("2.5"), true, null, "x\u00e9\n"), ((Map<?, ?>) v).get("a"));
            throwsA(JsonException.class, () -> Json.parse("{\"a\":1,}"));
            throwsA(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
            throwsA(JsonException.class, () -> Json.parse("[1] x"));
            throwsA(JsonException.class, () -> Json.parse("\"a\nb\""));
            throwsA(JsonException.class, () -> Json.parse("01"));
            throwsA(JsonException.class, () -> Json.parse("[".repeat(100)));
        });

        test("json writer uses LF, two spaces, escapes controls", () -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("k", List.of("a\"b", "c\u0001"));
            m.put("e", Map.of());
            String s = Json.write(m);
            eq("{\n  \"k\": [\n    \"a\\\"b\",\n    \"c\\u0001\"\n  ],\n  \"e\": {}\n}", s);
            excludes(s, "\r");
        });

        test("json accepts CRLF and BOM input", () -> {
            Object v = Json.parse("\uFEFF{\r\n  \"a\": 1\r\n}\r\n");
            eq(Map.of("a", 1L), v);
        });

        test("reader reports invalid enum, type mismatch and unknown field without dropping items", () -> {
            ReadResult r = read("{\"databases\":[{\"logicalName\":\"D\",\"databaseType\":\"postgres\","
                    + "\"schemas\":[{\"physicalName\":\"s\",\"tables\":[{\"physicalName\":\"t\","
                    + "\"targetTableType\":\"transaction\",\"columns\":[]}]}]}],"
                    + "\"logicalServices\":[{\"name\":5,\"isDeployedOnPrem\":\"true\",\"bogus\":1}, 7],"
                    + "\"extra\":[]}");
            List<String> codes = r.findings().stream().map(f -> f.code() + "@" + f.path()).toList();
            yes(codes.contains("ENUM_INVALID@databases[0].databaseType"), codes.toString());
            yes(codes.contains("ENUM_INVALID@databases[0].schemas[0].tables[0].targetTableType"), codes.toString());
            yes(codes.contains("TYPE_MISMATCH@logicalServices[0].name"), codes.toString());
            yes(codes.contains("TYPE_MISMATCH@logicalServices[0].isDeployedOnPrem"), codes.toString());
            yes(codes.contains("UNKNOWN_FIELD@logicalServices[0].bogus"), codes.toString());
            yes(codes.contains("TYPE_MISMATCH@logicalServices[1]"), codes.toString());
            yes(codes.contains("UNKNOWN_FIELD@extra"), codes.toString());
            eq(2, r.manifest().logicalServices().size());
        });

        test("root that is not an object is reported", () -> {
            ReadResult r = read("[1]");
            eq("TYPE_MISMATCH", r.findings().get(0).code());
            eq("$", r.findings().get(0).path());
        });
    }
}
