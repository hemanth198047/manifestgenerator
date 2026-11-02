package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import com.example.casc.json.Json;
import com.example.casc.manifest.ManifestMapper;
import com.example.casc.validation.Finding;
import com.example.casc.validation.FindingGuide;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FindingGuideTests {
    private FindingGuideTests() {}

    private static String run(int expectedExit, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8), k -> null);
        eq(expectedExit, code);
        return o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8);
    }

    public static void register() {
        test("findings: every finding code in the source has an explanation, and docs/FINDINGS.md is the generated text", () -> {
            Path src = Path.of("backend/src/main/java");
            if (!Files.isDirectory(src)) {
                throw new Skipped("run from the repository root to check the findings reference");
            }
            Pattern p = Pattern.compile("(?:Finding\\.(?:error|warning|info)|\\b(?:error|warn|info|findingRaw)|new Finding)\\(\\s*\"([A-Z_]+)\"");
            Pattern via = Pattern.compile("\"((?:REF)_[A-Z_]+)\"");
            Set<String> used = new TreeSet<>();
            try (Stream<Path> s = Files.walk(src)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (Pattern q : new Pattern[] {p, via}) {
                        Matcher m = q.matcher(text);
                        while (m.find()) {
                            used.add(m.group(1));
                        }
                    }
                }
            }
            for (String code : used) {
                yes(FindingGuide.find(code).isPresent(), "finding code " + code + " has no entry in FindingGuide");
            }
            for (FindingGuide.Entry e : FindingGuide.all()) {
                yes(!e.title().isBlank() && !e.meaning().isBlank() && !e.fix().isBlank() && !e.refer().isBlank(), e.code() + " is fully described");
            }
            Path doc = Path.of("docs/FINDINGS.md");
            yes(Files.isRegularFile(doc), "docs/FINDINGS.md exists");
            eq(FindingGuide.markdown(), Files.readString(doc, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        });
        test("findings: UNKNOWN_FIELD names the supported model, what happens to the field and what to do", () -> {
            var r = ManifestMapper.fromTree(Json.parse("{\"datasets\":[],\"logicalService\":[],\"logicalServices\":[{\"name\":\"s\",\"isDeployedOnPrem\":true,\"bogus\":1}]}"));
            Finding ds = r.findings().stream().filter(f -> f.path().equals("datasets")).findFirst().orElseThrow();
            contains(ds.message(), "logicalServices, systemDataElements, interfaces, dataEntities, databases, transformations");
            contains(ds.message(), "'export' writes only the supported fields");
            contains(ds.suggestion(), "dataset manifest");
            contains(ds.suggestion(), "do NOT run 'export'");
            Finding typo = r.findings().stream().filter(f -> f.path().equals("logicalService")).findFirst().orElseThrow();
            contains(typo.suggestion(), "Did you mean 'logicalServices'?");
            Finding nested = r.findings().stream().filter(f -> f.path().equals("logicalServices[0].bogus")).findFirst().orElseThrow();
            contains(nested.message(), "Fields it understands here: name, isDeployedOnPrem, cloudProvider, systemKey");
        });
        test("findings: the CLI explains a code, lists all codes, rejects unknown codes and points to explain after findings", () -> {
            contains(run(0, "explain", "UNKNOWN_FIELD"), "How to fix it:");
            contains(run(0, "explain", "unknown_field"), "What it means:");
            contains(run(0, "explain"), "UNKNOWN_FIELD");
            contains(run(2, "explain", "NOT_A_CODE"), "Unknown finding code");
            Path dir = tempDir("casc-explain");
            try {
                Path f = dir.resolve("m.json");
                Files.writeString(f, "{\"datasets\":[]}");
                String out = run(0, "validate", "--input", f.toString());
                contains(out, "More: casc-generator explain UNKNOWN_FIELD");
                contains(out, "not the official CasC schema and not FDC acceptance");
            } finally {
                deleteTree(dir);
            }
        });
    }
}
