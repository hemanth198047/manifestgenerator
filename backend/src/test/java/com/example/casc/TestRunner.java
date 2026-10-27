package com.example.casc;

/** Runs all tests; exit code 0 only if every test passes. Run via: gradlew unitTests */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) {
        DomainTests.register();
        ValidatorTests.register();
        OutputTests.register();
        AppTests.register();
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
            }
        }
        System.out.println((TestKit.CASES.size() - failed - skipped) + " passed, " + failed + " failed, "
                + skipped + " skipped, " + TestKit.CASES.size() + " total");
        System.exit(failed == 0 ? 0 : 1);
    }
}
