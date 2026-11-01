package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the bytecode of every test class (with the JDK's javap) and checks that each class, constructor, method and field
 * of the MAIN code that it references is public. This finds package-private reliance even in code paths that no test
 * executes, so it cannot be hidden by running everything on one class path. Skips when javap or class directories are
 * not available (for example when tests run from a jar).
 */
final class AccessAudit {
    private AccessAudit() {}

    private static final Pattern REF = Pattern.compile("//\\s*(Method|InterfaceMethod|Field|class)\\s+(\\S+?)(?:\\.([^:\\s]+):(\\S+))?\\s*$");

    private static String d(Class<?> c) {
        if (c == void.class) return "V";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == boolean.class) return "Z";
        if (c == char.class) return "C";
        if (c == byte.class) return "B";
        if (c == short.class) return "S";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + d(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String desc(Class<?>[] params, Class<?> ret) {
        StringBuilder b = new StringBuilder("(");
        for (Class<?> p : params) {
            b.append(d(p));
        }
        return b.append(")").append(d(ret)).toString();
    }

    private static boolean publicChain(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getDeclaringClass()) {
            if (!Modifier.isPublic(k.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static Path location(Class<?> c) throws Exception {
        var src = c.getProtectionDomain().getCodeSource();
        if (src == null) {
            return null;
        }
        URI u = src.getLocation().toURI();
        Path p = Path.of(u);
        return Files.isDirectory(p) ? p : null;
    }

    private static List<String> classNames(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                    .sorted().collect(Collectors.toList());
        }
    }

    static void register() {
        test("access audit: every main-code class and member that the tests reference is public", () -> {
            var javap = ToolProvider.findFirst("javap");
            Path testDir = location(TestKit.class);
            Path mainDir = location(Cli.class);
            if (javap.isEmpty() || testDir == null || mainDir == null) {
                throw new Skipped("needs javap and class directories");
            }
            List<String> mainNames = classNames(mainDir);
            java.util.Set<String> mainSlash = mainNames.stream().map(n -> n.replace('.', '/')).collect(Collectors.toSet());
            List<String> args = new ArrayList<>(List.of("-c", "-p", "-cp", testDir.toString()));
            args.addAll(classNames(testDir));
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            int rc = javap.get().run(new PrintWriter(out), new PrintWriter(err), args.toArray(new String[0]));
            yes(rc == 0, "javap failed: " + err);
            ClassLoader loader = Cli.class.getClassLoader();
            TreeSet<String> problems = new TreeSet<>();
            int checked = 0;
            String current = "?";
            for (String line : out.toString().split("\\R")) {
                Matcher header = Pattern.compile("^(?:public |final |abstract )*(?:class|interface|enum|record) (\\S+)").matcher(line);
                if (header.find()) {
                    current = header.group(1);
                    continue;
                }
                Matcher m = REF.matcher(line);
                if (!m.find() || m.group(2).startsWith("\"") || m.group(2).startsWith("[") || !mainSlash.contains(m.group(2))) {
                    continue;
                }
                checked++;
                String kind = m.group(1);
                String name = m.group(3);
                String descriptor = m.group(4);
                Class<?> c;
                try {
                    c = Class.forName(m.group(2).replace('/', '.'), false, loader);
                    c.getDeclaredMethods();                       // forces linking: fails when an adapter's library is missing
                } catch (LinkageError e) {
                    continue;
                }
                String where = current + " uses " + c.getName();
                if (!publicChain(c)) {
                    problems.add(where + ": the class is not public");
                    continue;
                }
                if (name == null) {
                    continue;
                }
                boolean found = false;
                boolean ok = false;
                if (kind.equals("Field")) {
                    for (Class<?> k = c; k != null && !found; k = k.getSuperclass()) {
                        for (Field f : k.getDeclaredFields()) {
                            if (f.getName().equals(name) && !found) {
                                found = true;
                                ok = Modifier.isPublic(f.getModifiers()) && publicChain(k);
                            }
                        }
                    }
                } else if (name.equals("<init>")) {
                    for (Constructor<?> k : c.getDeclaredConstructors()) {
                        if (descriptor.equals(desc(k.getParameterTypes(), void.class)) && !found) {
                            found = true;
                            ok = Modifier.isPublic(k.getModifiers());
                        }
                    }
                } else {
                    List<Class<?>> chain = new ArrayList<>();
                    for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                        chain.add(k);
                    }
                    chain.addAll(Arrays.asList(c.getInterfaces()));
                    for (Class<?> k : chain) {
                        for (Method mm : k.getDeclaredMethods()) {
                            if (mm.getName().equals(name) && descriptor.equals(desc(mm.getParameterTypes(), mm.getReturnType())) && !found) {
                                found = true;
                                ok = Modifier.isPublic(mm.getModifiers()) && publicChain(k);
                            }
                        }
                    }
                }
                if (found && !ok) {
                    problems.add(where + ": " + kind.toLowerCase() + " " + name + (descriptor == null ? "" : descriptor) + " is not public");
                }
            }
            yes(checked > 200, "the audit looked at too few references (" + checked + "); the parsing may be broken");
            yes(problems.isEmpty(), "tests rely on non-public main code:\n  " + String.join("\n  ", problems));
        });
    }
}
