package effortanalyzer.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExcelUtilsTest {

    // ── splitIntoChunks ───────────────────────────────────────────────────────

    @Test
    void splitNullReturnsOneEmptyChunk() {
        List<String> chunks = ExcelUtils.splitIntoChunks(null, 100);
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0));
    }

    @Test
    void splitEmptyStringReturnsOneEmptyChunk() {
        List<String> chunks = ExcelUtils.splitIntoChunks("", 100);
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0));
    }

    @Test
    void splitStringWithinLimitReturnsSingleChunk() {
        String text = "hello world";
        List<String> chunks = ExcelUtils.splitIntoChunks(text, 100);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void splitStringExactlyAtLimitReturnsSingleChunk() {
        List<String> chunks = ExcelUtils.splitIntoChunks("abc", 3);
        assertEquals(1, chunks.size());
        assertEquals("abc", chunks.get(0));
    }

    @Test
    void splitStringExceedingLimitReturnsTwoChunks() {
        List<String> chunks = ExcelUtils.splitIntoChunks("abcdef", 3);
        assertEquals(2, chunks.size());
        assertEquals("abc", chunks.get(0));
        assertEquals("def", chunks.get(1));
    }

    @Test
    void splitLargeStringIntoMultipleChunks() {
        // 10 chars, maxLength 4 -> ["0123","4567","89"]
        List<String> chunks = ExcelUtils.splitIntoChunks("0123456789", 4);
        assertEquals(3, chunks.size());
        assertEquals("0123", chunks.get(0));
        assertEquals("4567", chunks.get(1));
        assertEquals("89",   chunks.get(2));
    }

    @Test
    void splitReassemblesOriginalText() {
        String original = "The quick brown fox jumps over the lazy dog";
        List<String> chunks = ExcelUtils.splitIntoChunks(original, 7);
        assertEquals(original, String.join("", chunks));
    }

    // ── findHeader ────────────────────────────────────────────────────────────

    @Test
    void findHeaderExactMatch() {
        List<String> headers = List.of("Component/s", "Summary", "Status");
        Optional<String> found = ExcelUtils.findHeader(headers, "Component/s");
        assertTrue(found.isPresent());
        assertEquals("Component/s", found.get());
    }

    @Test
    void findHeaderCaseInsensitive() {
        List<String> headers = List.of("component/s", "Summary");
        Optional<String> found = ExcelUtils.findHeader(headers, "Component/s");
        assertTrue(found.isPresent());
    }

    @Test
    void findHeaderReturnsFirstMatchingCandidate() {
        List<String> headers = List.of("Components", "Summary");
        Optional<String> found = ExcelUtils.findHeader(headers, "Component/s", "Components", "Component");
        assertTrue(found.isPresent());
        assertEquals("Components", found.get());
    }

    @Test
    void findHeaderReturnsEmptyWhenNoneMatch() {
        List<String> headers = List.of("Summary", "Status");
        assertFalse(ExcelUtils.findHeader(headers, "Component/s", "Components").isPresent());
    }

    @Test
    void findHeaderWithEmptyList() {
        assertFalse(ExcelUtils.findHeader(List.of(), "Component/s").isPresent());
    }

    // ── getCellValueAsString ─────────────────────────────────────────────────

    @Test
    void getCellValueNullCellReturnsEmpty() {
        assertEquals("", ExcelUtils.getCellValueAsString(null));
    }

    @Test
    void getCellValueStringCell() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.STRING);
            cell.setCellValue("hello");
            assertEquals("hello", ExcelUtils.getCellValueAsString(cell));
        }
    }

    @Test
    void getCellValueNumericWholeNumber() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.NUMERIC);
            cell.setCellValue(42.0);
            assertEquals("42", ExcelUtils.getCellValueAsString(cell));
        }
    }

    @Test
    void getCellValueNumericDecimal() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.NUMERIC);
            cell.setCellValue(3.14);
            assertEquals("3.14", ExcelUtils.getCellValueAsString(cell));
        }
    }

    @Test
    void getCellValueBooleanTrue() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.BOOLEAN);
            cell.setCellValue(true);
            assertEquals("true", ExcelUtils.getCellValueAsString(cell));
        }
    }

    @Test
    void getCellValueBooleanFalse() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.BOOLEAN);
            cell.setCellValue(false);
            assertEquals("false", ExcelUtils.getCellValueAsString(cell));
        }
    }

    @Test
    void getCellValueBlankCellReturnsEmpty() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Cell cell = wb.createSheet().createRow(0).createCell(0, CellType.BLANK);
            assertEquals("", ExcelUtils.getCellValueAsString(cell));
        }
    }

    // ── readHeaders ──────────────────────────────────────────────────────────

    @Test
    void readHeadersNullRowReturnsEmptyList() {
        assertTrue(ExcelUtils.readHeaders(null).isEmpty());
    }

    @Test
    void readHeadersExtractsAllColumnNames() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Row row = wb.createSheet().createRow(0);
            row.createCell(0).setCellValue("Name");
            row.createCell(1).setCellValue("Age");
            row.createCell(2).setCellValue("City");

            List<String> headers = ExcelUtils.readHeaders(row);
            assertEquals(3, headers.size());
            assertEquals("Name", headers.get(0));
            assertEquals("Age",  headers.get(1));
            assertEquals("City", headers.get(2));
        }
    }

    // ── readRowAsMap ─────────────────────────────────────────────────────────

    @Test
    void readRowAsMapMapsValuesToHeaders() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Row row = wb.createSheet().createRow(0);
            row.createCell(0).setCellValue("Alice");
            row.createCell(1).setCellValue("30");

            Map<String, String> map = ExcelUtils.readRowAsMap(row, List.of("Name", "Age"));
            assertEquals("Alice", map.get("Name"));
            assertEquals("30",    map.get("Age"));
        }
    }

    @Test
    void readRowAsMapHandlesMissingCells() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            // Only cell 0 is populated; cell 1 is missing
            Row row = wb.createSheet().createRow(0);
            row.createCell(0).setCellValue("Bob");

            Map<String, String> map = ExcelUtils.readRowAsMap(row, List.of("Name", "Age"));
            assertEquals("Bob", map.get("Name"));
            assertEquals("",    map.get("Age"));   // blank for missing cell
        }
    }

    // ── writeToExcel ─────────────────────────────────────────────────────────

    @Test
    void writeToExcelCreatesNonEmptyFile(@TempDir Path tmpDir) throws IOException {
        Path out = tmpDir.resolve("test.xlsx");
        List<Map<String, String>> data = List.of(
                Map.of("Name", "Alice", "Score", "95"),
                Map.of("Name", "Bob",   "Score", "87")
        );
        ExcelUtils.writeToExcel(data, out, "Results");
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void writeToExcelSkipsNullOrEmptyData(@TempDir Path tmpDir) throws IOException {
        Path out = tmpDir.resolve("empty.xlsx");
        ExcelUtils.writeToExcel(List.of(), out, "Results");
        assertFalse(Files.exists(out), "No file should be created for empty data");
    }

    @Test
    void writeToExcelCanBeReadBack(@TempDir Path tmpDir) throws IOException {
        Path out = tmpDir.resolve("roundtrip.xlsx");
        List<Map<String, String>> data = List.of(
                Map.of("Component", "ServiceA", "Pack", "Core")
        );
        ExcelUtils.writeToExcel(data, out, "MergedData");

        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            Sheet sheet = wb.getSheetAt(0);
            // Row 0 = headers, row 1 = data
            assertEquals(2, sheet.getLastRowNum() + 1);
            // Verify one of the data values
            Row dataRow = sheet.getRow(1);
            boolean foundServiceA = false;
            for (Cell c : dataRow) {
                if ("ServiceA".equals(c.getStringCellValue())) {
                    foundServiceA = true;
                    break;
                }
            }
            assertTrue(foundServiceA, "Written value 'ServiceA' should be readable");
        }
    }

    // ── validateAndPrepareOutput ──────────────────────────────────────────────

    @Test
    void validateAndPrepareOutputCreatesParentDirectory(@TempDir Path tmpDir) throws IOException {
        Path deep = tmpDir.resolve("a").resolve("b").resolve("out.xlsx");
        ExcelUtils.validateAndPrepareOutput(deep, org.apache.logging.log4j.LogManager.getLogger(ExcelUtilsTest.class));
        assertTrue(Files.exists(deep.getParent()));
    }

    @Test
    void validateAndPrepareOutputThrowsWhenPathIsDirectory(@TempDir Path tmpDir) {
        assertThrows(IOException.class,
                () -> ExcelUtils.validateAndPrepareOutput(tmpDir,
                        org.apache.logging.log4j.LogManager.getLogger(ExcelUtilsTest.class)));
    }
}