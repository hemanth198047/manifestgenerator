# Optional reader adapters

The default build reads CSV, TSV, TXT, Markdown, SQL (a documented subset) and OpenAPI JSON with the JDK alone. Four **optional adapters** add formats that need a
third-party library. They are **off by default**: when an adapter is not enabled its sources are not compiled, its library is not resolved, and the application reports
the format as unavailable with an alternative, exactly as before. Adapters plug in through `java.util.ServiceLoader`, so the baseline never depends on them.

| Adapter | Library | Adds | Class (registered through ServiceLoader) |
|---------|---------|------|-------------------------------------------|
| `poi` | Apache POI (`poi`, `poi-ooxml` and their dependencies) | `.xlsx`, `.xlsm`, `.xls` spreadsheets and `.docx` Word documents | `adapters.poi.PoiStrategy` |
| `pdfbox` | Apache PDFBox (2.x or 3.x) | `.pdf` text | `adapters.pdfbox.PdfStrategy` |
| `jsoup` | jsoup | `.html`, `.htm` pages and Confluence page JSON | `adapters.jsoup.HtmlStrategy` |
| `jsqlparser` | JSqlParser (4.x or 5.x) | table-level lineage for `INSERT`, `UPDATE`, `MERGE`, `CREATE TABLE AS` | `adapters.jsqlparser.JSqlParserAnalyzer` |

## Enabling an adapter
Your libraries must come from **your approved sources**. Nothing is ever downloaded from a public repository by this project.

**Gradle**
```
gradle jar unitTests -Padapters=poi,pdfbox,jsoup,jsqlparser -PinternalRepoUrl=https://REPLACE-ME.internal/maven-releases
gradle jar unitTests -Padapters=all -PadapterLibDir=/approved/jars          # a directory of pre-provisioned jars instead
```
Repository credentials come from the environment (`REPO_USER`, `REPO_PASSWORD`). Versions (repository mode only) default to provisional values you should replace:
`-PpoiVersion=5.2.5 -PpdfboxVersion=3.0.2 -PjsoupVersion=1.17.2 -PjsqlparserVersion=4.9`. With adapters, the libraries are copied to `build/libs/lib` and listed in the jar's `Class-Path`:
keep that folder next to `casc-generator.jar`. `gradle adapters` prints what is enabled.

**Without Gradle**
```
ADAPTERS=poi,pdfbox,jsoup ADAPTER_LIB_DIR=/approved/jars bash infrastructure/ci/build-without-gradle.sh        # PowerShell: see the header of the .ps1
```
`ADAPTER_CP="a.jar:b.jar"` (`;` on Windows) can be used instead of `ADAPTER_LIB_DIR`. `SKIP_TEST_FILES='PoiDocxTests'` leaves out a test file that your jars cannot compile.

**Libraries each adapter needs on the class path.** POI: `poi`, `poi-ooxml`, the OOXML schema classes (`poi-ooxml-lite` or `poi-ooxml-full`, whichever your POI version uses), `xmlbeans`,
`commons-collections4`, `commons-compress`, `commons-io`, `commons-math3`, `commons-codec`, `curvesapi`, and `log4j-api` for POI 5. PDFBox: `pdfbox` (+ `fontbox`, `pdfbox-io` for 3.x, `commons-logging`).
jsoup and JSqlParser have none. With Gradle in repository mode the transitive libraries are resolved for you.

## What is shown when something is missing
The home page, `capabilities` command and `/api/capabilities` list each format as AVAILABLE or UNAVAILABLE with the reason. An adapter found on the class path whose library cannot be loaded is listed as
an "Adapter problem" and skipped; the application keeps working. A source in a format without a reader is an error with the alternative (for example "export the sheets to CSV"), never silently skipped.

## How binary files travel
Spreadsheets, Word and PDF files are read as bytes, never decoded as text. The CLI reads them from disk. In JSON (REST and tool contracts) a source gives `"contentBase64"` instead of `"text"`. In the web
forms the browser reads the file and carries it in a hidden field; project files store it the same way. (Base64 here is only a transport for uploaded documents; it has nothing to do with how this
project's source code is recreated.) Base64 adds a third to the size, so raise `--max-body-bytes` (default 5 MiB) for large files.

## Spreadsheets (`.xlsx`, `.xlsm`, `.xls`)
Every visible sheet goes through the same rules as a CSV file: header row, dictionary or data mode (`auto` picks by headers), the `map.*` column mapping, the `maxRows` sampling limit, row values
not retained. Locations read `sheet 'Dictionary', row 7`. Choose one sheet with `--sheet NAME|NUMBER` (option `sheet`). Cell text is what Excel displays (locale-independent); formulas use their calculated
value. Hidden sheets, empty sheets and sheets beyond 50 are reported. Macros are never run. Password-protected workbooks are rejected (no password is tried). Memory: a workbook is loaded as a whole,
so very large files need a larger JVM heap.

## Word (`.docx`)
Headings (Title, Heading 1..n) become notes; a table whose header has a Column/Field name plus Description or Type becomes dictionary entries named from a Table column or the heading above;
paragraphs are scanned for candidate terms (`NAME: description`) and lineage text. Other tables are reported as ignored. Old `.doc` files are not supported. Locations read `table 2, row 5` or `paragraph 14`.

## PDF
Text only, page by page, with the same term and lineage rules as Markdown. **Tables are not reconstructed** (text extraction and table reconstruction are different capabilities), multi-column pages may be read
in the wrong order, and a scanned (image-only) PDF has no text and is reported (`PDF_NO_TEXT`; there is no OCR). Everything needs review. Encrypted PDFs are rejected.

## HTML and Confluence
Pages are parsed, never rendered or scripted; `script`, `style` and `template` content is ignored. Headings, dictionary tables (nested tables belong to their cell), candidate terms and lineage text are read; bold and
code are kept as Markdown-style markers so `<b>Term</b>: text` is recognised. Pages that build their content with scripts have nothing to read. Confluence REST page JSON (`body.storage`, `body.view`) is unwrapped into HTML
by the baseline service; without the HTML adapter it is reported as unavailable. Confluence pages are HTML even inside JSON.

## SQL DML lineage (JSqlParser)
For `INSERT`, `UPDATE`, `MERGE` and `CREATE TABLE AS`, the adapter reports the target table and every other table the statement reads as **table-level lineage hints**. Column-level lineage is not inferred, hints are
never written to a manifest, and statements JSqlParser cannot parse (it has little dialect support) are reported as before. Nothing is executed.

## Security notes
No macro, script or embedded object is executed. OOXML files are protected against zip bombs through POI's limits (expansion ratio, entry and text size). Encrypted files are rejected and no password is tried.
Findings name the problem, never echo file content. Row values from spreadsheets are not retained. POI and jsoup parse XML/HTML with their defaults; this repository has not independently verified their handling of external entities.

## Dependency inventory (approval and licenses are for your review; nothing here is a scan)
| Library | Used by | Version default | Transitive libraries | Approval status |
|---------|---------|-----------------|----------------------|-----------------|
| Apache POI | `poi` | 5.2.5 (provisional) | xmlbeans, commons-collections4, commons-compress, commons-io, commons-math3, commons-codec, curvesapi, log4j-api, poi-ooxml-lite | Unknown |
| Apache PDFBox | `pdfbox` | 3.0.2 (provisional) | fontbox, pdfbox-io, commons-logging | Unknown |
| jsoup | `jsoup` | 1.17.2 (provisional) | none | Unknown |
| JSqlParser | `jsqlparser` | 4.9 (provisional) | none | Unknown |

## What was verified, and what was not
| Item | Status |
|------|--------|
| Default build (no adapters): 125 tests | Run: pass |
| All four adapters together against the real libraries (POI 4.0.1, PDFBox 2.0.29, jsoup 1.15.3, JSqlParser 4.9): 144 tests | Run: 143 pass, 1 skips (the `.xlsx` case below), on one class path and in separate class loaders |
| Each adapter alone (`poi`, `pdfbox`, `jsoup`, `jsqlparser`) and `pdfbox,jsoup`: 128 to 133 tests | Run: all pass, so the baseline tests adapt to whichever adapters are present |
| Packaged jar with adapters (`java -jar`, libraries through `Class-Path`): a real PDF, a real `.xls`, an HTML page, a Confluence page JSON and an `INSERT ... SELECT` script; REST `contentBase64` upload; capability list | Run: pass |
| `poi` against POI 4.0.1 (Debian build): legacy `.xls` end to end, the extraction rules, the CLI, the tool contract, the web upload | Run: pass |
| `poi`: **`.xlsx` and `.docx` files** | **Not run.** The Debian POI build here lacks the compiled OOXML schema classes, so POI's OOXML engine cannot start. The Word extraction rules are tested without Word files, and the POI-to-model layer (`DocxReader`) compiles but has never run. `PoiDocxTests` could not even be compiled here |
| `pdfbox` against PDFBox 2.0.29: real PDFs (written by hand in the test), text, terms, lineage, damaged and empty files | Run: pass. PDFBox 3.x is supported by design (loader chosen by reflection) but **not run** |
| `jsoup` against jsoup 1.15.3: pages, tables, Confluence JSON | Run: pass; one defect found and fixed by these runs (bold text lost before term detection) |
| `jsqlparser` | Run against **JSqlParser 4.9 built from its source tag** (javacc 7.0.12, the project's own sources; the published jar was not obtainable here): the adapter, its 3 tests and the SQL reader integration pass. The published artifact and the 5.x line were **not** run; the adapter uses only the stable core of the API and calls `TablesNamesFinder` by reflection for that reason |
| Gradle wiring (`-Padapters`, services, `Class-Path`, `unitTestsSeparateLoaders` with libraries) | **Not run** (no Gradle). The equivalent plain script was run |
| Newer library versions than those above | Not run |
| Windows, PowerShell script with adapters | Not run |
