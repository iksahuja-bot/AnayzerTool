package effortanalyzer.merger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import effortanalyzer.util.ExcelUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges ticket reports with component lists to create consolidated output.
 * 
 * Features:
 * - Flexible column name matching
 * - Left join semantics (keeps tickets without component matches)
 * - Time spent conversion (seconds to hours)
 * - Proper error handling and logging
 */
public class TicketComponentMerger {

    private static final Logger logger = LogManager.getLogger(TicketComponentMerger.class);

    // Configuration
    private final Path ticketFile;
    private final Path componentFile;
    private final Path outputFile;

    // Data holders
    public record Ticket(String components, Map<String, String> fields) {}
    public record Component(String name, String pack) {}

    public TicketComponentMerger(Path ticketFile, Path componentFile, Path outputFile) {
        this.ticketFile = ticketFile;
        this.componentFile = componentFile;
        this.outputFile = outputFile;
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        // Default file names
        String ticketFileName = "TicketReport.xlsx";
        String componentFileName = "ComponentList.xlsx";
        String outputFileName = "MergedOutput.xlsx";

        // Allow command-line overrides
        if (args.length >= 3) {
            ticketFileName = args[0];
            componentFileName = args[1];
            outputFileName = args[2];
        }

        Path ticketFile = Path.of(ticketFileName);
        Path componentFile = Path.of(componentFileName);
        Path outputFile = Path.of(outputFileName);

        logger.info("Starting ticket-component merge...");
        logger.info("Ticket file: {}", ticketFile.toAbsolutePath());
        logger.info("Component file: {}", componentFile.toAbsolutePath());
        logger.info("Output file: {}", outputFile.toAbsolutePath());

        try {
            TicketComponentMerger merger = new TicketComponentMerger(ticketFile, componentFile, outputFile);
            merger.merge();
            logger.info("Merge completed successfully!");
        } catch (Exception e) {
            logger.error("Merge failed", e);
            System.exit(1);
        }
    }

    /**
     * Performs the merge operation.
     */
    public void merge() throws IOException {
        // Read input files
        List<Ticket> tickets = readTickets();
        List<Component> components = readComponents();

        logger.info("Read {} tickets and {} components", tickets.size(), components.size());

        // Merge data
        List<Map<String, String>> mergedData = mergeData(tickets, components);

        logger.info("Generated {} merged rows", mergedData.size());

        // Write output
        ExcelUtils.writeToExcel(mergedData, outputFile, "MergedData");
    }

    /**
     * Reads tickets from the ticket report file.
     */
    private List<Ticket> readTickets() throws IOException {
        List<Ticket> tickets = new ArrayList<>();

        try (Workbook wb = ExcelUtils.openWorkbook(ticketFile)) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            List<String> headers = ExcelUtils.readHeaders(headerRow);

            // Find component column (handles variations like "Component/s", "Components", etc.)
            Optional<String> componentHeader = ExcelUtils.findHeader(headers,
                    "Component/s", "Components", "Component");

            if (componentHeader.isEmpty()) {
                throw new IllegalStateException(
                        "Could not find component column in ticket file. Expected: 'Component/s', 'Components', or 'Component'");
            }

            String componentCol = componentHeader.get();
            logger.debug("Using component column: '{}'", componentCol);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, String> fields = ExcelUtils.readRowAsMap(row, headers);
                String components = fields.getOrDefault(componentCol, "").trim();
                tickets.add(new Ticket(components, fields));
            }
        }

        return tickets;
    }

    /**
     * Reads components from the component list file.
     */
    private List<Component> readComponents() throws IOException {
        List<Component> components = new ArrayList<>();

        try (Workbook wb = ExcelUtils.openWorkbook(componentFile)) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            List<String> headers = ExcelUtils.readHeaders(headerRow);

            int nameIdx = headers.indexOf("Name");
            int packIdx = headers.indexOf("Pack");

            if (nameIdx == -1) {
                throw new IllegalStateException("No 'Name' column found in component file");
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String name = ExcelUtils.getCellValueAsString(row.getCell(nameIdx)).trim();
                String pack = (packIdx >= 0) 
                        ? ExcelUtils.getCellValueAsString(row.getCell(packIdx)).trim() 
                        : "";

                if (!name.isEmpty()) {
                    components.add(new Component(name, pack));
                }
            }
        }

        return components;
    }

    /**
     * Merges tickets with components using left join semantics.
     */
    private List<Map<String, String>> mergeData(List<Ticket> tickets, List<Component> components) {
        // Build component lookup map (case-insensitive)
        Map<String, String> componentPackMap = components.stream()
                .collect(Collectors.toMap(
                        c -> c.name().toLowerCase(),
                        Component::pack,
                        (existing, replacement) -> existing // Keep first on duplicates
                ));

        List<Map<String, String>> merged = new ArrayList<>();

        for (Ticket ticket : tickets) {
            // Split components by comma or period
            String[] ticketComponents = ticket.components().split("[.,]\\s*");
            boolean anyMatched = false;

            for (String comp : ticketComponents) {
                String compTrimmed = comp.trim();
                String pack = componentPackMap.get(compTrimmed.toLowerCase());

                if (pack != null && !pack.isEmpty()) {
                    anyMatched = true;
                    merged.add(buildMergedRow(ticket.fields(), compTrimmed, pack));
                }
            }

            // Left join: include ticket even if no components matched
            if (!anyMatched) {
                merged.add(buildMergedRow(ticket.fields(), "", ""));
            }
        }

        return merged;
    }

    /**
     * Builds a merged output row.
     */
    private Map<String, String> buildMergedRow(Map<String, String> fields, String component, String pack) {
        Map<String, String> row = new LinkedHashMap<>();

        // Determine component value
        String componentValue = component.isEmpty() 
                ? getFieldValue(fields, "Component/s", "Components", "Component")
                : component;

        row.put("Component", componentValue);
        row.put("Project", getFieldValue(fields, "Project"));
        row.put("Key", getFieldValue(fields, "Key"));
        row.put("Ticket Type", getFieldValue(fields, "Ticket Type", "Issue Type", "Type"));
        row.put("Status", getFieldValue(fields, "Status"));
        row.put("Summary", getFieldValue(fields, "Summary"));
        row.put("Resolution", getFieldValue(fields, "Resolution"));

        // Convert time spent from seconds to hours
        double hours = convertTimeSpentToHours(fields);
        row.put("Time Spent (Hours)", String.format("%.2f", hours));

        row.put("Deliverables", getFieldValue(fields, "Deliverables"));
        row.put("Pack", pack);

        return row;
    }

    /**
     * Gets field value, trying multiple possible column names.
     */
    private String getFieldValue(Map<String, String> fields, String... possibleNames) {
        for (String name : possibleNames) {
            String value = fields.get(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
            // Try case-insensitive match
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (entry.getKey().trim().equalsIgnoreCase(name.trim())) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    /**
     * Converts time spent from seconds to hours.
     */
    private double convertTimeSpentToHours(Map<String, String> fields) {
        String timeStr = getFieldValue(fields, "Time Spent", "TimeSpent", "Time");
        if (timeStr.isEmpty()) {
            return 0.0;
        }

        try {
            double seconds = Double.parseDouble(timeStr.trim());
            return seconds / 3600.0;
        } catch (NumberFormatException e) {
            logger.debug("Could not parse time spent value: '{}'", timeStr);
            return 0.0;
        }
    }
}
