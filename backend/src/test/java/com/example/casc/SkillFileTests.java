package com.example.casc;

import static com.example.casc.TestKit.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The GitHub Copilot skills in .github/skills only name real commands, tools and options, and fit the published skill limits. */
public final class SkillFileTests {
    private SkillFileTests() {}

    public static void register() {
        test("copilot skills: every .github/skills/NAME/SKILL.md has valid frontmatter and names only real commands", () -> {
            Path root = Path.of(".github/skills");
            if (!Files.isDirectory(root)) {
                throw new Skipped("run from the repository root to check the Copilot skills");
            }
            Set<String> commands = Set.of("tool", "tools", "ingest", "validate", "export", "capabilities", "version", "help", "web", "explain", "combine", "json", "compare", "mcp");
            Set<String> toolNames = Set.of("ingest_sources", "validate_manifest_payload", "generate_casc_manifest");
            List<Path> dirs;
            try (Stream<Path> s = Files.list(root)) {
                dirs = s.filter(Files::isDirectory).sorted().toList();
            }
            yes(dirs.size() >= 8, "all capabilities are covered by skills");
            for (Path dir : dirs) {
                Path file = dir.resolve("SKILL.md");
                yes(Files.isRegularFile(file), dir + " has SKILL.md");
                String text = read(file).replace("\r\n", "\n");
                Matcher fm = Pattern.compile("^---\n(.*?)\n---\n", Pattern.DOTALL).matcher(text);
                yes(fm.find(), dir + ": frontmatter present");
                Matcher name = Pattern.compile("(?m)^name: (.+)$").matcher(fm.group(1));
                Matcher desc = Pattern.compile("(?m)^description: (.+)$").matcher(fm.group(1));
                yes(name.find() && desc.find(), dir + ": name and description");
                eq(dir.getFileName().toString(), name.group(1).trim());
                yes(name.group(1).trim().matches("[a-z0-9]+(-[a-z0-9]+)*") && name.group(1).trim().length() <= 64, dir + ": name format");
                yes(desc.group(1).length() <= 1024, dir + ": description length");
                yes(text.lines().count() < 500, dir + ": body under 500 lines");
                Matcher cmd = Pattern.compile("run-cli\\s+(\\w+)(?:\\s+(\\w+))?").matcher(text);
                while (cmd.find()) {
                    yes(commands.contains(cmd.group(1)), dir + ": unknown command " + cmd.group(1));
                    if (cmd.group(1).equals("tool") && cmd.group(2) != null) {
                        yes(toolNames.contains(cmd.group(2)), dir + ": unknown tool " + cmd.group(2));
                    }
                }
            }
        });
    }

    private static final java.util.List<String> DATASET_OPTIONS = java.util.List.of("--manifest-input", "--sql-input", "--table-metadata", "--table-list", "--domain", "--boundary",
            "--table-descriptions", "--column-metadata", "--no-upstream", "--database-name", "--schema-name", "--default-schema", "--dialect", "--combine-output", "--combine-count", "--combine-tables",
            "--combined-only", "--dataset-output", "--overwrite");
    private static final java.util.Map<String, java.util.List<String>> OTHER_OPTIONS = java.util.Map.of(
            "casc-combine-datasets", java.util.List.of("--input", "--output", "--count", "--tables", "--overwrite"),
            "casc-json-tools", java.util.List.of("--input", "--path", "--value", "--text", "--output", "--compact", "--sort-keys", "--lenient", "--with-paths", "--lines", "--overwrite"),
            "casc-compare-files", java.util.List.of("--input", "--output", "--json-output", "--path", "--key", "--title", "--max-rows", "--lenient", "--fail-on-diff", "--overwrite"));

    public static void registerSync() {
        test("skills and docs stay in step: every dataset, combine, json and compare option is in the help, COMMANDS.md, INGESTION.md and the matching skills; Claude skills match Copilot skills", () -> {
            Path commands = Path.of("docs/COMMANDS.md");
            if (!Files.isRegularFile(commands) || !Files.isDirectory(Path.of(".github/skills"))) {
                throw new Skipped("run from the repository root to check that the skills and docs are in step");
            }
            String help = helpText();
            String doc = read(commands);
            String ingestion = read(Path.of("docs/INGESTION.md"));
            for (String o : DATASET_OPTIONS) {
                yes(help.contains(o), "help lists " + o);
                yes(doc.contains(o), "COMMANDS.md lists " + o);
                yes(ingestion.contains(o) || o.equals("--overwrite") || o.equals("--dialect"), "INGESTION.md lists " + o);
                for (String skill : java.util.List.of(".github/skills/casc-dataset-manifests/SKILL.md", "infrastructure/integrations/claude/casc-dataset-manifests/SKILL.md")) {
                    yes(read(Path.of(skill)).contains(o), skill + " lists " + o);
                }
            }
            for (var e : OTHER_OPTIONS.entrySet()) {
                String skill = read(Path.of(".github/skills/" + e.getKey() + "/SKILL.md"));
                for (String o : e.getValue()) {
                    yes(doc.contains(o), "COMMANDS.md lists " + o);
                    yes(skill.contains(o), e.getKey() + " lists " + o);
                }
                eq(skill.replace("\r\n", "\n"), read(Path.of("infrastructure/integrations/claude/" + e.getKey() + "/SKILL.md")).replace("\r\n", "\n"));
            }
            for (String name : java.util.List.of("casc-explain-findings")) {
                eq(read(Path.of(".github/skills/" + name + "/SKILL.md")).replace("\r\n", "\n"), read(Path.of("infrastructure/integrations/claude/" + name + "/SKILL.md")).replace("\r\n", "\n"));
            }
            String copilot = read(Path.of(".github/skills/casc-dataset-manifests/SKILL.md")).replace("\r\n", "\n");
            String claude = read(Path.of("infrastructure/integrations/claude/casc-dataset-manifests/SKILL.md")).replace("\r\n", "\n");
            eq(copilot.substring(copilot.indexOf("## Before you start")), claude.substring(claude.indexOf("## Before you start")));
        });
    }

    public static void registerFdcUpload() {
        test("fdc-manifest-upload skill: complete structure, the exact upload command, no copies of the upload script, and its Python unit tests pass", () -> {
            Path dir = Path.of(".github/skills/fdc-manifest-upload");
            if (!Files.isDirectory(dir)) {
                throw new Skipped("run from the repository root to check the fdc-manifest-upload skill");
            }
            for (String f : java.util.List.of("SKILL.md", "README.md", "scripts/validate_upload_prerequisites.py", "scripts/auth_check.py", "scripts/run_upload.py", "scripts/fdc_common.py",
                    "scripts/install_skill.py", "references/upload_workflow.md", "references/authentication.md", "references/troubleshooting.md", "references/generic_agent_instructions.md",
                    "examples/sample_upload.md", "tests/test_validate.py", "tests/test_auth.py", "tests/test_run_upload.py")) {
                yes(Files.isRegularFile(dir.resolve(f)), "missing " + f);
            }
            String command = "/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py";
            String skill = read(dir.resolve("SKILL.md"));
            yes(skill.contains(command + " -s <server> -m <meta> -p <project> -r <release> -e <environment> -md <distribution system>"), "SKILL.md has the exact command");
            yes(read(dir.resolve("scripts/fdc_common.py")).contains("DEFAULT_SCRIPT = \"" + command + "\""), "the script path is the given one");
            for (String ref : java.util.List.of("references/upload_workflow.md", "references/authentication.md", "references/troubleshooting.md", "examples/sample_upload.md", "scripts/run_upload.py")) {
                yes(skill.contains(ref.substring(ref.indexOf('/') + 1)) || skill.contains(ref), "SKILL.md mentions " + ref);
            }
            try (java.util.stream.Stream<Path> s = Files.walk(Path.of("."), 6)) {
                yes(s.noneMatch(p -> p.getFileName() != null && p.getFileName().toString().equals("casc_upload_manifest.py")), "the upload script must not be copied into this repository");
            }
            Process proc;
            try {
                ProcessBuilder pb = new ProcessBuilder("python3", "-m", "unittest", "discover", "-s", "tests");
                pb.directory(dir.toFile());
                pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
                pb.redirectErrorStream(true);
                proc = pb.start();
            } catch (IOException e) {
                throw new Skipped("python3 is not available, so the skill's Python unit tests were not run");
            }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            eq(0, proc.waitFor());
            contains(out, "OK");
        });
    }

    private static String helpText() {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        com.example.casc.cli.Cli.run(new String[] {"help"}, InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(o, true, StandardCharsets.UTF_8), k -> null);
        return o.toString(StandardCharsets.UTF_8);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
