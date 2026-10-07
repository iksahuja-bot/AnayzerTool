package effortanalyzer.source;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceInventoryReaderTest {

    @Test
    void readsWorkbookAndHonorsDisabledRows(@TempDir Path tmpDir) throws Exception {
        Path workbook = tmpDir.resolve("components.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Component");
            header.createCell(1).setCellValue("Repository");
            header.createCell(2).setCellValue("Trunk");
            header.createCell(3).setCellValue("Type");
            header.createCell(4).setCellValue("Branch");
            header.createCell(5).setCellValue("Revision");
            header.createCell(6).setCellValue("Path");
            header.createCell(7).setCellValue("Enabled");
            header.createCell(8).setCellValue("Generated JARs");
            header.createCell(9).setCellValue("Application Packages");
            header.createCell(10).setCellValue("Ownership");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Enabled Component");
            row.createCell(1).setCellValue("https://example.com/repo.git");
            row.createCell(2).setCellValue("https://example.com/repo/trunk");
            row.createCell(3).setCellValue("git");
            row.createCell(4).setCellValue("main");
            row.createCell(6).setCellValue("src");
            row.createCell(7).setCellValue("yes");
            row.createCell(8).setCellValue("target/app-core.jar; target/app-api.jar");
            row.createCell(9).setCellValue("com.example;org.example");
            row.createCell(10).setCellValue("application code");

            Row disabled = sheet.createRow(2);
            disabled.createCell(0).setCellValue("Disabled Component");
            disabled.createCell(1).setCellValue("https://svn.example.com/repo");
            disabled.createCell(7).setCellValue("false");

            try (FileOutputStream out = new FileOutputStream(workbook.toFile())) {
                wb.write(out);
            }
        }

        List<SourceComponent> components = new SourceInventoryReader().read(workbook);
        assertEquals(2, components.size());
        assertEquals(RepositoryType.GIT, components.get(0).type());
        assertEquals("https://example.com/repo/trunk", components.get(0).trunk());
        assertEquals("main", components.get(0).branch());
        assertEquals("src", components.get(0).path());
        assertEquals(List.of("target/app-core.jar", "target/app-api.jar"), components.get(0).generatedJars());
        assertEquals(List.of("com.example", "org.example"), components.get(0).applicationPackages());
        assertEquals("application code", components.get(0).ownership());
        assertTrue(components.get(0).enabled());
        assertFalse(components.get(1).enabled());
    }
}
