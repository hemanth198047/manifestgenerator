package com.example.casc.ingest;

import java.util.Set;

/** Extracts a RawMetadataBundle from one source. Strategies never execute or interpret instructions in the input. */
public interface SourceIngestionStrategy {
    String id();

    /** Lower-case file extensions handled, without the dot. */
    Set<String> extensions();

    RawMetadataBundle ingest(SourceInput input, IngestOptions options);
}
