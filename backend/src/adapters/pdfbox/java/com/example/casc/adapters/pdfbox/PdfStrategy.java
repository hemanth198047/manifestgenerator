package com.example.casc.adapters.pdfbox;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.ingest.TextStrategy;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF text through Apache PDFBox (an optional adapter). Only the text layer is read, page by page, and scanned with the same rules
 * as Markdown: candidate terms ("NAME: description") and lineage text. Limits, stated plainly:
 *  - tables are NOT reconstructed (text extraction and table reconstruction are different capabilities); export tables as CSV;
 *  - a scanned PDF has no text layer and there is no OCR; such a file is reported, not guessed at;
 *  - the reading order of multi-column pages can be wrong, so every result needs review;
 *  - encrypted PDFs are rejected (no password is ever tried); nothing in the PDF is executed.
 * Works with PDFBox 2.x and 3.x: the document is opened through whichever loader the installed version provides.
 */
public final class PdfStrategy implements SourceIngestionStrategy {
    static final int MAX_PAGES = 500;
    static final int MAX_CHARS = 5_000_000;

    @Override public String id() { return "pdfbox"; }

    @Override public Set<String> extensions() { return Set.of("pdf"); }

    /** PDFBox 3 has Loader.loadPDF(byte[]); PDFBox 2 has PDDocument.load(byte[]). */
    private static PDDocument open(byte[] bytes) throws Exception {
        try {
            Class<?> loader = Class.forName("org.apache.pdfbox.Loader");
            return (PDDocument) loader.getMethod("loadPDF", byte[].class).invoke(null, (Object) bytes);
        } catch (ClassNotFoundException e) {
            return (PDDocument) PDDocument.class.getMethod("load", byte[].class).invoke(null, (Object) bytes);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String src = in.name();
        try (PDDocument doc = open(in.bytes())) {
            if (doc.isEncrypted()) {
                b.error("PDF_ENCRYPTED", src, "The PDF is encrypted. No password is ever tried.", "Remove the protection and add the file again, or provide a text export.");
                b.source(src, "pdf", "REJECTED", "encrypted");
                return b.build();
            }
            int pages = doc.getNumberOfPages();
            int read = Math.min(pages, MAX_PAGES);
            if (pages > MAX_PAGES) {
                b.warn("PDF_PAGE_LIMIT", src, "Only the first " + MAX_PAGES + " of " + pages + " pages were read.", "Split the document.");
            }
            TextStrategy.Prose prose = new TextStrategy.Prose(src, b);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            long chars = 0;
            int textPages = 0;
            for (int p = 1; p <= read; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) {
                    continue;
                }
                textPages++;
                chars += text.length();
                if (chars > MAX_CHARS) {
                    b.warn("PDF_TEXT_LIMIT", src, "More than " + MAX_CHARS + " characters of text; the rest was not read.", "Split the document.");
                    break;
                }
                String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
                for (int l = 0; l < lines.length; l++) {
                    String t = lines[l].trim();
                    if (!t.isEmpty()) {
                        prose.line(t, "page " + p + ", line " + (l + 1));
                    }
                }
            }
            if (textPages == 0) {
                b.warn("PDF_NO_TEXT", src, "The PDF has no text layer (a scanned or image-only document). OCR is not available.",
                        "Provide a text export, or run OCR with an approved tool and add the result.");
            } else {
                b.info("PDF_TABLES_NOT_RECONSTRUCTED", src, "Only text was extracted. Tables in the PDF are not reconstructed and multi-column pages may be read in the wrong order.",
                        "Export tables as CSV, and review every candidate term against the document.");
            }
            prose.summary();
            if (textPages > 0 && prose.terms() == 0 && prose.lineage() == 0) {
                b.warn("TEXT_NOTHING_EXTRACTED", src, "Text was read from " + textPages + " page(s) but no terms or lineage statements were recognised.",
                        "Provide 'NAME: description' lines, or a CSV data dictionary.");
            }
            b.source(src, "pdf", "INGESTED", read + " page(s) read, " + textPages + " with text, " + prose.terms() + " candidate term(s), " + prose.lineage() + " lineage statement(s)");
        } catch (Exception e) {
            b.error("PDF_UNREADABLE", src, "The file could not be read as a PDF (" + e.getClass().getSimpleName() + ").",
                    "Check that it is a valid PDF, or provide a text export.");
            b.source(src, "pdf", "REJECTED", "unreadable");
        } catch (LinkageError e) {
            b.error("ADAPTER_LIBRARY_MISSING", src, "Reading PDF needs the PDFBox library and its dependencies on the class path.", "Add them (docs/ADAPTERS.md), or provide a text export.");
            b.source(src, "pdf", "REJECTED", "library missing");
        }
        return b.build();
    }
}
