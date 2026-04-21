# EffortAnalyzer Examples

This document provides practical examples for using the EffortAnalyzer tools.

## Spring Deprecation Analyzer Examples

### Example 1: Analyze a Single Application JAR

**Scenario:** You have a Spring Boot application JAR and want to check for deprecated APIs before upgrading to Spring 5.3.x.

```bash
# Windows
analyze-spring-jars.bat C:\projects\myapp\target\myapp-1.0.0.jar

# Linux/Mac
./analyze-spring-jars.sh /projects/myapp/target/myapp-1.0.0.jar
```

**Expected Output:**
```
================================================
Spring 5.3.x Deprecation Analyzer
================================================

Target: myapp-1.0.0.jar
Output: SpringDeprecationReport.xlsx

Running analysis...
Found 1 JAR files to analyze
Analyzing: myapp-1.0.0.jar
Analysis complete. Total findings: 15

================================================
Analysis complete!
Report saved to: SpringDeprecationReport.xlsx
================================================
```

**Report Contents:**
- 15 findings across various classes
- Severity breakdown: 3 CRITICAL, 8 WARNING, 4 INFO
- Recommendations for each deprecated API

---

### Example 2: Analyze All JARs in a Directory

**Scenario:** Your application has multiple modules, and you want to analyze all JARs at once.

```bash
# Analyze entire lib directory
./analyze-spring-jars.sh /opt/apps/myapp/lib/ comprehensive-report.xlsx
```

**Directory Structure:**
```
/opt/apps/myapp/lib/
├── myapp-core-1.0.0.jar
├── myapp-web-1.0.0.jar
├── myapp-data-1.0.0.jar
└── myapp-security-1.0.0.jar
```

**Expected Output:**
```
Found 4 JAR files to analyze
Analyzing: myapp-core-1.0.0.jar
Analyzing: myapp-web-1.0.0.jar
Analyzing: myapp-data-1.0.0.jar
Analyzing: myapp-security-1.0.0.jar
Analysis complete. Total findings: 42
```

---

### Example 3: Custom Rules for Organization-Specific Deprecations

**Scenario:** Your organization has internal Spring utilities that are being deprecated.

**Step 1:** Edit `src/main/resources/spring-deprecation-rules.txt`

```
# Custom company rules
com.mycompany.spring.util.LegacyRestClient||CRITICAL|Use com.mycompany.spring.util.ModernRestClient|Legacy client removed
com.mycompany.spring.security.OldAuthFilter||WARNING|Use Spring Security 5.x filters|Custom filter deprecated
com.mycompany.spring.data.CustomRepository|findByOldMethod|WARNING|Use findByNewMethod()|Method signature changed
```

**Step 2:** Rebuild and run

```bash
mvn clean package
./analyze-spring-jars.sh myapp.jar custom-analysis.xlsx
```

**Result:** Report includes both standard Spring deprecations AND your custom rules.

---

### Example 4: CI/CD Pipeline Integration

**Scenario:** Automatically check for deprecated APIs in your build pipeline.

**GitHub Actions Example:**

```yaml
name: Spring Deprecation Check

on: [push, pull_request]

jobs:
  deprecation-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Build application
        run: mvn clean package -DskipTests
      
      - name: Run Spring Deprecation Analyzer
        run: |
          cd EffortAnalyzer
          mvn clean package -DskipTests
          ./analyze-spring-jars.sh ../target/*.jar deprecation-report.xlsx
      
      - name: Upload Report
        uses: actions/upload-artifact@v3
        with:
          name: spring-deprecation-report
          path: EffortAnalyzer/deprecation-report.xlsx
      
      - name: Check for Critical Issues
        run: |
          # Parse Excel and fail if CRITICAL issues found
          # (requires additional scripting)
          echo "Review the report for CRITICAL issues"
```

**Jenkins Pipeline Example:**

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }
        stage('Spring Deprecation Analysis') {
            steps {
                dir('EffortAnalyzer') {
                    sh 'mvn clean package -DskipTests'
                    sh './analyze-spring-jars.sh ../target/*.jar deprecation-report.xlsx'
                }
            }
        }
        stage('Archive Report') {
            steps {
                archiveArtifacts artifacts: 'EffortAnalyzer/deprecation-report.xlsx'
            }
        }
    }
}
```

---

### Example 5: Scheduled Analysis for Continuous Monitoring

**Scenario:** Run weekly analysis to track deprecation debt over time.

**Cron Job (Linux):**

```bash
# Edit crontab
crontab -e

# Add weekly job (every Sunday at 2 AM)
0 2 * * 0 /opt/scripts/weekly-spring-analysis.sh
```

**Script: `/opt/scripts/weekly-spring-analysis.sh`**

```bash
#!/bin/bash
DATE=$(date +%Y-%m-%d)
APP_DIR="/opt/apps/production"
REPORT_DIR="/opt/reports/spring-deprecations"

cd /opt/EffortAnalyzer

# Analyze production JARs
./analyze-spring-jars.sh "$APP_DIR/lib/" "$REPORT_DIR/report-$DATE.xlsx"

# Keep last 12 weeks of reports
find "$REPORT_DIR" -name "report-*.xlsx" -mtime +84 -delete

# Send notification
echo "Spring deprecation analysis complete for $DATE" | mail -s "Weekly Spring Analysis" team@company.com
```

---

## Migration Report Analyzer Examples

### Example 6: Analyze IBM Transformation Advisor Reports

**Scenario:** You have 200+ JSON reports from IBM Transformation Advisor.

**Step 1:** Place reports in resources folder

```
src/main/resources/reports/
├── app1.jar_AnalysisReport.json
├── app2.jar_AnalysisReport.json
├── app3.jar_AnalysisReport.json
└── ... (200+ files)
```

**Step 2:** Configure (optional)

Edit `analyzer.properties`:
```properties
analyzer.resource.folder=reports
analyzer.output.file=TransformationAnalysis.xlsx
analyzer.parallel.enabled=true
```

**Step 3:** Run analyzer

```bash
java -jar target/EffortAnalyzer-2.0.0.jar analyze
```

**Output:**
```
Starting EffortAnalyzer...
Configuration loaded - Resource folder: reports, Output: TransformationAnalysis.xlsx, Parallel: true
Found 216 JSON files to process
Processing complete. Total grouped results: 1,847
Excel output generated: TransformationAnalysis.xlsx
Analysis completed in 8,432 ms
```

**Performance:** With parallel processing, 216 files analyzed in ~8 seconds!

---

### Example 7: Exclude Informational Rules

**Scenario:** Focus only on critical migration issues by excluding informational rules.

Edit `analyzer.properties`:
```properties
# Exclude all Java version info rules
analyzer.excluded.rules=Java11GeneralInfoAndPotentialIssues,Java17GeneralInfoAndPotentialIssues,Java21GeneralInfoAndPotentialIssues,CLDRLocaleDataByDefault,RunJDeps
```

**Result:** Report contains only actionable migration issues.

---

## Ticket-Component Merger Examples

### Example 8: Basic Ticket Merge

**Scenario:** Merge JIRA tickets with component ownership data.

**Input Files:**

`TicketReport.xlsx`:
| Key | Summary | Component/s | Time Spent | Status |
|-----|---------|-------------|------------|--------|
| PROJ-123 | Fix login bug | Auth, Security | 14400 | Done |
| PROJ-124 | Update API | API Gateway | 7200 | In Progress |

`ComponentList.xlsx`:
| Name | Pack | Owner |
|------|------|-------|
| Auth | Pack-A | Team-1 |
| Security | Pack-A | Team-1 |
| API Gateway | Pack-B | Team-2 |

**Run:**
```bash
java -jar target/EffortAnalyzer-2.0.0.jar merge
```

**Output: `MergedOutput.xlsx`**
| Component | Key | Summary | Time Spent (Hours) | Pack | Status |
|-----------|-----|---------|-------------------|------|--------|
| Auth | PROJ-123 | Fix login bug | 4.00 | Pack-A | Done |
| Security | PROJ-123 | Fix login bug | 4.00 | Pack-A | Done |
| API Gateway | PROJ-124 | Update API | 2.00 | Pack-B | In Progress |

**Note:** PROJ-123 appears twice (once per component) for accurate effort tracking per component.

---

### Example 9: Custom File Names

**Scenario:** Use custom input/output file names.

```bash
java -jar target/EffortAnalyzer-2.0.0.jar merge \
  sprint-tickets.xlsx \
  team-components.xlsx \
  sprint-effort-report.xlsx
```

---

## Combined Workflow Example

### Example 10: Complete Migration Analysis Workflow

**Scenario:** End-to-end migration analysis for a Spring Boot application.

**Step 1: Analyze Spring Deprecations**
```bash
./analyze-spring-jars.sh target/myapp.jar spring-deprecations.xlsx
```

**Step 2: Analyze Transformation Reports**
```bash
# Place IBM TA reports in resources/reports/
java -jar target/EffortAnalyzer-2.0.0.jar analyze
```

**Step 3: Track Effort with Tickets**
```bash
# Export JIRA tickets to TicketReport.xlsx
# Create ComponentList.xlsx with component-to-pack mapping
java -jar target/EffortAnalyzer-2.0.0.jar merge
```

**Step 4: Review Reports**
- `spring-deprecations.xlsx` - Spring API changes needed
- `AnalyzerOutput.xlsx` - WebLogic to Liberty migration issues
- `MergedOutput.xlsx` - Effort tracking by component

**Step 5: Create Migration Plan**
Based on the three reports, prioritize:
1. CRITICAL Spring deprecations
2. High-severity transformation issues
3. Components with most effort required

---

## Advanced Usage

### Example 11: Programmatic Usage

**Scenario:** Integrate analyzer into your own Java application.

```java
import effortanalyzer.spring.SpringDeprecationAnalyzer;
import effortanalyzer.analyzer.ReportAnalyzer;
import effortanalyzer.config.AnalyzerConfig;

public class MyAnalysisApp {
    public static void main(String[] args) throws Exception {
        // Spring deprecation analysis
        SpringDeprecationAnalyzer springAnalyzer = new SpringDeprecationAnalyzer();
        springAnalyzer.analyze("myapp.jar");
        springAnalyzer.generateReport("spring-report.xlsx");
        
        // Migration report analysis
        AnalyzerConfig config = AnalyzerConfig.load();
        ReportAnalyzer reportAnalyzer = new ReportAnalyzer(config);
        reportAnalyzer.run();
        
        System.out.println("Analysis complete!");
    }
}
```

---

### Example 12: Docker Integration

**Scenario:** Run analyzer in a Docker container.

**Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy analyzer
COPY target/EffortAnalyzer-2.0.0.jar /app/
COPY analyze-spring-jars.sh /app/

# Copy JARs to analyze
COPY target/*.jar /app/jars/

RUN chmod +x analyze-spring-jars.sh

CMD ["./analyze-spring-jars.sh", "/app/jars/", "/app/reports/spring-analysis.xlsx"]
```

**Build and Run:**
```bash
docker build -t spring-analyzer .
docker run -v $(pwd)/reports:/app/reports spring-analyzer
```

---

## Tips and Best Practices

1. **Start with Spring Analyzer**: Run it first to understand API changes before migration
2. **Use Parallel Processing**: Enable for large datasets (200+ files)
3. **Customize Rules**: Add organization-specific deprecations
4. **Automate**: Integrate into CI/CD for continuous monitoring
5. **Track Over Time**: Run periodically to measure progress
6. **Review Context**: Always check the "Context" column for false positives
7. **Prioritize CRITICAL**: Focus on breaking changes first

---

## Troubleshooting Examples

### Issue: No Spring Deprecations Found

**Cause:** JARs don't contain source files

**Solution:** Include sources in your build
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-source-plugin</artifactId>
    <executions>
        <execution>
            <id>attach-sources</id>
            <goals>
                <goal>jar</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Issue: Out of Memory

**Cause:** Large JARs or many files

**Solution:** Increase heap
```bash
java -Xmx4g -jar target/EffortAnalyzer-2.0.0.jar spring large-app.jar
```

### Issue: False Positives

**Cause:** String literals or comments containing class names

**Solution:** Review "Context" column and filter manually
