package effortanalyzer.source;

import effortanalyzer.util.ExcelUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Reads Component/Repository source inventory workbooks. */
public class SourceInventoryReader {

    public List<SourceComponent> read(Path workbookPath) throws IOException {
        List<SourceComponent> components = new ArrayList<>();

        try (Workbook wb = ExcelUtils.openWorkbook(workbookPath)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return components;

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            List<String> headers = ExcelUtils.readHeaders(headerRow);
            Map<String, Integer> columns = normalizeHeaders(headers);

            int componentCol = required(columns, "component", "name");
            int repositoryCol = required(columns, "repository", "repo", "url", "svn", "git");

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String component = value(row, componentCol);
                String repository = value(row, repositoryCol).trim();
                if (component.isBlank() && repository.isBlank()) continue;
                if (repository.isBlank()) continue;

                String trunk = value(row, optional(columns, "trunk", "trunkurl", "trunkrepository", "latesttrunk"));
                String enabledValue = value(row, optional(columns, "enabled"));
                boolean enabled = enabledValue.isBlank() || isEnabled(enabledValue);
                String typeValue = value(row, optional(columns, "type", "repositorytype", "repository_type", "scm"));
                String branch = value(row, optional(columns, "branch", "tag"));
                String revision = value(row, optional(columns, "revision", "rev", "commit"));
                String path = value(row, optional(columns, "path", "subpath", "sourcepath", "source_path"));
                String generatedJars = value(row, optional(columns,
                        "generatedjars", "generatedartifacts", "artifacts", "jars", "jarpaths", "generatedbinaries"));
                String applicationPackages = value(row, optional(columns,
                        "applicationpackages", "apppackages", "packages", "packageprefixes", "ownedpackages"));
                String ownership = value(row, optional(columns,
                        "ownership", "ownertype", "codeownership", "scope"));
                String trunkValidated = value(row, optional(columns,
                        "trunkvalidated", "validatedtrunk", "trunkontarget", "trunkverified"));

                components.add(new SourceComponent(
                        component.trim(),
                        repository,
                        trunk,
                        RepositoryType.from(typeValue, repository),
                        branch.trim(),
                        revision.trim(),
                        path.trim(),
                        enabled,
                        r + 1,
                        splitSemicolonList(generatedJars),
                        splitSemicolonList(applicationPackages),
                        ownership,
                        trunkValidated.isBlank() ? null : isEnabled(trunkValidated)));
            }
        }

        return components;
    }

    private static Map<String, Integer> normalizeHeaders(List<String> headers) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String normalized = normalize(headers.get(i));
            if (!normalized.isBlank()) columns.putIfAbsent(normalized, i);
        }
        return columns;
    }

    private static int required(Map<String, Integer> columns, String... names) {
        int idx = optional(columns, names);
        if (idx < 0) {
            throw new IllegalArgumentException("Source inventory must contain columns: Component and Repository");
        }
        return idx;
    }

    private static int optional(Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer idx = columns.get(normalize(name));
            if (idx != null) return idx;
        }
        return -1;
    }

    private static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String value(Row row, int column) {
        if (column < 0) return "";
        return ExcelUtils.getCellValueAsString(row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
    }

    private static boolean isEnabled(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("false") || v.equals("no") || v.equals("n") || v.equals("0") || v.equals("disabled") || v.equals("skip"));
    }

    private static List<String> splitSemicolonList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[;,\\r\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
