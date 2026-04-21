package effortanalyzer.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import effortanalyzer.config.AnalyzerConfig;
import effortanalyzer.model.GroupedResult;
import effortanalyzer.util.ExcelUtils;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Analyzes JSON migration reports and generates consolidated Excel output.
 * 
 * Features:
 * - Parallel processing for improved performance
 * - Configurable rule exclusions
 * - Thread-safe grouping
 * - Proper resource handling
 */
public class ReportAnalyzer {

    private static final Logger logger = LogManager.getLogger(ReportAnalyzer.class);

    // Single shared ObjectMapper instance (thread-safe for reading)
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnalyzerConfig config;
    private final Map<String, GroupedResult> groupedResults;

    public ReportAnalyzer(AnalyzerConfig config) {
        this.config = config;
        this.groupedResults = new ConcurrentHashMap<>();
    }

    /**
     * Runs the analysis process.
     */
    public void run() throws IOException {
        // Load JSON files
        List<Path> inputFiles = loadJsonFiles();

        if (inputFiles.isEmpty()) {
            logger.error("No JSON files found in resources/{}", config.getResourceFolder());
            throw new IllegalStateException("No input files found");
        }

        logger.info("Found {} JSON files to process", inputFiles.size());

        // Process files (parallel or sequential based on config)
        processFiles(inputFiles);

        // Generate output
        writeExcelOutput();

        logger.info("Processing complete. Total grouped results: {}", groupedResults.size());
    }

    /**
     * Loads JSON files.
     *
     * Priority:
     *   1. External directory  – when {@code --input=<path>} is supplied (consistent with other modules)
     *   2. Classpath resources – fallback for the embedded {@code reports/} folder inside the JAR
     */
    private List<Path> loadJsonFiles() {
        String inputPath = config.getInputPath();

        if (!inputPath.isBlank()) {
            return loadFromExternalDirectory(Path.of(inputPath));
        }

        return loadFromClasspath(config.getResourceFolder());
    }

    /**
     * Loads JSON files from an external filesystem directory supplied via {@code --input}.
     */
    private List<Path> loadFromExternalDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            logger.error("--input path is not a directory: {}", dir);
            return Collections.emptyList();
        }

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path entry : stream) {
                files.add(entry);
            }
        } catch (IOException e) {
            logger.error("Failed to read JSON files from {}: {}", dir, e.getMessage());
        }

        logger.info("Loaded {} JSON files from external directory: {}", files.size(), dir);
        return files;
    }

    /**
     * Loads JSON files from classpath resources (development mode or embedded reports).
     * Handles both plain filesystem classpaths and JAR-packaged resources.
     */
    private List<Path> loadFromClasspath(String resourceFolder) {
        List<Path> files = new ArrayList<>();
        try {
            URL resourceURL = getClass().getClassLoader().getResource(resourceFolder);
            if (resourceURL == null) {
                logger.error("Classpath resource folder not found: {}", resourceFolder);
                return files;
            }

            URI uri = resourceURL.toURI();

            if ("jar".equals(uri.getScheme())) {
                files = loadFromJar(uri, resourceFolder);
            } else {
                Path resourcePath = Paths.get(uri);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(resourcePath, "*.json")) {
                    for (Path entry : stream) {
                        files.add(entry);
                    }
                }
            }
        } catch (URISyntaxException | IOException e) {
            logger.error("Failed to load JSON files from classpath", e);
        }

        logger.info("Loaded {} JSON files from classpath resource folder: {}", files.size(), resourceFolder);
        return files;
    }

    /**
     * Loads files from a JAR (production / shaded-JAR mode).
     */
    private List<Path> loadFromJar(URI jarUri, String resourceFolder) throws IOException {
        List<Path> files = new ArrayList<>();

        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
            Path resourcePath = fs.getPath(resourceFolder);

            try (Stream<Path> stream = Files.walk(resourcePath, 1)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                Path tempFile = Files.createTempFile("report_", ".json");
                                Files.copy(p, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                tempFile.toFile().deleteOnExit();
                                files.add(tempFile);
                            } catch (IOException e) {
                                logger.warn("Failed to copy JAR resource: {}", p, e);
                            }
                        });
            }
        }

        return files;
    }

    /**
     * Processes all JSON files using parallel or sequential streams.
     */
    private void processFiles(List<Path> files) {
        Stream<Path> stream = config.isParallelProcessingEnabled()
                ? files.parallelStream()
                : files.stream();

        stream.forEach(this::processFile);
    }

    /**
     * Processes a single JSON file.
     */
    private void processFile(Path file) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(file.toFile());
            String jsonFileName = file.getFileName().toString();
            String componentName = extractComponentName(jsonFileName);

            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isArray()) {
                logger.debug("No rules array in file: {}", jsonFileName);
                return;
            }

            for (JsonNode rule : rules) {
                processRule(jsonFileName, componentName, rule);
            }

        } catch (IOException e) {
            logger.error("Failed to process file: {}", file, e);
        }
    }

    /**
     * Processes a single rule from a report.
     */
    private void processRule(String jsonFileName, String componentName, JsonNode rule) {
        String ruleId = rule.path("ruleId").asText("");
        String severity = rule.path("severity").asText("");

        // Skip excluded rules
        if (config.isRuleExcluded(ruleId)) {
            logger.trace("Skipping excluded rule: {}", ruleId);
            return;
        }

        String groupKey = GroupedResult.generateKey(jsonFileName, componentName, ruleId);

        // Get or create grouped result (thread-safe)
        GroupedResult group = groupedResults.computeIfAbsent(groupKey,
                k -> new GroupedResult(jsonFileName, componentName, ruleId, severity));

        // Process results
        JsonNode results = rule.get("results");
        if (results == null || !results.isArray()) {
            return;
        }

        for (JsonNode result : results) {
            processResult(group, result);
        }
    }

    /**
     * Processes a single result entry.
     */
    private void processResult(GroupedResult group, JsonNode result) {
        String affectedFileName = result.path("fileName").asText("");
        group.addAffectedFile(affectedFileName);

        JsonNode details = result.get("details");
        if (details == null || !details.isArray()) {
            return;
        }

        // Use synchronized block for thread-safe ArrayNode modification
        synchronized (group.getDetailsArray()) {
            for (JsonNode detail : details) {
                ObjectNode detailNode = OBJECT_MAPPER.createObjectNode();
                detailNode.put("affectedFileName", affectedFileName);
                detailNode.put("reference", detail.path("reference").asText(""));
                detailNode.put("match", detail.path("match").asText(""));
                detailNode.put("lineNumber", detail.path("lineNumber").asInt(-1));
                group.getDetailsArray().add(detailNode);
            }
        }
    }

    /**
     * Extracts component name from JSON file name.
     */
    private String extractComponentName(String fileName) {
        return fileName
                .replace("_AnalysisReport.json", "")
                .replace(".json", "")
                .replace(".jar", "");
    }

    /**
     * Writes the grouped results to Excel.
     */
    private void writeExcelOutput() throws IOException {
        String outputFile = config.getOutputFile();
        logger.info("Writing output to: {}", outputFile);

        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Results");

            createHeaderRow(wb, sheet);

            int maxDetailColumns = calculateMaxDetailColumns();

            int rowNum = 1;
            for (GroupedResult gr : groupedResults.values()) {
                writeDataRow(sheet, rowNum++, gr, maxDetailColumns);
            }

            Row headerRow = sheet.getRow(0);
            for (int i = 1; i <= maxDetailColumns; i++) {
                headerRow.createCell(5 + i - 1).setCellValue("MergedDetails_JSON_Part" + i);
            }

            autoSizeColumns(sheet, 5 + maxDetailColumns);

            try (FileOutputStream out = new FileOutputStream(outPath.toFile())) {
                wb.write(out);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(outPath, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("Excel output generated: {}", outPath);
    }

    /**
     * Creates the header row with styling.
     */
    private void createHeaderRow(Workbook wb, Sheet sheet) {
        Row header = sheet.createRow(0);
        CellStyle headerStyle = ExcelUtils.createHeaderStyle(wb);

        String[] headers = {"JsonFileName", "ComponentName", "RuleID", "RuleSeverity", "AffectedFilesCount"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * Calculates the maximum number of detail columns needed.
     */
    private int calculateMaxDetailColumns() {
        int maxColumns = 1;
        int maxLength = config.getMaxCellLength();

        for (GroupedResult gr : groupedResults.values()) {
            try {
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(gr.getDetailsArray());
                int chunks = (int) Math.ceil((double) json.length() / maxLength);
                maxColumns = Math.max(maxColumns, chunks);
            } catch (IOException e) {
                logger.warn("Failed to serialize details for: {}", gr.getRuleId());
            }
        }

        return maxColumns;
    }

    /**
     * Writes a single data row.
     */
    private void writeDataRow(Sheet sheet, int rowNum, GroupedResult gr, int maxDetailColumns) {
        Row row = sheet.createRow(rowNum);

        row.createCell(0).setCellValue(gr.getJsonFileName());
        row.createCell(1).setCellValue(gr.getComponentName());
        row.createCell(2).setCellValue(gr.getRuleId());
        row.createCell(3).setCellValue(gr.getSeverity());
        row.createCell(4).setCellValue(gr.getAffectedFilesCount());

        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(gr.getDetailsArray());

            List<String> chunks = ExcelUtils.splitIntoChunks(json, config.getMaxCellLength());

            for (int i = 0; i < chunks.size(); i++) {
                row.createCell(5 + i).setCellValue(chunks.get(i));
            }

        } catch (IOException e) {
            logger.warn("Failed to serialize details for row {}", rowNum, e);
        }
    }

    /**
     * Auto-sizes columns with reasonable limits.
     */
    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < Math.min(columnCount, 10); i++) {
            sheet.autoSizeColumn(i);
            // Cap at 50 characters width
            int maxWidth = 50 * 256;
            if (sheet.getColumnWidth(i) > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            }
        }
    }

    /**
     * Gets the count of processed results.
     */
    public int getResultCount() {
        return groupedResults.size();
    }
}
