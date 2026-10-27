package com.example.casc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal test harness: no JUnit, no dependencies. */
public final class TestKit {
    private TestKit() {}

    public interface Body {
        void run() throws Exception;
    }

    record Case(String name, Body body) {}

    static final List<Case> CASES = new ArrayList<>();

    public static void test(String name, Body body) {
        CASES.add(new Case(name, body));
    }

    /** Thrown to mark a test as skipped because the environment cannot run it; reported as SKIP. */
    public static final class Skipped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Skipped(String reason) {
            super(reason);
        }
    }

    public static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void yes(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void contains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("expected to contain <" + needle + "> but was <" + haystack + ">");
        }
    }

    public static void excludes(String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError("expected NOT to contain <" + needle + ">");
        }
    }

    public static void throwsA(Class<? extends Throwable> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    public static String resource(String path) throws IOException {
        try (InputStream in = TestKit.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
