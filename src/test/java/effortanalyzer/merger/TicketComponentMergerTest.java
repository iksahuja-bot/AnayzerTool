package effortanalyzer.merger;

import effortanalyzer.util.ExcelUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TicketComponentMergerTest {

    // Helpers to build test Excel files using the same ExcelUtils the app uses

    private Path writeTicketFile(Path dir, List<Map<String, String>> rows) throws IOException {
        Path path = dir.resolve("tickets.xlsx");
        ExcelUtils.writeToExcel(rows, path, "Tickets");
        return path;
    }

    private Path writeComponentFile(Path dir, List<Map<String, String>> rows) throws IOException {
        Path path = dir.resolve("components.xlsx");
        ExcelUtils.writeToExcel(rows, path, "Components");
        return path;
    }

    private Map<String, String> ticket(String component, String key, String summary) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Component/s", component);
        row.put("Key", key);
        row.put("Project", "PROJ");
        row.put("Summary", summary);
        row.put("Status", "Open");
        row.put("Resolution", "Unresolved");
        row.put("Ticket Type", "Story");
        row.put("Time Spent", "3600");
        row.put("Deliverables", "");
        return row;
    }

    private Map<String, String> component(String name, String pack) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Name", name);
        row.put("Pack", pack);
        return row;
    }

    // Basic merge

    @Test
    void mergeProducesOutputFile(@TempDir Path tmpDir) throws IOException {
        Path ticketFile    = writeTicketFile(tmpDir, List.of(ticket("AuthService", "PROJ-1", "Fix login")));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("AuthService", "Security")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        assertTrue(outputFile.toFile().exists(), "Output file should be created");
        assertTrue(outputFile.toFile().length() > 0, "Output file should not be empty");
    }

    @Test
    void matchedComponentReceivesPackValue(@TempDir Path tmpDir) throws IOException {
        Path ticketFile    = writeTicketFile(tmpDir, List.of(ticket("PaymentGateway", "PROJ-2", "Payment issue")));
        Path componentFile = writeComponentFile(tmpDir, List.of(
                component("PaymentGateway", "Billing"),
                component("OrderService",   "Orders")
        ));
        Path outputFile = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        // Verify output can be opened and the pack column is present
        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            var headers = ExcelUtils.readHeaders(sheet.getRow(0));
            assertTrue(headers.contains("Pack"), "Output should contain Pack column");

            var dataRow = ExcelUtils.readRowAsMap(sheet.getRow(1), headers);
            assertEquals("Billing", dataRow.get("Pack"),
                    "Matched component should carry its Pack value");
        }
    }

    @Test
    void unmatchedTicketProducedWithEmptyPack(@TempDir Path tmpDir) throws IOException {
        Path ticketFile    = writeTicketFile(tmpDir, List.of(ticket("UnknownComponent", "PROJ-3", "Orphan ticket")));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("SomeOtherComponent", "Pack1")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            var headers = ExcelUtils.readHeaders(sheet.getRow(0));
            var dataRow = ExcelUtils.readRowAsMap(sheet.getRow(1), headers);
            assertEquals("", dataRow.get("Pack"), "Unmatched ticket should have empty Pack");
        }
    }

    @Test
    void componentMatchingIsCaseInsensitive(@TempDir Path tmpDir) throws IOException {
        // Ticket has lowercase component name, component list has mixed case
        Path ticketFile    = writeTicketFile(tmpDir, List.of(ticket("authservice", "PROJ-4", "Auth bug")));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("AuthService", "Security")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            var headers = ExcelUtils.readHeaders(sheet.getRow(0));
            var dataRow = ExcelUtils.readRowAsMap(sheet.getRow(1), headers);
            assertEquals("Security", dataRow.get("Pack"), "Match should be case-insensitive");
        }
    }

    @Test
    void multiComponentTicketExpandsToMultipleRows(@TempDir Path tmpDir) throws IOException {
        // Ticket has two components separated by comma
        Map<String, String> t = ticket("AuthService, OrderService", "PROJ-5", "Multi-component ticket");
        Path ticketFile    = writeTicketFile(tmpDir, List.of(t));
        Path componentFile = writeComponentFile(tmpDir, List.of(
                component("AuthService",  "Security"),
                component("OrderService", "Orders")
        ));
        Path outputFile = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            // Row 0 = header, rows 1 and 2 = two expanded rows
            assertEquals(2, sheet.getLastRowNum(),
                    "Ticket with two matched components should produce two output rows");
        }
    }

    @Test
    void timeSpentConvertedFromSecondsToHours(@TempDir Path tmpDir) throws IOException {
        Map<String, String> t = ticket("AuthService", "PROJ-6", "Time check");
        t.put("Time Spent", "7200");  // 2 hours in seconds
        Path ticketFile    = writeTicketFile(tmpDir, List.of(t));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("AuthService", "Security")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            var headers = ExcelUtils.readHeaders(sheet.getRow(0));
            var dataRow = ExcelUtils.readRowAsMap(sheet.getRow(1), headers);
            String hours = dataRow.get("Time Spent (Hours)");
            assertNotNull(hours, "Output should have 'Time Spent (Hours)' column");
            assertEquals(2.0, Double.parseDouble(hours), 0.001,
                    "7200 seconds should convert to 2.00 hours");
        }
    }

    @Test
    void zeroTimeSpentWhenFieldAbsent(@TempDir Path tmpDir) throws IOException {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("Component/s", "AuthService");
        t.put("Key", "PROJ-7");
        t.put("Project", "PROJ");
        t.put("Summary", "No time tracked");
        t.put("Status", "Open");
        t.put("Resolution", "Unresolved");
        t.put("Ticket Type", "Bug");
        t.put("Deliverables", "");
        // No "Time Spent" field

        Path ticketFile    = writeTicketFile(tmpDir, List.of(t));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("AuthService", "Security")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        new TicketComponentMerger(ticketFile, componentFile, outputFile).merge();

        try (var wb = ExcelUtils.openWorkbook(outputFile)) {
            var sheet = wb.getSheetAt(0);
            var headers = ExcelUtils.readHeaders(sheet.getRow(0));
            var dataRow = ExcelUtils.readRowAsMap(sheet.getRow(1), headers);
            assertEquals("0.00", dataRow.get("Time Spent (Hours)"));
        }
    }

    @Test
    void noComponentFileColumnThrowsIllegalState(@TempDir Path tmpDir) throws IOException {
        Path ticketFile = writeTicketFile(tmpDir, List.of(ticket("X", "PROJ-8", "Test")));

        // Component file without "Name" column
        Map<String, String> badComp = new LinkedHashMap<>();
        badComp.put("WrongColumn", "something");
        Path componentFile = writeComponentFile(tmpDir, List.of(badComp));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        TicketComponentMerger merger = new TicketComponentMerger(ticketFile, componentFile, outputFile);
        assertThrows(IllegalStateException.class, merger::merge,
                "Missing 'Name' column should cause IllegalStateException");
    }

    @Test
    void noTicketComponentColumnThrowsIllegalState(@TempDir Path tmpDir) throws IOException {
        // Ticket file without any of the expected component column names
        Map<String, String> t = new LinkedHashMap<>();
        t.put("Key", "PROJ-9");
        t.put("Summary", "bad ticket");
        Path ticketFile    = writeTicketFile(tmpDir, List.of(t));
        Path componentFile = writeComponentFile(tmpDir, List.of(component("X", "Y")));
        Path outputFile    = tmpDir.resolve("out.xlsx");

        TicketComponentMerger merger = new TicketComponentMerger(ticketFile, componentFile, outputFile);
        assertThrows(IllegalStateException.class, merger::merge,
                "Missing component column in ticket file should throw IllegalStateException");
    }
}