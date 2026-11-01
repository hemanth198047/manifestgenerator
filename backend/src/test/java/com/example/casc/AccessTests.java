package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.Csv;
import com.example.casc.ingest.SourceInput;
import com.example.casc.net.HttpUrlFetcher;
import com.example.casc.net.UrlFetcher;
import com.example.casc.net.UrlPolicy;
import com.example.casc.net.UrlSources;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * These tests live in a different package from the classes they call. If one of those members becomes package-private
 * again this file stops compiling, and SplitLoaderRunner catches the same mistake at run time.
 */
final class AccessTests {
    private AccessTests() {}

    static void register() {
        test("access: Csv, the policy check and URL source conversion are usable from another package", () -> {
            Csv.Result r = Csv.parse("a,b\n1,2\n", ',', 10);
            eq(2, r.records().size());
            eq(List.of(1, 2), r.lines());
            try {
                Csv.parse("a,\"never closed\n", ',', 10);
                throw new AssertionError("expected a CsvException");
            } catch (Csv.CsvException e) {
                eq(1, e.line());
                contains(e.getMessage(), "line 1");
            }
            UrlPolicy p = UrlPolicy.of(List.of("wiki.corp.example"), false, 10, 1024 * 1024);
            yes(p.hostAllowed("wiki.corp.example", -1, "https"), "allowed host");
            yes(!p.hostAllowed("other.example", -1, "https"), "unlisted host");
            try {
                new HttpUrlFetcher(p).check(URI.create("https://other.example/x"));
                throw new AssertionError("expected a FetchException");
            } catch (UrlFetcher.FetchException e) {
                contains(e.getMessage(), "allowlist");
            }
            SourceInput s = UrlSources.toSource(new UrlFetcher.Fetched("https://w.example/a.txt", "text/plain",
                    "hello".getBytes(StandardCharsets.UTF_8)), Set.of("txt"));
            eq("w.example/a.txt", s.name());
            eq("hello", s.text());
        });
    }
}
