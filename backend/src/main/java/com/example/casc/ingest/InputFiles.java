package com.example.casc.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads files and directories into SourceInputs with these rules:
 *  - when a root is given, every real path (after resolving symbolic links) must lie inside it;
 *  - symbolic links met while walking a directory are skipped and reported;
 *  - directories are walked to a depth of 4, hidden files are skipped, and only known extensions are considered;
 *  - results are ordered by relative path using '/', independent of the file system order;
 *  - size and count limits apply; problems become unreadable sources, never silent omissions.
 */
public final class InputFiles {
    private InputFiles() {}

    public static final int MAX_FILES = 200;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;

    public record Result(List<SourceInput> inputs, List<String> notes) {}

    public static Result collect(List<String> paths, Path root, Set<String> knownExtensions) {
        List<SourceInput> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Path realRoot = null;
        if (root != null) {
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                out.add(SourceInput.failed(root.toString(), "The configured input root cannot be resolved: " + e.getMessage()));
                return new Result(out, notes);
            }
        }
        long[] total = {0};
        for (String s : paths) {
            Path p = Path.of(s);
            Path real;
            try {
                real = p.toRealPath();
            } catch (IOException e) {
                out.add(SourceInput.failed(s, "Not found or not readable: " + s));
                continue;
            }
            if (realRoot != null && !real.startsWith(realRoot)) {
                out.add(SourceInput.failed(s, "Path is outside the allowed input root and was not read."));
                continue;
            }
            if (Files.isDirectory(real)) {
                walk(real, realRoot, knownExtensions, out, notes, total);
            } else {
                add(real, real.getFileName().toString(), out, total);
            }
        }
        return new Result(out, notes);
    }

    private static void walk(Path dir, Path realRoot, Set<String> known, List<SourceInput> out, List<String> notes, long[] total) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir, 4)) {
            s.filter(f -> !f.equals(dir)).forEach(f -> {
                String name = f.getFileName().toString();
                if (name.startsWith(".")) {
                    return;
                }
                if (Files.isSymbolicLink(f)) {
                    notes.add("Skipped symbolic link: " + rel(dir, f));
                } else if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    String ext = SourceIngestionService.extension(name);
                    if (known.contains(ext) || (ext.equals("json") && looksLikeOpenApi(f))) {
                        files.add(f);
                    } else {
                        notes.add("Skipped file with unsupported type: " + rel(dir, f));
                    }
                }
            });
        } catch (IOException e) {
            out.add(SourceInput.failed(dir.toString(), "Cannot read directory: " + e.getMessage()));
            return;
        }
        files.sort((a, b) -> rel(dir, a).compareTo(rel(dir, b)));
        for (Path f : files) {
            add(f, rel(dir, f), out, total);
        }
    }

    /** Directory scans include a .json file only if it mentions "openapi" or "swagger" near the start. */
    private static boolean looksLikeOpenApi(Path f) {
        try (java.io.InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            String head = new String(in.readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8);
            return head.contains("\"openapi\"") || head.contains("\"swagger\"");
        } catch (IOException e) {
            return false;
        }
    }

    private static String rel(Path dir, Path f) {
        return dir.relativize(f).toString().replace('\\', '/');
    }

    private static void add(Path file, String name, List<SourceInput> out, long[] total) {
        if (out.size() >= MAX_FILES) {
            out.add(SourceInput.failed(name, "More than " + MAX_FILES + " files; this file was not read."));
            return;
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                out.add(SourceInput.failed(name, "File is larger than " + MAX_FILE_BYTES + " bytes and was not read."));
                return;
            }
            if (total[0] + size > MAX_TOTAL_BYTES) {
                out.add(SourceInput.failed(name, "Total input size limit of " + MAX_TOTAL_BYTES + " bytes reached; file not read."));
                return;
            }
            total[0] += size;
            out.add(SourceInput.fromBytes(name, Files.readAllBytes(file)));
        } catch (IOException e) {
            out.add(SourceInput.failed(name, "Cannot read file: " + e.getMessage()));
        }
    }
}
