package effortanalyzer.source;

import effortanalyzer.util.ExcelUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes source-inventory checkout and source-scan results. */
public class SourceInventoryReportWriter {

    private static final Logger logger = LogManager.getLogger(SourceInventoryReportWriter.class);

    public void write(String outputFile, String module, List<SourceScanResult> results) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            writeSourceSheets(wb, module, results);
            writeWorkbook(wb, outPath);
        }
        logger.info("Source inventory report written: {}", outPath);
    }

    public void append(String outputFile, String module, List<SourceScanResult> results) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        if (!Files.isRegularFile(outPath)) {
            throw new IOException("Cannot append source inventory sheets because report does not exist: " + outPath);
        }

        byte[] workbookBytes = Files.readAllBytes(outPath);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            removeSheetIfPresent(wb, "Source Inventory");
            removeSheetIfPresent(wb, "Source Findings");
            removeSheetIfPresent(wb, "Checkout Errors");
            writeSourceSheets(wb, module, results);
            writeWorkbook(wb, outPath);
        }
        logger.info("Source inventory sheets appended to report: {}", outPath);
    }

    private void writeSourceSheets(Workbook wb, String module, List<SourceScanResult> results) {
        CellStyle header = ExcelUtils.createHeaderStyle(wb);
        CellStyle wrap = wb.createCellStyle();
        wrap.setWrapText(true);
        writeInventorySheet(wb, header, wrap, module, results);
        writeFindingsSheet(wb, header, wrap, results);
        writeCheckoutErrorsSheet(wb, header, wrap, results);
    }

    private void writeWorkbook(Workbook wb, Path outPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
            wb.write(fos);
        } catch (IOException e) {
            throw new IOException(ExcelUtils.diagnoseWriteFailure(outPath, e), e);
        }
    }

    private static void removeSheetIfPresent(Workbook wb, String name) {
        int index = wb.getSheetIndex(name);
        if (index >= 0) wb.removeSheetAt(index);
    }

    private void writeInventorySheet(Workbook wb, CellStyle header, CellStyle wrap, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Source Inventory");
        String[] headers = {"Module", "Component", "Repository", "Type", "Enabled", "Checkout Status", "Checkout Path", "Files Scanned", "Findings"};
        writeHeader(sheet, header, headers);
        int r = 1;
        for (SourceScanResult result : results) {
            SourceComponent c = result.component();
            Row row = sheet.createRow(r++);
            set(row, 0, module, wrap);
            set(row, 1, c.component(), wrap);
            set(row, 2, c.repository(), wrap);
            set(row, 3, c.type().name(), wrap);
            set(row, 4, String.valueOf(c.enabled()), wrap);
            set(row, 5, result.checkout().status(), wrap);
            set(row, 6, result.checkout().checkoutPath() == null ? "" : result.checkout().checkoutPath().toString(), wrap);
            set(row, 7, String.valueOf(result.filesScanned()), wrap);
            set(row, 8, String.valueOf(result.findings().size()), wrap);
        }
        size(sheet, headers.length);
    }

    private void writeFindingsSheet(Workbook wb, CellStyle header, CellStyle wrap, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Source Findings");
        String[] headers = {"Component", "Repository", "Scanner", "Category", "Severity", "File", "Line", "Rule", "Description", "Remediation", "Context"};
        writeHeader(sheet, header, headers);
        int r = 1;
        for (SourceScanResult result : results) {
            for (SourceFinding f : result.findings()) {
                Row row = sheet.createRow(r++);
                set(row, 0, f.component(), wrap);
                set(row, 1, f.repository(), wrap);
                set(row, 2, f.scanner(), wrap);
                set(row, 3, f.category(), wrap);
                set(row, 4, f.severity(), wrap);
                set(row, 5, f.file(), wrap);
                set(row, 6, String.valueOf(f.line()), wrap);
                set(row, 7, f.rule(), wrap);
                set(row, 8, f.description(), wrap);
                set(row, 9, f.remediation(), wrap);
                set(row, 10, f.context(), wrap);
            }
        }
        size(sheet, headers.length);
    }

    private void writeCheckoutErrorsSheet(Workbook wb, CellStyle header, CellStyle wrap, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Checkout Errors");
        String[] headers = {"Component", "Repository", "Type", "Status", "Message"};
        writeHeader(sheet, header, headers);
        int r = 1;
        for (SourceScanResult result : results) {
            if (result.checkout().success()) continue;
            SourceComponent c = result.component();
            Row row = sheet.createRow(r++);
            set(row, 0, c.component(), wrap);
            set(row, 1, c.repository(), wrap);
            set(row, 2, c.type().name(), wrap);
            set(row, 3, result.checkout().status(), wrap);
            set(row, 4, result.checkout().message(), wrap);
        }
        size(sheet, headers.length);
    }

    private static void writeHeader(Sheet sheet, CellStyle style, String[] headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private static void set(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void size(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 70 * 256) sheet.setColumnWidth(i, 70 * 256);
        }
    }
}
