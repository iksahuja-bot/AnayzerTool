package effortanalyzer.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Shared utility class for Excel operations.
 * Consolidates common functionality used across the application.
 */
public final class ExcelUtils {

    private static final Logger logger = LogManager.getLogger(ExcelUtils.class);

    private ExcelUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Reads an Excel file and returns a Workbook.
     * Caller is responsible for closing the workbook.
     */
    public static Workbook openWorkbook(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        logger.debug("Opening workbook: {}", filePath);
        try (InputStream is = Files.newInputStream(filePath)) {
            return new XSSFWorkbook(is);
        }
    }

    /**
     * Reads header row and returns list of column names.
     */
    public static List<String> readHeaders(Row headerRow) {
        if (headerRow == null) {
            return Collections.emptyList();
        }
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(getCellValueAsString(cell).trim());
        }
        return headers;
    }

    /**
     * Reads a data row into a map using the provided headers.
     */
    public static Map<String, String> readRowAsMap(Row row, List<String> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            map.put(headers.get(i), getCellValueAsString(cell));
        }
        return map;
    }

    /**
     * Gets cell value as String, handling different cell types.
     */
    public static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                // Avoid scientific notation for large numbers
                double value = cell.getNumericCellValue();
                if (value == Math.floor(value) && value < Long.MAX_VALUE) {
                    yield String.valueOf((long) value);
                }
                yield String.valueOf(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (IllegalStateException e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * Writes data to an Excel file.
     */
    public static void writeToExcel(List<Map<String, String>> data, Path outputPath, String sheetName) throws IOException {
        if (data == null || data.isEmpty()) {
            logger.warn("No data to write to Excel");
            return;
        }

        logger.info("Writing {} rows to {}", data.size(), outputPath);

        Path absolute = outputPath.toAbsolutePath();
        validateAndPrepareOutput(absolute, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);

            List<String> headers = new ArrayList<>(data.get(0).keySet());
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(wb);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < data.size(); i++) {
                Row row = sheet.createRow(i + 1);
                Map<String, String> rowData = data.get(i);
                for (int j = 0; j < headers.size(); j++) {
                    row.createCell(j).setCellValue(rowData.getOrDefault(headers.get(j), ""));
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
                int maxWidth = 50 * 256;
                if (sheet.getColumnWidth(i) > maxWidth) {
                    sheet.setColumnWidth(i, maxWidth);
                }
            }

            try (FileOutputStream out = new FileOutputStream(absolute.toFile())) {
                wb.write(out);
            } catch (IOException e) {
                String detail = diagnoseWriteFailure(absolute, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("Excel file written successfully: {}", absolute);
    }

    /**
     * Creates a styled header cell style.
     */
    public static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /**
     * Splits a large string into Excel-safe chunks (max 32767 characters per cell).
     */
    public static List<String> splitIntoChunks(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return Collections.singletonList("");
        }
        
        List<String> parts = new ArrayList<>();
        int length = text.length();

        for (int i = 0; i < length; i += maxLength) {
            parts.add(text.substring(i, Math.min(length, i + maxLength)));
        }

        return parts;
    }

    /**
     * Finds a header column by name (case-insensitive, trimmed).
     */
    public static Optional<String> findHeader(List<String> headers, String... possibleNames) {
        for (String name : possibleNames) {
            Optional<String> found = headers.stream()
                    .filter(h -> h.trim().equalsIgnoreCase(name.trim()))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Output file validation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validates the output path before any bytes are written and logs specific
     * diagnostics for each failure scenario.
     *
     * Checks performed (in order):
     *   1. The path must not be an existing directory.
     *   2. The parent directory is created automatically when missing; failure
     *      to create it is reported with a clear hint.
     *   3. The parent directory must be writable.
     *   4. If the file already exists it must not be read-only or locked
     *      (e.g. open in Excel).
     *
     * @param outputPath absolute or relative path where the report will be saved
     * @param log        caller's logger so messages appear in the correct context
     * @throws IOException with a detailed, actionable message when any check fails
     */
    public static void validateAndPrepareOutput(Path outputPath, Logger log) throws IOException {
        Path absolute = outputPath.toAbsolutePath().normalize();
        Path parent   = absolute.getParent();

        // 1 — path is itself a directory
        if (Files.isDirectory(absolute)) {
            String msg = "Output path is a directory, not a file: " + absolute
                    + System.lineSeparator()
                    + "  Hint: Append a file name to the path, e.g. \""
                    + absolute + "\\report.xlsx\"";
            log.error(msg);
            throw new IOException(msg);
        }

        // 2 — parent directory does not exist → try to create it
        if (parent != null && !Files.exists(parent)) {
            log.warn("Output directory does not exist, attempting to create: {}", parent);
            try {
                Files.createDirectories(parent);
                log.info("Created output directory: {}", parent);
            } catch (IOException e) {
                String msg = "Cannot create output directory: " + parent
                        + System.lineSeparator()
                        + "  Reason: " + e.getMessage()
                        + System.lineSeparator()
                        + "  Hint: Verify you have write permission for this location, "
                        + "or choose a path that already exists.";
                log.error(msg);
                throw new IOException(msg, e);
            }
        }

        // 3 — parent directory not writable
        if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
            String msg = "Output directory is not writable: " + parent
                    + System.lineSeparator()
                    + "  Hint: Run as administrator, adjust folder permissions, "
                    + "or choose a different output directory.";
            log.error(msg);
            throw new IOException(msg);
        }

        // 4 — file exists: must be writable (could be open in Excel or marked read-only)
        if (Files.exists(absolute)) {
            if (!Files.isWritable(absolute)) {
                String msg = "Output file exists but cannot be overwritten: " + absolute
                        + System.lineSeparator()
                        + "  Possible causes:"
                        + System.lineSeparator()
                        + "    - The file is currently open in Excel or another application"
                        + System.lineSeparator()
                        + "    - The file is marked read-only"
                        + System.lineSeparator()
                        + "  Hint: Close the file, remove the read-only attribute, "
                        + "or specify a different output file name.";
                log.error(msg);
                throw new IOException(msg);
            }
            log.warn("Output file already exists and will be overwritten: {}", absolute);
        }
    }

    /**
     * Enriches an IOException caught during the actual write with a human-readable
     * diagnosis.  Call this inside the catch block of a FileOutputStream write.
     *
     * @param path  the file that was being written
     * @param cause the IOException that was thrown
     * @return a detailed message string (does NOT throw — callers decide what to do)
     */
    public static String diagnoseWriteFailure(Path path, IOException cause) {
        String rawMessage = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        Path parent = path.getParent();

        if (cause instanceof AccessDeniedException) {
            return "Access denied writing report to: " + path
                    + System.lineSeparator()
                    + "  The file may be open in another application (e.g. Excel)."
                    + System.lineSeparator()
                    + "  Close the file and retry, or choose a different output name."
                    + System.lineSeparator()
                    + "  Detail: " + rawMessage;
        }

        if (cause instanceof FileNotFoundException) {
            if (parent != null && !Files.exists(parent)) {
                return "Output directory not found when opening file for writing: " + parent
                        + System.lineSeparator()
                        + "  Create the directory first or choose an existing output path."
                        + System.lineSeparator()
                        + "  Detail: " + rawMessage;
            }
            return "Cannot create or open output file: " + path
                    + System.lineSeparator()
                    + "  Check that you have write permission for this location."
                    + System.lineSeparator()
                    + "  Detail: " + rawMessage;
        }

        return "Failed to write report to: " + path
                + System.lineSeparator()
                + "  Detail: " + rawMessage;
    }
}
