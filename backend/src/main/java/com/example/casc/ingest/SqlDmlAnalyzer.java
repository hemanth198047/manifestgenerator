package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.LineageHint;
import java.util.List;

/**
 * Optional analyzer for SQL statements that the built-in reader does not interpret (INSERT, UPDATE, MERGE, CREATE TABLE AS).
 * An implementation is found with java.util.ServiceLoader, so an adapter that needs a third-party SQL parser can plug in without
 * the baseline depending on it. Hints are for review and are never written to a manifest.
 */
public interface SqlDmlAnalyzer {
    /** Short name used in messages, for example "jsqlparser". */
    String id();

    /**
     * @return lineage hints (possibly empty) when the statement was understood, or null when this analyzer cannot handle it
     *         (the statement is then reported as not analysed). Must never execute the statement.
     */
    List<LineageHint> analyze(String statementSql, String dialect, String source, String location);
}
