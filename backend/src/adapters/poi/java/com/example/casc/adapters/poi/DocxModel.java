package com.example.casc.adapters.poi;

import java.util.List;

/** A Word document reduced to what the extractor needs, so the extraction rules can be tested without Word files. */
public final class DocxModel {
    private DocxModel() {}

    /** One body element in document order. */
    public sealed interface Block permits Heading, Paragraph, Table {}

    /** level is 1 for Title and Heading 1, 2 for Heading 2, and so on. */
    public record Heading(int number, int level, String text) implements Block {}

    public record Paragraph(int number, String text) implements Block {}

    /** Rows of cell texts; the first row is the header row. */
    public record Table(int number, List<List<String>> rows) implements Block {}
}
