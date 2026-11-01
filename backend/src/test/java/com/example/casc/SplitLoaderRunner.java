package com.example.casc;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the whole suite with the main classes and the test classes in SEPARATE class loaders, as some IDEs, test runners
 * and module setups do. Package-private access between main and test code then fails with IllegalAccessError, so
 * anything the tests need from main code must be public.
 * Usage: SplitLoaderRunner MAIN_CLASSPATH TEST_CLASSPATH (each a list separated by the platform path separator).
 */
public final class SplitLoaderRunner {
    private SplitLoaderRunner() {}

    private static URL[] urls(String path) throws Exception {
        List<URL> out = new ArrayList<>();
        for (String p : path.split(File.pathSeparator)) {
            if (!p.isBlank()) {
                out.add(new File(p).toURI().toURL());
            }
        }
        return out.toArray(new URL[0]);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: SplitLoaderRunner MAIN_CLASSPATH TEST_CLASSPATH");
            System.exit(2);
        }
        ClassLoader main = new URLClassLoader(urls(args[0]), ClassLoader.getPlatformClassLoader());
        ClassLoader test = new URLClassLoader(urls(args[1]), main);
        Thread.currentThread().setContextClassLoader(test);
        try {
            test.loadClass("com.example.casc.TestRunner").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            System.exit(1);
        }
    }
}
