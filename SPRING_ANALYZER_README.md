# Spring 5.3.x Deprecation Analyzer

A comprehensive tool to analyze your JAR files and identify deprecated or removed Spring Framework 5.3.x APIs that need migration.

## Overview

This analyzer scans your application JARs to find:
- **Deprecated APIs** that will be removed in future Spring versions
- **Removed APIs** that will cause runtime failures
- **Package relocations** and class renames
- **Method signature changes** that may break your code

## Quick Start

### Option 1: Using the Batch Script (Windows)

```bash
# Analyze a single JAR
analyze-spring-jars.bat myapp.jar

# Analyze all JARs in a directory
analyze-spring-jars.bat C:\projects\lib\

# Specify custom output file
analyze-spring-jars.bat myapp.jar custom-report.xlsx
```

### Option 2: Using the Shell Script (Linux/Mac)

```bash
# Make script executable
chmod +x analyze-spring-jars.sh

# Analyze a single JAR
./analyze-spring-jars.sh myapp.jar

# Analyze all JARs in a directory
./analyze-spring-jars.sh /projects/lib/

# Specify custom output file
./analyze-spring-jars.sh myapp.jar custom-report.xlsx
```

### Option 3: Direct Java Execution

```bash
# Build the project first
mvn clean package

# Run the analyzer
java -cp target/EffortAnalyzer-2.0.0.jar \
  effortanalyzer.spring.SpringDeprecationAnalyzer \
  myapp.jar SpringReport.xlsx
```

### Option 4: Using the Unified CLI

```bash
java -jar target/EffortAnalyzer-2.0.0.jar spring myapp.jar
```

## Output Report

The analyzer generates an Excel file with two sheets:

### 1. Spring Deprecations Sheet

Contains detailed findings with the following columns:

| Column | Description |
|--------|-------------|
| **JAR File** | Name of the JAR file containing the deprecated API usage |
| **File** | Source or class file where the usage was found |
| **Line** | Line number (if available from source files) |
| **Deprecated Class** | Fully qualified class name of the deprecated API |
| **Method** | Specific method name (if applicable) |
| **Severity** | CRITICAL, WARNING, or INFO |
| **Replacement** | Recommended replacement or migration path |
| **Description** | Explanation of why it's deprecated and impact |
| **Context** | Code snippet or context where it was found |

### 2. Summary Sheet

Provides high-level statistics:
- Total findings count
- Number of JARs analyzed
- Breakdown by severity level

## Severity Levels

- **CRITICAL**: API has been removed or will cause runtime failures. Immediate action required.
- **WARNING**: API is deprecated and will be removed in future versions. Plan migration.
- **INFO**: Best practice recommendation or informational notice.

## Detected Spring 5.3.x Changes

The analyzer includes rules for the following Spring Framework changes:

### Spring Web
- ✓ `RequestMethod` enum → Use `HttpMethod`
- ✓ JSONP support removal → Use CORS instead
- ✓ `MediaType.APPLICATION_JSON_UTF8` → Use `MediaType.APPLICATION_JSON`
- ✓ `RestTemplate` maintenance mode → Consider `WebClient`

### Spring Core
- ✓ `NestedRuntimeException` / `NestedCheckedException` → Use standard exception chaining
- ✓ `ReflectionUtils.getAllDeclaredMethods()` → Use alternatives

### Spring Security
- ✓ `WebSecurity.ignoring()` → Use `permitAll()`
- ✓ Direct filter instantiation → Use configuration

### Spring Boot
- ✓ `ResourceProperties` → Use `WebProperties.Resources`
- ✓ `ServerProperties` structure changes

### Spring Data
- ✓ `QueryMethod.getReturnedObjectType()` → Use `getReturnType()`

### Spring JDBC
- ✓ `JdbcTemplate.queryForObject()` behavior changes
- ✓ `SimpleJdbcInsert.compile()` deprecation

### Spring Batch
- ✓ `JobBuilderFactory` → Use `JobBuilder` directly
- ✓ `StepBuilderFactory` → Use `StepBuilder` directly

### And many more...

## Custom Rules

You can add your own deprecation rules by editing:

```
src/main/resources/spring-deprecation-rules.txt
```

### Rule Format

```
className|methodName|severity|replacement|description
```

**Example:**
```
com.mycompany.CustomComponent|oldMethod|WARNING|Use newMethod() instead|Custom deprecation
org.springframework.custom.OldClass||CRITICAL|Use NewClass instead|Class removed in Spring 5.3
```

**Fields:**
- `className`: Fully qualified class name (required)
- `methodName`: Specific method name (optional, leave empty for entire class)
- `severity`: CRITICAL, WARNING, or INFO (required)
- `replacement`: Migration guidance (required)
- `description`: Detailed explanation (optional)

## How It Works

The analyzer performs the following steps:

1. **JAR Discovery**: Finds all JAR files in the specified path
2. **Content Scanning**: Extracts and analyzes:
   - Java source files (`.java`) - for detailed line-by-line analysis
   - Compiled class files (`.class`) - for bytecode-level detection
3. **Pattern Matching**: Checks for:
   - Import statements
   - Class instantiations
   - Method invocations
   - Package references
4. **Report Generation**: Creates Excel report with findings and statistics

## Use Cases

### 1. Pre-Migration Assessment

Before upgrading to Spring 5.3.x or Spring Boot 2.4+:

```bash
# Analyze your application
./analyze-spring-jars.sh target/myapp.jar pre-migration-report.xlsx

# Review the report to understand migration effort
```

### 2. CI/CD Integration

Add to your build pipeline to catch deprecated API usage:

```yaml
# Example GitHub Actions
- name: Check Spring Deprecations
  run: |
    ./analyze-spring-jars.sh target/*.jar deprecation-report.xlsx
    # Fail build if CRITICAL issues found
```

### 3. Large Application Analysis

Analyze all libraries in your project:

```bash
# Analyze entire lib directory
./analyze-spring-jars.sh /path/to/project/lib/ full-analysis.xlsx
```

### 4. Continuous Monitoring

Run periodically to track deprecation debt:

```bash
# Weekly cron job
0 0 * * 0 /path/to/analyze-spring-jars.sh /apps/production/lib/ weekly-report.xlsx
```

## Limitations

- **Source Code Access**: Most accurate when source files are included in JARs. Class file analysis is less precise.
- **False Positives**: May detect some uses that aren't actually problematic (e.g., string literals containing class names).
- **Reflection**: Cannot detect APIs used via reflection or dynamic class loading.
- **Third-Party Libraries**: Only scans your JARs, not transitive dependencies.

## Best Practices

1. **Run Early**: Analyze before starting migration to understand scope
2. **Prioritize CRITICAL**: Address critical issues first
3. **Review Context**: Check the "Context" column to understand actual usage
4. **Update Rules**: Add custom rules for your organization's deprecated APIs
5. **Track Progress**: Re-run analysis after fixes to measure progress

## Troubleshooting

### No Findings Reported

- Ensure you're analyzing the correct JARs
- Check if JARs contain source files (`.java`) for best results
- Verify Spring Framework is actually used in the application

### Build Fails

```bash
# Ensure JAVA_HOME is set
echo %JAVA_HOME%  # Windows
echo $JAVA_HOME   # Linux/Mac

# Ensure Maven is installed
mvn --version
```

### Memory Issues with Large JARs

```bash
# Increase Java heap size
java -Xmx2g -cp target/EffortAnalyzer-2.0.0.jar \
  effortanalyzer.spring.SpringDeprecationAnalyzer \
  large-app.jar
```

## Examples

### Example 1: Single Application JAR

```bash
./analyze-spring-jars.sh myapp-1.0.0.jar
```

**Output**: `SpringDeprecationReport.xlsx` with all deprecated API usages

### Example 2: Multiple JARs in Directory

```bash
./analyze-spring-jars.sh /opt/apps/myapp/lib/ myapp-spring-analysis.xlsx
```

**Output**: `myapp-spring-analysis.xlsx` with findings from all JARs

### Example 3: Integration with Maven

```xml
<!-- Add to pom.xml -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>spring-deprecation-check</id>
            <phase>verify</phase>
            <goals>
                <goal>java</goal>
            </goals>
            <configuration>
                <mainClass>effortanalyzer.spring.SpringDeprecationAnalyzer</mainClass>
                <arguments>
                    <argument>${project.build.directory}/${project.build.finalName}.jar</argument>
                    <argument>spring-deprecation-report.xlsx</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Run with: `mvn verify`

## Support

For issues or questions:
1. Check the logs in `logs/effortanalyzer.log`
2. Review the generated Excel report's Summary sheet
3. Verify your Spring Framework version compatibility

## License

Part of the EffortAnalyzer project.
