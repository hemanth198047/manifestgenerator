package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.app.ExportService;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import java.util.List;

final class ValidatorTests {
    private ValidatorTests() {}

    static ValidationReport check(String json) throws Exception {
        ExportService s = ExportService.standard();
        return s.validate(s.read(json), List.of());
    }

    static boolean has(ValidationReport r, String code, String path) {
        for (Finding f : r.findings()) {
            if (f.code().equals(code) && f.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    static void expect(ValidationReport r, String code, String path) {
        yes(has(r, code, path), "expected " + code + " at " + path + " but got " + r.toTree());
    }

    static void register() {
        test("sample manifest has no errors", () -> {
            ValidationReport r = check(resource("/static/samples/sample-manifest.json"));
            eq(0, r.errors().size());
        });

        test("cloud provider and on-prem rules", () -> {
            ValidationReport r = check("{\"logicalServices\":[{\"name\":\"A\"},{\"name\":\"B\",\"isDeployedOnPrem\":false},"
                    + "{\"name\":\"C\",\"isDeployedOnPrem\":false,\"cloudProvider\":\"X\"},"
                    + "{\"name\":\"D\",\"isDeployedOnPrem\":true,\"cloudProvider\":\"X\"}]}");
            expect(r, "BOOL_MISSING", "logicalServices[0].isDeployedOnPrem");
            expect(r, "CLOUD_REQUIRED", "logicalServices[1].cloudProvider");
            yes(!has(r, "CLOUD_REQUIRED", "logicalServices[2].cloudProvider"), "C is valid");
            expect(r, "CLOUD_UNEXPECTED", "logicalServices[3].cloudProvider");
        });

        test("required fields, duplicates and system keys", () -> {
            ValidationReport r = check("{\"logicalServices\":[{\"name\":\" \",\"isDeployedOnPrem\":true,\"systemKey\":\"k\"},"
                    + "{\"name\":\"A\",\"isDeployedOnPrem\":true,\"systemKey\":\"k\"},"
                    + "{\"name\":\"A\",\"isDeployedOnPrem\":true}],"
                    + "\"interfaces\":[{\"name\":\"I\",\"systemKey\":\"k\"}]}");
            expect(r, "REQ_BLANK", "logicalServices[0].name");
            expect(r, "KEY_DUP", "logicalServices[1].systemKey");
            expect(r, "DUP_NAME", "logicalServices[2].name");
            expect(r, "KEY_DUP", "interfaces[0].systemKey");
            expect(r, "REQ_MISSING", "interfaces[0].type");
        });

        test("references to services and elements", () -> {
            ValidationReport r = check("{\"logicalServices\":[{\"name\":\"S\",\"isDeployedOnPrem\":true}],"
                    + "\"systemDataElements\":[{\"name\":\"E\",\"logicalServices\":[\"S\",\"Nope\"]}],"
                    + "\"interfaces\":[{\"name\":\"I\",\"type\":\"SOURCING\",\"interfaceDataElements\":[{\"physicalName\":\"a.b\",\"systemDataElement\":\"Nope\",\"sensitiveDataAttestation\":\"No\"}]}],"
                    + "\"dataEntities\":[{\"name\":\"x.y\",\"systemDataElements\":[{\"name\":\"Nope\"}]}],"
                    + "\"transformations\":[{\"name\":\"T\",\"sourceDataElements\":[\"E\"],\"targetDataElements\":[\"Nope\"]}]}");
            expect(r, "REF_LOGICAL_SERVICE", "systemDataElements[0].logicalServices[1]");
            expect(r, "REF_ELEMENT", "interfaces[0].interfaceDataElements[0].systemDataElement");
            expect(r, "REF_ELEMENT", "dataEntities[0].systemDataElements[0].name");
            expect(r, "REF_ELEMENT", "transformations[0].targetDataElements[0]");
            yes(!has(r, "REF_ELEMENT", "transformations[0].sourceDataElements[0]"), "E is declared");
            yes(!has(r, "NAME_QUALIFIED", "interfaces[0].interfaceDataElements[0].physicalName"), "periods allowed in paths");
            yes(!has(r, "NAME_QUALIFIED", "dataEntities[0].name"), "periods allowed in entity names");
        });

        test("schema, table and column names must be unqualified; duplicates by scope", () -> {
            ValidationReport r = check("{\"databases\":[{\"logicalName\":\"D\",\"schemas\":["
                    + "{\"physicalName\":\"db.s\",\"tables\":[{\"physicalName\":\"s.t\",\"columns\":["
                    + "{\"physicalName\":\"t.c\",\"sensitiveDataAttestation\":\"No\"},"
                    + "{\"physicalName\":\"c2\",\"sensitiveDataAttestation\":\"No\"},"
                    + "{\"physicalName\":\"c2\",\"sensitiveDataAttestation\":\"No\"}]}]}]}]}");
            expect(r, "NAME_QUALIFIED", "databases[0].schemas[0].physicalName");
            expect(r, "NAME_QUALIFIED", "databases[0].schemas[0].tables[0].physicalName");
            expect(r, "NAME_QUALIFIED", "databases[0].schemas[0].tables[0].columns[0].physicalName");
            expect(r, "DUP_NAME", "databases[0].schemas[0].tables[0].columns[2].physicalName");
        });

        test("same column name in different tables is not a duplicate", () -> {
            ValidationReport r = check("{\"databases\":[{\"logicalName\":\"D\",\"schemas\":[{\"physicalName\":\"s\",\"tables\":["
                    + "{\"physicalName\":\"a\",\"columns\":[{\"physicalName\":\"id\",\"sensitiveDataAttestation\":\"No\"}]},"
                    + "{\"physicalName\":\"b\",\"columns\":[{\"physicalName\":\"id\",\"sensitiveDataAttestation\":\"No\"}]}]}]}]}");
            yes(r.findings().stream().noneMatch(f -> f.code().equals("DUP_NAME")), r.toTree().toString());
        });

        test("PII rules", () -> {
            String base = "{\"databases\":[{\"logicalName\":\"D\",\"schemas\":[{\"physicalName\":\"s\",\"tables\":[{\"physicalName\":\"t\",\"columns\":[";
            String end = "]}]}]}]}";
            String c = "databases[0].schemas[0].tables[0].columns[0]";
            expect(check(base + "{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"Yes\"}" + end), "PII_REQUIRED", c + ".piiElements");
            expect(check(base + "{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"Yes\",\"piiElements\":[{\"name\":\"Email\"}]}" + end),
                    "PII_CATEGORY_REQUIRED", c + ".dataSubjectCategories");
            expect(check(base + "{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"No\",\"piiElements\":[{\"name\":\"Email\"}],\"dataSubjectCategories\":[\"Client\"]}" + end),
                    "PII_CONFLICT", c + ".sensitiveDataAttestation");
            expect(check(base + "{\"physicalName\":\"c\"}" + end), "ATTEST_MISSING", c + ".sensitiveDataAttestation");
            expect(check(base + "{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"Yes\",\"piiElements\":[{\"name\":\"Email\"}],\"dataSubjectCategories\":[\"Contractor\"]}" + end),
                    "ENUM_INVALID", c + ".dataSubjectCategories[0]");
        });

        test("lineage is carried through and flagged as unverified", () -> {
            ValidationReport r = check("{\"databases\":[{\"logicalName\":\"D\",\"schemas\":[{\"physicalName\":\"s\",\"tables\":[{\"physicalName\":\"t\",\"columns\":["
                    + "{\"physicalName\":\"c\",\"sensitiveDataAttestation\":\"No\",\"sourcedFromColumns\":[{\"anything\":\"x\"}]}]}]}]}]}");
            expect(r, "LINEAGE_UNVERIFIED", "databases[0].schemas[0].tables[0].columns[0].sourcedFromColumns");
        });

        test("unresolved decisions are reported separately", () -> {
            ExportService s = ExportService.standard();
            ReadResult in = s.read("{}");
            ValidationReport r = s.validate(in, List.of("Merge TRADE_QTY with Trade Quantity?"));
            expect(r, "UNRESOLVED_DECISION", "decisions");
            eq(0, r.errors().size());
        });
    }
}
