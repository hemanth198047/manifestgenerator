package com.example.casc.adapters.poi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * The only class that touches Word (XWPF) objects: it converts a document into the plain DocxModel. Kept thin on purpose.
 * Document macros, fields and embedded objects are never run; XML is parsed by POI with external entities disabled.
 */
public final class DocxReader {
    private DocxReader() {}

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^\\s*heading\\s*(\\d+)\\s*$");
    private static final int MAX_BLOCKS = 200_000;

    public static List<DocxModel.Block> read(byte[] bytes) throws IOException {
        List<DocxModel.Block> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFStyles styles = doc.getStyles();
            int number = 0;
            int tableNumber = 0;
            for (IBodyElement element : doc.getBodyElements()) {
                if (out.size() >= MAX_BLOCKS) {
                    break;
                }
                number++;
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    int level = headingLevel(p.getStyle(), styles);
                    out.add(level > 0 ? new DocxModel.Heading(number, level, text) : new DocxModel.Paragraph(number, text));
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) element;
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText() == null ? "" : cell.getText().trim());
                        }
                        rows.add(cells);
                    }
                    out.add(new DocxModel.Table(++tableNumber, rows));
                }
            }
        }
        return out;
    }

    /** 1 for Title and Heading 1 and so on; 0 for body text. Uses the style's display name when the document defines it. */
    public static int headingLevel(String styleId, XWPFStyles styles) {
        if (styleId == null) {
            return 0;
        }
        String name = styleId;
        if (styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null && style.getName() != null) {
                name = style.getName();
            }
        }
        if (name.equalsIgnoreCase("title")) {
            return 1;
        }
        Matcher m = HEADING_STYLE.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }
}
