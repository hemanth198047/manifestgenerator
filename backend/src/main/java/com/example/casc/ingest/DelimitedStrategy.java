package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import java.util.Set;

/** CSV and TSV: parses the delimited text, then applies the shared table rules in Tabular. */
public final class DelimitedStrategy implements SourceIngestionStrategy {

    @Override public String id() { return "delimited"; }

    @Override public Set<String> extensions() { return Set.of("csv", "tsv"); }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String ext = SourceIngestionService.extension(in.name());
        String format = ext.equals("tsv") ? "tsv" : "csv";
        char delim = o.delimiter() != 0 ? o.delimiter() : (ext.equals("tsv") ? '\t' : ',');
        Csv.Result r;
        try {
            r = Csv.parse(in.text(), delim, Math.max(o.headerRow(), 1) + o.maxRows());
        } catch (Csv.CsvException e) {
            b.error("CSV_SYNTAX", in.name() + ":line " + e.line(), e.getMessage(),
                    "Fix the quoting at that line, or export the file again with standard CSV quoting.");
            b.source(in.name(), format, "REJECTED", e.getMessage());
            return b.build();
        }
        Tabular.Result t = Tabular.ingest(b, in.name(), Tabular.baseName(in.name()), "line ", r.records(), r.lines(), r.truncated(), o);
        b.source(in.name(), format, t.rejected() ? "REJECTED" : "INGESTED", t.detail());
        return b.build();
    }
}
