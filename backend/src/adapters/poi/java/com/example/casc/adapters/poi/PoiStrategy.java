package com.example.casc.adapters.poi;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.ingest.Tabular;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Spreadsheets (.xlsx, .xlsm, .xls) and Word documents (.docx) through Apache POI (an optional adapter).
 * Each visible sheet goes through the same rules as a CSV file (header row, dictionary or data mode, column mapping, sampling limit,
 * row values not retained). Choose one sheet with the option "sheet" (name or 1-based number). Macros are never run, hidden sheets
 * are skipped and reported, encrypted files are rejected (no password is ever tried). Cell text is what Excel shows
 * (DataFormatter, locale-independent); formulas use their calculated value.
 */
public final class PoiStrategy implements SourceIngestionStrategy {
    static final int MAX_SHEETS = 50;
    static final int MAX_COLUMNS = 1000;

    static {
        limitZipExpansion();
    }

    /** Guards against zip bombs in OOXML files. Done by reflection so a class path without poi-ooxml still loads this class. */
    private static void limitZipExpansion() {
        try {
            Class<?> zip = Class.forName("org.apache.poi.openxml4j.util.ZipSecureFile");
            zip.getMethod("setMinInflateRatio", double.class).invoke(null, 0.01d);
            zip.getMethod("setMaxEntrySize", long.class).invoke(null, 200L * 1024 * 1024);
            zip.getMethod("setMaxTextSize", long.class).invoke(null, 50L * 1024 * 1024);
        } catch (ReflectiveOperationException | LinkageError e) {
            // poi-ooxml is not on the class path: only .xls can be read, and .xlsx/.docx report that clearly
        }
    }

    @Override public String id() { return "poi"; }

    @Override public Set<String> extensions() { return Set.of("xlsx", "xlsm", "xls", "docx"); }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        String ext = SourceIngestionService.extension(in.name());
        return ext.equals("docx") ? docx(in, o) : spreadsheet(in, ext, o);
    }

    // ------------------------------------------------------------------ spreadsheets

    private RawMetadataBundle spreadsheet(SourceInput in, String ext, IngestOptions o) {
        Builder b = new Builder();
        String format = ext.equals("xls") ? "xls" : "xlsx";
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(in.bytes()))) {
            read(wb, in, format, o, b);
        } catch (EncryptedDocumentException e) {
            b.error("SPREADSHEET_ENCRYPTED", in.name(), "The workbook is password protected. No password is ever tried.",
                    "Remove the protection and add the file again, or export the sheets to CSV.");
            b.source(in.name(), format, "REJECTED", "encrypted");
        } catch (LinkageError e) {
            b.error("ADAPTER_LIBRARY_MISSING", in.name(), "Reading ." + ext + " needs the poi-ooxml library and its dependencies on the class path.",
                    "Add the POI OOXML libraries (docs/ADAPTERS.md), or export the sheets to CSV.");
            b.source(in.name(), format, "REJECTED", "library missing");
        } catch (Exception e) {
            b.error("SPREADSHEET_UNREADABLE", in.name(), "The file could not be read as a spreadsheet (" + e.getClass().getSimpleName() + ").",
                    "Check that it is a valid .xlsx or .xls file, or export the sheets to CSV.");
            b.source(in.name(), format, "REJECTED", "unreadable");
        }
        return b.build();
    }

    private void read(Workbook wb, SourceInput in, String format, IngestOptions o, Builder b) {
        String selected = o.sheet().trim();
        DataFormatter fmt = new DataFormatter(Locale.ROOT);
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        int sheets = wb.getNumberOfSheets();
        List<String> names = new ArrayList<>();
        List<String> details = new ArrayList<>();
        int processed = 0;
        boolean anyRejected = false;
        boolean found = selected.isEmpty();
        for (int i = 0; i < sheets; i++) {
            Sheet sheet = wb.getSheetAt(i);
            String name = sheet.getSheetName();
            names.add(name);
            if (!selected.isEmpty()) {
                if (!(name.equalsIgnoreCase(selected) || selected.equals(String.valueOf(i + 1)))) {
                    continue;
                }
                found = true;
            }
            if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) {
                b.info("SHEET_HIDDEN", in.name(), "Hidden sheet '" + name + "' was skipped.", "Unhide it or select it explicitly with the sheet option.");
                continue;
            }
            if (processed >= MAX_SHEETS) {
                b.warn("SHEET_LIMIT", in.name(), "Only the first " + MAX_SHEETS + " sheets were read.", "Select a sheet with the sheet option.");
                break;
            }
            processed++;
            Tabular.Result r = sheet(sheet, name, in, o, b, fmt, evaluator);
            if (r == null) {
                continue;
            }
            anyRejected |= r.rejected();
            details.add("'" + name + "': " + r.detail());
        }
        if (!found) {
            b.error("SHEET_NOT_FOUND", in.name(), "No sheet matches '" + selected + "'. Sheets: " + names + ".", "Use a sheet name or its number (1-based).");
            b.source(in.name(), format, "REJECTED", "sheet not found");
            return;
        }
        boolean rejected = details.isEmpty() || (anyRejected && b.tableCount() == 0 && b.termCount() == 0);
        if (details.isEmpty()) {
            b.warn("SPREADSHEET_NOTHING_EXTRACTED", in.name(), "No readable sheet with data was found.", "Check the sheet selection and that the sheets are not empty or hidden.");
        }
        b.info("CELL_TEXT_AS_SHOWN", in.name(), "Cell values are read as Excel displays them; formulas use their calculated value; macros are never run.", "Check dates and numbers against the source.");
        b.source(in.name(), format, rejected ? "REJECTED" : "INGESTED", processed + " sheet(s): " + String.join("; ", details));
    }

    /** One sheet through the shared table rules. Returns null (after an INFO finding) for an empty sheet. */
    private Tabular.Result sheet(Sheet sheet, String name, SourceInput in, IngestOptions o, Builder b, DataFormatter fmt, FormulaEvaluator evaluator) {
        int limit = Math.max(o.headerRow(), 1) + o.maxRows();
        List<List<String>> rows = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        boolean truncated = false;
        boolean wide = false;
        int width = 0;
        int formulaProblems = 0;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int last = Math.min(row.getLastCellNum(), MAX_COLUMNS);
            if (row.getLastCellNum() > MAX_COLUMNS) {
                wide = true;
            }
            List<String> cells = new ArrayList<>();
            boolean any = false;
            for (int c = 0; c < last; c++) {
                Cell cell = row.getCell(c);
                String text = "";
                if (cell != null) {
                    try {
                        text = fmt.formatCellValue(cell, evaluator);
                    } catch (RuntimeException e) {
                        formulaProblems++;
                    }
                }
                text = text.replace("\r\n", "\n").replace('\r', '\n').trim();
                any |= !text.isEmpty();
                cells.add(text);
            }
            if (!any) {
                continue;
            }
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                cells.remove(cells.size() - 1);
            }
            width = Math.max(width, cells.size());
            rows.add(cells);
            numbers.add(r + 1);
        }
        if (rows.isEmpty()) {
            b.info("SHEET_EMPTY", in.name(), "Sheet '" + name + "' has no data and was skipped.", "None needed.");
            return null;
        }
        for (List<String> row : rows) {
            while (row.size() < width) {
                row.add("");
            }
        }
        if (wide) {
            b.warn("SHEET_TOO_WIDE", in.name(), "Sheet '" + name + "' has more than " + MAX_COLUMNS + " columns; the rest were ignored.", "Split the sheet.");
        }
        if (formulaProblems > 0) {
            b.warn("FORMULA_UNREADABLE", in.name(), formulaProblems + " cell(s) in sheet '" + name + "' could not be evaluated and were read as empty.", "Check the formulas.");
        }
        return Tabular.ingest(b, in.name(), name, "sheet '" + name + "', row ", rows, numbers, truncated, o);
    }

    // ------------------------------------------------------------------ Word

    private RawMetadataBundle docx(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        try {
            List<DocxModel.Block> blocks = DocxReader.read(in.bytes());
            String detail = DocxExtractor.extract(blocks, in.name(), o, b);
            b.source(in.name(), "docx", "INGESTED", detail);
        } catch (EncryptedDocumentException e) {
            b.error("DOCX_ENCRYPTED", in.name(), "The document is password protected. No password is ever tried.", "Remove the protection and add the file again.");
            b.source(in.name(), "docx", "REJECTED", "encrypted");
        } catch (LinkageError e) {
            b.error("ADAPTER_LIBRARY_MISSING", in.name(), "Reading .docx needs the poi-ooxml library and its dependencies on the class path.",
                    "Add the POI OOXML libraries (docs/ADAPTERS.md), or save the document as text or Markdown.");
            b.source(in.name(), "docx", "REJECTED", "library missing");
        } catch (Exception e) {
            b.error("DOCX_UNREADABLE", in.name(), "The file could not be read as a .docx document (" + e.getClass().getSimpleName() + ").",
                    "Check that it is a valid .docx file (old .doc files are not supported), or save it as text or Markdown.");
            b.source(in.name(), "docx", "REJECTED", "unreadable");
        }
        return b.build();
    }
}
