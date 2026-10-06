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
            header.createCell(2).setCellValue("Type");
            header.createCell(3).setCellValue("Branch");
            header.createCell(4).setCellValue("Revision");
            header.createCell(5).setCellValue("Path");
            header.createCell(6).setCellValue("Enabled");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Enabled Component");
            row.createCell(1).setCellValue("https://example.com/repo.git");
            row.createCell(2).setCellValue("git");
            row.createCell(3).setCellValue("main");
            row.createCell(5).setCellValue("src");
            row.createCell(6).setCellValue("yes");

            Row disabled = sheet.createRow(2);
            disabled.createCell(0).setCellValue("Disabled Component");
            disabled.createCell(1).setCellValue("https://svn.example.com/repo");
            disabled.createCell(6).setCellValue("false");

            try (FileOutputStream out = new FileOutputStream(workbook.toFile())) {
                wb.write(out);
            }
        }

        List<SourceComponent> components = new SourceInventoryReader().read(workbook);
        assertEquals(2, components.size());
        assertEquals(RepositoryType.GIT, components.get(0).type());
        assertEquals("main", components.get(0).branch());
        assertEquals("src", components.get(0).path());
        assertTrue(components.get(0).enabled());
        assertFalse(components.get(1).enabled());
    }
}
