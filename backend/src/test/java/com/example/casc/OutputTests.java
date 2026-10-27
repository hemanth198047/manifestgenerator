package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.json.Json;
import com.example.casc.manifest.ManifestMapper;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class OutputTests {
    private OutputTests() {}

    static final String TINY = "{\"logicalServices\":[{\"name\":\"TradeService\",\"isDeployedOnPrem\":true}],"
            + "\"systemDataElements\":[{\"name\":\"Trade Quantity\",\"logicalServices\":[\"TradeService\"]}]}";

    static ExportResult export(String json, String format) throws Exception {
        ExportService s = ExportService.standard();
        return s.export(s.read(json), List.of(), format);
    }

    static void register() {
        test("json export: stable order, no nulls, LF only, ends with newline", () -> {
            ExportResult r = export(TINY, "json");
            eq("{\n  \"logicalServices\": [\n    {\n      \"name\": \"TradeService\",\n      \"isDeployedOnPrem\": true\n    }\n  ],\n"
                    + "  \"systemDataElements\": [\n    {\n      \"name\": \"Trade Quantity\",\n      \"logicalServices\": [\n        \"TradeService\"\n      ]\n    }\n  ]\n}\n",
                    r.content());
            excludes(r.content(), "\r");
            eq("LOCAL", r.validationLevel());
            yes(r.exportable(), "tiny manifest has no errors");
        });

        test("json export round-trips the sample", () -> {
            String sample = resource("/static/samples/sample-manifest.json");
            ExportResult r = export(sample, "json");
            yes(r.report().findings().stream().noneMatch(f -> f.code().startsWith("ROUNDTRIP")), r.report().toTree().toString());
            String again = ExportService.standard().export(ManifestMapper.fromTree(Json.parse(r.content())), List.of(), "json").content();
            eq(r.content(), again);
        });

        test("yaml export: exact layout", () -> {
            ExportResult r = export(TINY, "yaml");
            eq("logicalServices:\n  - name: \"TradeService\"\n    isDeployedOnPrem: true\n"
                    + "systemDataElements:\n  - name: \"Trade Quantity\"\n    logicalServices:\n      - \"TradeService\"\n", r.content());
            yes(r.report().infos().stream().anyMatch(f -> f.code().equals("ROUNDTRIP_UNAVAILABLE")), "yaml has no round trip");
        });

        test("yaml quoting: Yes/No/numbers stay strings; escapes; odd keys quoted", () -> {
            ExportResult r = export("{\"databases\":[{\"logicalName\":\"D\",\"schemas\":[{\"physicalName\":\"s\",\"tables\":[{\"physicalName\":\"t\","
                    + "\"description\":\"line1\\nline2 \\\"q\\\" \\\\ \\u0001 \u00e9\",\"columns\":[{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"Yes\","
                    + "\"piiElements\":[{\"name\":\"123\"}],\"dataSubjectCategories\":[\"Client\"],"
                    + "\"sourcedFromColumns\":[{\"n\":\"v\",\"has space\":true,\"x\":null,\"num\":5,\"empty\":{},\"list\":[]}]}]}]}]}]}", "yaml");
            String y = r.content();
            contains(y, "description: \"line1\\nline2 \\\"q\\\" \\\\ \\u0001 \u00e9\"");
            contains(y, "sensitiveDataAttestation: \"Yes\"");
            contains(y, "- name: \"123\"");
            contains(y, "\"n\": \"v\"");
            contains(y, "\"has space\": true");
            contains(y, "x: null");
            contains(y, "num: 5");
            contains(y, "empty: {}");
            contains(y, "list: []");
            excludes(y, "\r");
        });

        test("unknown or unavailable format explains what is available", () -> {
            ExportService s = ExportService.standard();
            try {
                s.export(s.read(TINY), List.of(), "xml");
                throw new AssertionError("expected failure");
            } catch (CapabilityUnavailableException e) {
                contains(e.getMessage(), "json, yaml");
            }
        });

        test("export with errors is not exportable but still produces content", () -> {
            ExportResult r = export("{\"logicalServices\":[{\"name\":\"A\"}]}", "json");
            yes(!r.exportable(), "BOOL_MISSING is an error");
            contains(r.content(), "\"name\": \"A\"");
        });

        test("output is identical under a different default locale and time zone", () -> {
            Locale oldLocale = Locale.getDefault();
            TimeZone oldZone = TimeZone.getDefault();
            try {
                String sample = resource("/static/samples/sample-manifest.json");
                String baseJson = export(sample, "json").content();
                String baseYaml = export(sample, "yaml").content();
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
                eq(baseJson, export(sample, "JSON").content());
                eq(baseYaml, export(sample, " YAML ").content());
            } finally {
                Locale.setDefault(oldLocale);
                TimeZone.setDefault(oldZone);
            }
        });

        test("CRLF input gives the same output as LF input", () -> {
            String sample = resource("/static/samples/sample-manifest.json").replace("\r\n", "\n");
            eq(export(sample, "json").content(), export(sample.replace("\n", "\r\n"), "json").content());
        });
    }
}
