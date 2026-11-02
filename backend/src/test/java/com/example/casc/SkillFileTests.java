package com.example.casc;

import static com.example.casc.TestKit.*;

import java.io.IOException;
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
            Set<String> commands = Set.of("tool", "tools", "ingest", "validate", "export", "capabilities", "version", "help", "web", "explain");
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

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
