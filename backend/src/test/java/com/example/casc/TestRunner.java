package com.example.casc;

/** Runs all tests; exit code 0 only if every test passes. Run via: gradlew unitTests */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) {
        DomainTests.register();
        ValidatorTests.register();
        OutputTests.register();
        AppTests.register();
        com.example.casc.ingest.IngestTests.register();
        com.example.casc.net.NetTests.register();
        com.example.casc.ingest.OpenApiTests.register();
        com.example.casc.wizard.WizardTests.register();
        IngestAppTests.register();
        WizardWebTests.register();
        ToolTests.register();
        FuzzTests.register();
        AccessTests.register();
        AccessAudit.register();
        for (String name : java.util.List.of("com.example.casc.adapters.poi.PoiTests", "com.example.casc.adapters.pdfbox.PdfTests",
                "com.example.casc.adapters.jsoup.HtmlTests", "com.example.casc.adapters.jsqlparser.JSqlParserTests")) {
            try {
                Class.forName(name).getMethod("register").invoke(null);
            } catch (ClassNotFoundException e) {
                System.out.println("NOTE  adapter tests not compiled (adapter not enabled): " + name.substring(name.lastIndexOf('.') + 1));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot register " + name, e);
            }
        }
        int failed = 0;
        int skipped = 0;
        for (TestKit.Case c : TestKit.CASES) {
            try {
                c.body().run();
                System.out.println("PASS  " + c.name());
            } catch (TestKit.Skipped t) {
                skipped++;
                System.out.println("SKIP  " + c.name() + "  ->  " + t.getMessage());
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL  " + c.name() + "  ->  " + t);
                for (StackTraceElement e : t.getStackTrace()) {
                    String cls = e.getClassName();
                    if (cls.startsWith("com.example.casc") && !cls.contains("TestKit") && !cls.contains("TestRunner") && !cls.contains("$$Lambda")) {
                        System.out.println("        at " + e.getFileName() + ":" + e.getLineNumber() + "  (" + cls + "." + e.getMethodName() + ")");
                        break;
                    }
                }
                if (Boolean.getBoolean("casc.test.trace")) {
                    t.printStackTrace(System.out);
                }
            }
        }
        System.out.println((TestKit.CASES.size() - failed - skipped) + " passed, " + failed + " failed, "
                + skipped + " skipped, " + TestKit.CASES.size() + " total");
        System.exit(failed == 0 ? 0 : 1);
    }
}
