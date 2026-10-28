package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.agent.AgentTools;
import com.example.casc.agent.ToolException;
import com.example.casc.app.ExportService;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper;
import com.example.casc.net.UrlFetcher;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Seeded, repeatable robustness checks: garbage and damaged input must produce findings or clean errors, never an uncaught exception. */
final class FuzzTests {
    private FuzzTests() {}

    private static final String ALPHABET = "abcXYZ019 _.,;:()[]{}<>|\"'`\\/#*-+=\n\r\t\u00e9\u4e2d$@";
    private static final List<String> NAMES = List.of("a.csv", "a.tsv", "a.sql", "a.md", "a.txt", "a.json", "a.yaml", "a.xlsx", "noext");

    private static String garbage(Random r, int max) {
        int n = r.nextInt(max);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    /** Deletes, duplicates or replaces a random slice. */
    private static String damage(Random r, String s) {
        if (s.isEmpty()) {
            return s;
        }
        int a = r.nextInt(s.length());
        int b = Math.min(s.length(), a + 1 + r.nextInt(12));
        return switch (r.nextInt(3)) {
            case 0 -> s.substring(0, a) + s.substring(b);
            case 1 -> s.substring(0, b) + s.substring(a);
            default -> s.substring(0, a) + garbage(r, 6) + s.substring(b);
        };
    }

    private static void ingestOk(SourceIngestionService svc, String name, String text, IngestOptions o) {
        RawMetadataBundle b = svc.ingest(List.of(SourceInput.of(name, text)), o);
        Json.write(b.toTree());                       // must also be serialisable
        yes(!b.sources().isEmpty() || !b.findings().isEmpty(), "never an empty success for " + name);
    }

    static void register() {
        test("fuzz: random text through every reader never throws and never returns an empty success", () -> {
            SourceIngestionService svc = SourceIngestionService.standard();
            Random r = new Random(20260101L);
            List<IngestOptions> options = List.of(IngestOptions.defaults(),
                    IngestOptions.fromMap(Map.of("dialect", "sqlserver", "mode", "dictionary", "hasHeader", "false")),
                    IngestOptions.fromMap(Map.of("delimiter", "pipe", "headerRow", "3", "maxRows", "5", "dialect", "oracle")));
            for (int i = 0; i < 1500; i++) {
                ingestOk(svc, NAMES.get(r.nextInt(NAMES.size())), garbage(r, 400), options.get(r.nextInt(options.size())));
            }
        });

        test("fuzz: damaged copies of the real samples are handled", () -> {
            SourceIngestionService svc = SourceIngestionService.standard();
            Random r = new Random(7L);
            Map<String, String> samples = new java.util.LinkedHashMap<>();
            samples.put("s.sql", resource("/static/samples/sample-schema.sql"));
            samples.put("d.csv", resource("/static/samples/sample-dictionary.csv"));
            samples.put("n.md", resource("/static/samples/sample-notes.md"));
            samples.put("o.json", resource("/static/samples/sample-openapi.json"));
            for (Map.Entry<String, String> e : samples.entrySet()) {
                for (int i = 0; i < 400; i++) {
                    String text = damage(r, damage(r, e.getValue()));
                    ingestOk(svc, e.getKey(), text, IngestOptions.defaults());
                }
            }
        });

        test("fuzz: damaged JSON is a clean JsonException or a readable manifest that validates without throwing", () -> {
            Random r = new Random(11L);
            String base = resource("/static/samples/sample-manifest.json");
            ExportService export = ExportService.standard();
            int parsed = 0;
            for (int i = 0; i < 1500; i++) {
                String text = damage(r, damage(r, base));
                try {
                    var in = ManifestMapper.fromTree(Json.parse(text));
                    export.export(in, List.of(), i % 2 == 0 ? "json" : "yaml");
                    parsed++;
                } catch (JsonException expected) {
                    // clean rejection
                }
            }
            yes(parsed > 50, "a fair share of damaged inputs still parsed: " + parsed);
            for (int i = 0; i < 500; i++) {
                try {
                    Json.parse(garbage(r, 120));
                } catch (JsonException expected) {
                    // clean rejection
                }
            }
        });

        test("fuzz: damaged tool requests only ever produce ToolException", () -> {
            Random r = new Random(13L);
            AgentTools tools = new AgentTools(UrlFetcher.disabled());
            List<String> bases = List.of(
                    "{\"sources\":[{\"name\":\"a.sql\",\"text\":\"CREATE TABLE s.t (a INT);\"}],\"options\":{\"dialect\":\"oracle\"}}",
                    "{\"manifest\":{\"logicalServices\":[{\"name\":\"A\",\"isDeployedOnPrem\":true}]}}",
                    "{\"parsedMetadata\":{\"sources\":[],\"tables\":[{\"name\":\"t\",\"kind\":\"TABLE\",\"source\":\"x\",\"columns\":[{\"name\":\"c\"}]}]},"
                            + "\"userInputs\":{\"system\":{\"databaseLogicalName\":\"D\"},\"tables\":{\"t\":{\"tableType\":\"Reference\"}}}}");
            List<String> names = List.of("ingest_sources", "validate_manifest_payload", "generate_casc_manifest");
            for (int i = 0; i < 1200; i++) {
                int k = r.nextInt(3);
                String text = damage(r, bases.get(k));
                try {
                    tools.call(names.get(r.nextInt(3)), Json.parse(text));
                } catch (JsonException | ToolException expected) {
                    // clean rejection
                }
            }
        });

        test("windows-style names are labels: separators and drive letters never leak into table names", () -> {
            SourceIngestionService svc = SourceIngestionService.standard();
            RawMetadataBundle b = svc.ingest(List.of(SourceInput.of("C:\\my specs\\orders.csv", "id,name\n1,x\n")), IngestOptions.defaults());
            eq("orders", b.tables().get(0).name());
            eq("sql", SourceIngestionService.extension("C:\\specs\\schema.SQL"));
            eq("csv", SourceIngestionService.extension("/home/u/my specs/a.csv"));
            eq("", SourceIngestionService.extension("C:\\specs\\README"));
        });
    }
}
