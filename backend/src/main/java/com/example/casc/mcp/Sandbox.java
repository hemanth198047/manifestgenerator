package com.example.casc.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything a tool may read or write lives under one root folder. Paths are resolved against the root, symbolic links are followed before the
 * check, hidden files and folders (.git, .github, .vscode, .env, ...) are never touched, files that look like secrets are never read, and
 * only plain data file types may be written.
 */
public final class Sandbox {
    private static final Set<String> WRITABLE_EXTENSIONS = Set.of("json", "html", "htm", "csv", "tsv", "txt", "md", "yaml", "yml");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(secret|passw(or)?d|credential|private[-_]?key|id_rsa|id_ed25519|\\.pem$|\\.key$|\\.p12$|\\.pfx$|\\.jks$|\\.keystore$)");

    private final Path root;
    private final boolean readOnly;

    public Sandbox(Path root, boolean readOnly) throws IOException {
        this.root = root.toRealPath();
        this.readOnly = readOnly;
    }

    public Path root() {
        return root;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** An existing file or folder inside the root that may be read. */
    public Path resolveRead(String requested) throws ToolError {
        Path p = lexical(requested);
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new ToolError("'" + requested + "' does not exist (looked under " + root + "). Use list_files to see what is there.");
        }
        inside(real, requested);
        checkSegments(root.relativize(real), requested);
        if (SECRET_NAME.matcher(real.getFileName() == null ? "" : real.getFileName().toString()).find()) {
            throw new ToolError("'" + requested + "' looks like a file that holds secrets, so it is never read.");
        }
        return real;
    }

    /** A path inside the root that may be written (it need not exist). Only plain data file types are allowed when it is a file. */
    public Path resolveWrite(String requested, boolean directory) throws ToolError {
        if (readOnly) {
            throw new ToolError("The server runs read-only (--read-only), so nothing can be written.");
        }
        Path p = lexical(requested);
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new ToolError("'" + requested + "' is not a usable path.");
        }
        Path realExisting;
        try {
            realExisting = existing.toRealPath();
        } catch (IOException e) {
            throw new ToolError("'" + requested + "' cannot be used: " + e.getMessage());
        }
        Path target = realExisting.resolve(existing.relativize(p)).normalize();
        inside(target, requested);
        Path rel = root.relativize(target);
        if (rel.toString().isEmpty()) {
            throw new ToolError("Choose a file or a folder inside the root, not the root itself.");
        }
        checkSegments(rel, requested);
        if (!directory) {
            String name = target.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!WRITABLE_EXTENSIONS.contains(ext)) {
                throw new ToolError("Only these file types may be written: " + String.join(", ", new java.util.TreeSet<>(WRITABLE_EXTENSIONS)) + ". '" + requested + "' is not one of them.");
            }
            if (Files.isDirectory(target)) {
                throw new ToolError("'" + requested + "' is a folder, but a file was expected.");
            }
        } else if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new ToolError("'" + requested + "' is a file, but a folder was expected.");
        }
        if (SECRET_NAME.matcher(target.getFileName().toString()).find()) {
            throw new ToolError("'" + requested + "' looks like a name for secrets and is not allowed as an output.");
        }
        return target;
    }

    public String relative(Path p) {
        try {
            return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    private Path lexical(String requested) throws ToolError {
        if (requested == null || requested.isBlank()) {
            throw new ToolError("A path is missing.");
        }
        if (requested.indexOf('\0') >= 0 || requested.contains("\n") || requested.contains("\r")) {
            throw new ToolError("The path contains a control character.");
        }
        try {
            Path raw = Paths.get(requested);
            return (raw.isAbsolute() ? raw : root.resolve(raw)).normalize();
        } catch (InvalidPathException e) {
            throw new ToolError("'" + requested + "' is not a valid path.");
        }
    }

    private void inside(Path real, String requested) throws ToolError {
        if (!real.startsWith(root)) {
            throw new ToolError("'" + requested + "' is outside the folder this server may use (" + root + "). Use a path inside it.");
        }
    }

    private static void checkSegments(Path relative, String requested) throws ToolError {
        for (Path seg : relative) {
            String s = seg.toString();
            if (s.startsWith(".") && !s.isEmpty()) {
                throw new ToolError("'" + requested + "' is inside a hidden file or folder (" + s + "), which is never touched.");
            }
        }
    }
}
