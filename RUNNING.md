# EffortAnalyzer — Running Instructions

> **Version:** 2.0.0  
> **Requires:** Java 21+  
> **Built with:** Apache Maven 3.8+

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Building the Project](#2-building-the-project)
3. [IBM Scanner Setup (upgrade module)](#3-ibm-scanner-setup-upgrade-module)
4. [Running on Windows](#4-running-on-windows)
5. [Running on Unix / Linux / macOS](#5-running-on-unix--linux--macos)
6. [Direct Java Invocation (any platform)](#6-direct-java-invocation-any-platform)
7. [Module Reference](#7-module-reference)
8. [How to Read the Reports](#8-how-to-read-the-reports)
9. [Adding Custom Rules](#9-adding-custom-rules)
10. [Excluding Rules](#10-excluding-rules)
11. [Configuration File](#11-configuration-file)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Prerequisites

| Requirement | Minimum | Notes |
|-------------|---------|-------|
| Java        | 21      | OpenJDK or Oracle JDK — must be on `PATH` or `JAVA_HOME` set |
| Maven       | 3.8     | Required only if building from source |

### Check your Java version

**Windows (Command Prompt or PowerShell):**
```bat
java -version
```

**Unix / Linux / macOS:**
```bash
java -version
```

Expected output (minimum):
```
java version "21.x.x" ...
```

---

## 2. Building the Project

Run the following command from the project root (where `pom.xml` is located):

```bash
mvn clean package -DskipTests
```

If your environment uses a local Maven settings file (e.g. to override corporate repositories):
```bash
mvn clean package -DskipTests -s settings-local.xml
```

The fat (shaded) JAR is produced at:
```
target/EffortAnalyzer-2.0.0-shaded.jar
```

Copy this JAR plus `run.bat` / `run.ps1` / `run.sh` and the resource files to your
deployment folder.

---

## 3. IBM Scanner Setup (upgrade module)

The `upgrade` module uses the **IBM Migration Toolkit for Application Binaries**
(`binaryAppScanner.jar`) for Java 8 → 21 compatibility analysis. This is a free
tool provided by IBM and must be downloaded separately.

### Step 1 — Download

1. Go to: <https://www.ibm.com/support/pages/migration-toolkit-application-binaries>
2. Download `binaryAppScanner.jar`.

### Step 2 — Place next to the EffortAnalyzer JAR

Copy `binaryAppScanner.jar` into the **same folder** as `EffortAnalyzer-2.0.0-shaded.jar`.
No configuration is needed — the tool is auto-detected at startup.

```
EffortAnalyzer-Copy\
├── EffortAnalyzer-2.0.0-shaded.jar   ← EffortAnalyzer
├── binaryAppScanner.jar               ← IBM scanner (download & place here)
├── run.bat
├── run.ps1
├── run.sh
├── upgrade-excluded-rules.txt
├── analyzer.properties
└── reports\                           ← auto-created before each upgrade run
    ├── MyApp-ejb.json                    IBM WAMT JSON output (kept for reference)
    └── MyApp-web.json
```

> The `reports\` folder is **created automatically** the first time you run the `upgrade`
> module. Before every subsequent run it is **cleaned** so that stale JSON files from a
> previous scan never mix with new results. The JSON files are kept after the run — you
> can open them to inspect the raw IBM WAMT findings or re-run analysis at any time.

### Step 3 — (Optional) Override the path

If the scanner is in a different location, pass it explicitly:

```bat
:: Windows
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade --input=C:\apps\lib ^
     --ibm-scanner=C:\tools\binaryAppScanner.jar
```

```bash
# Unix
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade --input=/opt/app/lib \
     --ibm-scanner=/opt/tools/binaryAppScanner.jar
```

### What if the scanner is not found?

The `upgrade` module still runs gracefully:

- **Phase 1 (Java 21 compatibility)** is skipped — a clear warning appears in the console and in the report.
- **Phase 2 (library upgrade scan)** runs normally.
- The Excel report is generated — the `☕ Java 21 Issues` sheet shows instructions for downloading and placing the scanner.

---

## 4. Running on Windows

The `run.bat` launcher handles Java detection, argument validation, and provides an interactive menu.

### Interactive mode (recommended for first use)

Open a Command Prompt in the folder containing `run.bat` and the JAR:
```bat
run.bat
```

A numbered menu will appear. Select a module and follow the prompts.

### Command-line mode

```bat
run.bat <module> [input-path] [output-file]
```

| Module       | Input required | Default output file                     |
|--------------|:--------------:|-----------------------------------------|
| `upgrade`    | yes            | `Upgrade-Compatibility-Report.xlsx`     |
| `wl-jboss26` | yes            | `WlToJBoss-WildFly26-Report.xlsx`       |
| `wl-jboss27` | yes            | `WlToJBoss-WildFly27-Report.xlsx`       |
| `analyze`    | optional       | `AnalyzerOutput.xlsx`                   |
| `merge`      | no (see note)  | `MergedOutput.xlsx`                     |

> **upgrade module:** Place `binaryAppScanner.jar` next to the launcher scripts before running
> (see [Section 3](#3-ibm-scanner-setup-upgrade-module)). It is auto-detected automatically.

#### Examples

```bat
:: Java 21 + library upgrade scan (IBM scanner auto-detected from same folder)
run.bat upgrade  C:\apps\lib   Upgrade-Compatibility-Report.xlsx

:: WebLogic → WildFly 26 / JBoss EAP 7.4  (Java 8, javax.*)
run.bat wl-jboss26 C:\apps\lib  MigrationWF26.xlsx

:: WebLogic → WildFly 27+ / JBoss EAP 8   (Java 21, jakarta.*)
run.bat wl-jboss27 C:\apps\lib  MigrationWF27.xlsx

:: IBM Transformation Advisor analysis — external JSON reports directory
run.bat analyze  C:\reports\json   AnalysisOutput.xlsx

:: IBM Transformation Advisor analysis — uses embedded baseline reports in JAR
run.bat analyze                    AnalysisOutput.xlsx

:: Show help
run.bat help
```

### Configuring a custom Java home (Windows)

If Java is not on your PATH, open `run.bat` in a text editor and set:
```bat
set "DEFAULT_JAVA_HOME=C:\Program Files\Java\jdk-21"
```

---

## 5. Running on Unix / Linux / macOS

The `run.sh` launcher mirrors `run.bat`.

### Make the script executable (first time only)

```bash
chmod +x run.sh
```

### Interactive mode

```bash
./run.sh
```

### Command-line mode

```bash
./run.sh <module> [input-path] [output-file]
```

#### Examples

```bash
# Java 21 + library upgrade scan (IBM scanner auto-detected from same folder)
./run.sh upgrade   /opt/app/lib  Upgrade-Compatibility-Report.xlsx

# WebLogic → WildFly 26 / JBoss EAP 7.4  (Java 8, javax.*)
./run.sh wl-jboss26 /opt/app/lib  MigrationWF26.xlsx

# WebLogic → WildFly 27+ / JBoss EAP 8   (Java 21, jakarta.*)
./run.sh wl-jboss27 /opt/app/lib  MigrationWF27.xlsx

# IBM Transformation Advisor analysis — external JSON reports directory
./run.sh analyze  /opt/reports/json  AnalysisOutput.xlsx

# IBM Transformation Advisor analysis — uses embedded baseline reports in JAR
./run.sh analyze                     AnalysisOutput.xlsx

# Show help
./run.sh help
```

### Configuring a custom Java home (Unix)

Open `run.sh` and set:
```bash
DEFAULT_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
```

Common Java 21 installation paths:

| Distribution    | Typical path                                                  |
|-----------------|---------------------------------------------------------------|
| OpenJDK (apt)   | `/usr/lib/jvm/java-21-openjdk-amd64`                          |
| Eclipse Temurin | `/usr/lib/jvm/temurin-21`                                     |
| SDKMAN          | `~/.sdkman/candidates/java/21.x.x-tem/`                       |
| Homebrew (macOS)| `/opt/homebrew/opt/openjdk@21`                                |
| macOS system    | `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`  |

---

## 6. Direct Java Invocation (any platform)

```bash
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=<module> [--input=<path>] [--output=<file>]
```

#### Examples

```bash
# Upgrade: IBM scanner auto-detected from working directory
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=upgrade \
  --input=/opt/app/lib \
  --output=Upgrade-Compatibility-Report.xlsx

# Upgrade: explicit IBM scanner path
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=upgrade \
  --input=/opt/app/lib \
  --ibm-scanner=/opt/tools/binaryAppScanner.jar \
  --output=Upgrade-Compatibility-Report.xlsx

# Upgrade: supply a list of JAR files instead of a directory
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=upgrade \
  --jar-list=jars.txt \
  --output=Upgrade-Compatibility-Report.xlsx

# WebLogic → WildFly 26 migration
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=wl-jboss26 \
  --input=/opt/app/lib \
  --output=MigrationWF26.xlsx

# WebLogic → WildFly 27+ migration
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=wl-jboss27 \
  --input=/opt/app/lib \
  --output=MigrationWF27.xlsx

# IBM Transformation Advisor analysis — external JSON folder
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=analyze \
  --input=/opt/reports/json \
  --output=AnalysisOutput.xlsx

# IBM Transformation Advisor analysis — embedded baseline reports
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=analyze \
  --output=AnalysisOutput.xlsx

# Excel merger
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=merge \
  --ticket-file=TicketReport.xlsx \
  --component-file=ComponentList.xlsx \
  --output=MergedOutput.xlsx
```

---

## 7. Module Reference

### `upgrade` — Java 21 + Library Upgrade Compatibility Analyzer

Runs two phases in a single pass and writes one combined **6-sheet Excel report**.

---

#### Phase 1 — Java 21 JVM Compatibility (IBM Migration Toolkit)

The IBM `binaryAppScanner.jar` is invoked automatically on your input JARs:

```
java -jar binaryAppScanner.jar <input>
     --analyzeJavaSE --sourceJava=oracle8 --targetJava=java21
     --format=json --output=<jar-folder>/reports
```

The JSON output files are written to the **`reports/` folder next to the EffortAnalyzer JAR**,
then parsed and filtered by the exclusion list, and the findings are written to the Excel report.

**`reports/` folder lifecycle:**

| Event | What happens |
|-------|-------------|
| First run | `reports/` is created automatically |
| Every run | `reports/` is cleaned before the IBM scanner runs (stale JSONs removed) |
| After run | JSON files are **kept** for reference — open them to inspect raw IBM WAMT output |

> **Prerequisite:** download `binaryAppScanner.jar` from IBM and place it next to the
> EffortAnalyzer JAR. See [Section 3](#3-ibm-scanner-setup-upgrade-module).

---

#### Phase 2 — Library Upgrade Compatibility (built-in)

| Library          | Target version | Migration scope |
|------------------|----------------|-----------------|
| Spring Framework | 5.3.39         | 3.x / 4.x / 5.x → 5.3.39 |
| Guava            | 31.1-jre       | Any prior version → 31.1 |
| Guice            | 5.1.0          | 3.x / 4.x → 5.1.0 |
| Jersey           | 2.22.2         | 1.x → 2.x (complete rewrite: `com.sun.jersey` → `org.glassfish.jersey`) |
| CGLib            | → ByteBuddy    | Any CGLib usage → ByteBuddy |

---

#### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `--input=<path>` | yes* | JAR/WAR/EAR file or directory of archives |
| `--jar-list=<file>` | yes* | Text file with one JAR path per line (alternative to `--input`) |
| `--ibm-scanner=<path>` | no | Path to `binaryAppScanner.jar` (default: auto-detect from JAR folder) |
| `--output=<file>` | no | Output Excel file (default: `Upgrade-Compatibility-Report.xlsx`) |

*One of `--input` or `--jar-list` is required.

#### Exclusions

Edit `upgrade-excluded-rules.txt` (see [Section 10](#10-excluding-rules)).

**16 IBM TA informational rules are always excluded** by default — the same set as the
standalone `analyze` module — in addition to any entries in `upgrade-excluded-rules.txt`.

```bat
:: Windows — IBM scanner auto-detected
run.bat upgrade  C:\apps\lib   Upgrade-Compatibility-Report.xlsx

:: Windows — explicit IBM scanner path
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade --input=C:\apps\lib ^
     --ibm-scanner=C:\tools\binaryAppScanner.jar

:: Unix
./run.sh upgrade /opt/app/lib  Upgrade-Compatibility-Report.xlsx
```

---

### `wl-jboss26` — WebLogic → WildFly 26 / JBoss EAP 7.4

Scans JARs, WARs, and EARs for patterns that need attention when migrating
from WebLogic to **WildFly 26 / JBoss EAP 7.4**.

| Target attribute | Value        |
|------------------|--------------|
| Java version     | Java 8       |
| EE specification | Jakarta EE 8 |
| API namespace    | `javax.*`    |
| Namespace change | **Not required** |

Reports cover: WebLogic-specific APIs, EJB legacy patterns (CMP/BMP), JNDI
naming, JMS configuration, classloading, and third-party library compatibility.

**Required argument:** `--input=<path>` — path to a JAR/WAR/EAR or directory

---

### `wl-jboss27` — WebLogic → WildFly 27+ / JBoss EAP 8

Scans JARs, WARs, and EARs for patterns that need attention when migrating
from WebLogic to **WildFly 27+ / JBoss EAP 8**.

| Target attribute | Value         |
|------------------|---------------|
| Java version     | Java 21       |
| EE specification | Jakarta EE 10 |
| API namespace    | `jakarta.*`   |
| Namespace change | **Required**  |

Reports cover: all `wl-jboss26` topics plus `javax.*` → `jakarta.*` namespace
migration and Java 21 incompatible APIs.

**Required argument:** `--input=<path>` — path to a JAR/WAR/EAR or directory

---

### `analyze` — IBM Transformation Advisor Report Analyzer

Reads IBM Transformation Advisor JSON analysis reports and produces a
consolidated, grouped Excel workbook. Findings are grouped by component and rule.

**`--input=<dir>`** (optional) — directory containing `*.json` IBM TA report files.  
If omitted, the bundled `reports/` folder inside the JAR is used.

**Exclusions:** 16 informational IBM TA rules are excluded by default.
Override with `--excluded-rules=Rule1,Rule2` or configure in `analyzer.properties`.

```bat
:: External JSON reports directory
run.bat analyze  C:\reports\json   AnalysisOutput.xlsx

:: Embedded baseline reports
run.bat analyze                    AnalysisOutput.xlsx
```

---

### `merge` — Excel Merger

Merges a JIRA ticket report spreadsheet with a component list spreadsheet,
producing a combined output Excel file.

**Arguments:** `--ticket-file=<file>` and `--component-file=<file>`

---

## 8. How to Read the Reports

---

### All modules — Severity colour coding

Every finding is colour-coded by severity:

| Colour | Severity | Meaning |
|--------|----------|---------|
| 🟠 Orange | **HIGH** | Will fail at runtime — fix before deployment |
| 🟡 Yellow | **MEDIUM** | Deprecated or behaviorally changed — plan to fix |
| 🟢 Green | **LOW** | Informational or soft-deprecated — low risk, review when convenient |

Fix all **HIGH** items first. These APIs have been removed from Java 21 or the
target library version and will cause runtime failures.

---

### `upgrade` module — 6-sheet report

The report opens to the **📋 Instructions** sheet, which contains a complete
in-report guide. Read it first before looking at the findings.
Every other sheet has a light-blue instruction bar at the top.

| Sheet | What to do here |
|---|---|
| **📋 Instructions** | Read once. Explains every sheet, severity guide, migration steps, and how to manage exclusions. |
| **📊 Summary** | Triage. Find components with the most HIGH issues. Sort by the Priority column to know where to start. |
| **☕ Java 21 Issues (IBM)** | Fix HIGH rules first. Each row has an IBM WAMT Rule ID — search it at [ibm.com/docs/en/wamt](https://www.ibm.com/docs/en/wamt) for code-before/after migration guidance. The `Next Steps` column gives a direct search hint. |
| **📦 Library Issues** | Simple substitutions. The `Replacement API` and `What to Do` columns tell you exactly what to change. |
| **✅ Remediation Checklist** | Your migration task list. One row = one unique fix (deduplicated). Sorted by severity. Mark `Done?` as you complete each fix. Share with your team to coordinate work. |
| **🚫 Excluded Rules** | Reference. Shows every rule that was filtered out, with the reason. Edit `upgrade-excluded-rules.txt` to re-enable or add exclusions. |

**IBM Rule IDs** in the `☕ Java 21 Issues` sheet are IBM WAMT rule names
(e.g. `FinalizationDeprecated`, `RemovedSunAPIs`, `DetectThreadStop`).  
Search any Rule ID at <https://www.ibm.com/docs/en/wamt> for detailed code examples.

**Default exclusions:** 16 IBM TA informational rules are always excluded.
The `🚫 Excluded Rules` sheet lists them all with human-readable explanations.

---

### `wl-jboss26` / `wl-jboss27` module report sheets

| Sheet | Purpose |
|---|---|
| **Summary** | Severity/category breakdown, per-JAR issue counts, note on excluded WebLogic stubs |
| **All Findings** | Full detail per JAR: Source column (green = developer code, grey = WL-generated stub), API pattern, severity, remediation |
| **JAR Inventory** | Per-JAR class count, issue count, generated stub count |
| **Checklist** | Deduplicated action list. `☐` = action needed, `—` = WL-generated stub (informational only) |
| **Migration Playbook** | Step-by-step numbered guides for complex migrations: HomeInterfaceHelper, jboss-ejb3.xml setup, Platform.jndi(), security, javax→jakarta, classloading |

**Green "Developer code" rows** in All Findings and Checklist are real issues that
need developer attention.

**Grey "WL appc-generated" rows** are WebLogic-generated EJB stubs detected in
JARs. These are automatically excluded from the count because they are not
hand-written code and will not be present in a WildFly deployment.

---

## 9. Adding Custom Rules

### Custom library rules (`upgrade` module — Spring / library side)

Place a file named `upgrade-compatibility-rules.txt` next to the JAR.
Format (pipe-delimited, one rule per line):

```
library|className|methodName|severity|replacement|description
```

---

### `wl-jboss` module custom rules

Place `wl-jboss-custom-rules.txt` next to the JAR. Format (pipe-delimited):

```
CATEGORY|apiPattern|SEVERITY|description|remediation
```

This file already contains pre-populated Kernel project-specific patterns
(`HomeInterfaceHelper`, `RemoteHelper.narrow`, etc.).

---

## 10. Excluding Rules

The `upgrade` module supports an exclusion file that suppresses findings you
have already addressed or intentionally accept.

**File:** `upgrade-excluded-rules.txt` — place next to the JAR.

### How exclusions are applied

Two layers of exclusions are always active:

1. **16 IBM TA defaults** (hardcoded) — the same informational rules excluded by the
   standalone `analyze` module (e.g. `CLDRLocaleDataByDefault`, `RunJDeps`,
   `Java21GeneralInfoAndPotentialIssues`). These cannot be re-enabled from the file.

2. **`upgrade-excluded-rules.txt`** — user-editable file for additional exclusions.

The `🚫 Excluded Rules` sheet in the report lists every excluded rule with its reason.

### Supported exclusion formats

| Format | Example | Effect |
|--------|---------|--------|
| IBM WAMT rule name | `RemovedJaxBModuleNotProvided` | Suppresses that IBM rule |
| Spring library name | `Guava 31.1-jre` | Suppresses all Guava library findings |
| Class prefix | `org.springframework.remoting.jaxrpc` | Suppresses a specific Spring class |

### How to use the exclusion file

1. Open `upgrade-excluded-rules.txt` in any text editor.
2. Add the rule ID or IBM WAMT rule name on a new line.
3. Lines starting with `#` are comments.
4. Re-run the tool — that rule will no longer appear in the report.

**Example — suppress after confirming JAXB dependency is added to `pom.xml`:**

```
# Add this line to exclude the JAXB removal finding:
RemovedJaxBModuleNotProvided
```

---

## 11. Configuration File

An `analyzer.properties` file can be placed next to the JAR to set default
values for all arguments. CLI arguments always take priority.

```properties
# Active module (can be overridden with --module=)
module=upgrade

# Input path
input.path=/opt/app/lib

# IBM scanner path (upgrade module; auto-detected if blank)
# analyzer.ibm.scanner.jar=/opt/tools/binaryAppScanner.jar

# Output file
output.file=Upgrade-Compatibility-Report.xlsx
```

See the bundled `analyzer.properties` for all available keys with descriptions.

---

## 12. Troubleshooting

### IBM scanner not found

```
[upgrade] IBM scanner not found (binaryAppScanner.jar).
          Download from: https://www.ibm.com/support/pages/migration-toolkit-application-binaries
          Place it next to EffortAnalyzer-2.0.0-shaded.jar to enable Java 21 scan.
```

- Download `binaryAppScanner.jar` from IBM (free, requires IBM account).
- Place it in the **same folder** as `EffortAnalyzer-2.0.0-shaded.jar`.
- Re-run the tool — it is auto-detected automatically.
- Alternatively, pass `--ibm-scanner=<path>` to specify an explicit location.

---

### Java 21 Issues sheet is empty / shows "scanner not found"

The IBM scanner was not found at runtime. See the troubleshooting entry above.

---

### Java not found

```
[ERR] Java not found.
```

- Verify Java 21+ is installed: `java -version`
- Add Java to your `PATH`, or set `JAVA_HOME`, or configure `DEFAULT_JAVA_HOME`
  inside the launcher script.

---

### JAR not found

```
[ERR] JAR not found: .../EffortAnalyzer-2.0.0-shaded.jar
```

- Build the project: `mvn clean package -DskipTests -s settings-local.xml`
- Make sure you are running the launcher from the same folder as the JAR.

---

### Report not created — access denied

```
ERROR ... Output file exists but cannot be overwritten
```

- Close the output file in Excel or any other application.
- Remove the read-only attribute from the file, or choose a different output filename.

---

### Report not created — directory missing

```
ERROR ... Cannot create output directory
```

- Verify you have write permission on the target directory.
- Create the directory manually, or choose an output path that already exists.

---

### Exit code 9009 (Windows)

This code means Windows could not find a command. Check that:
- `run.bat` has not been modified to include characters that CMD misinterprets.
- The `JAVA_EXE` path has no trailing spaces or invisible characters.

---

### Identical reports for `wl-jboss26` and `wl-jboss27`

Use the **separate module names** `wl-jboss26` and `wl-jboss27` on the
command line. Do not use the legacy `wl-jboss` module name — it defaults to
the WildFly 27 profile.

```bat
:: Correct
run.bat wl-jboss26 C:\apps\lib MigrationWF26.xlsx
run.bat wl-jboss27 C:\apps\lib MigrationWF27.xlsx
```

---

### Unknown module error

```
Unknown module: 'spring'
```

The `spring` and `java21` modules have been consolidated into the `upgrade` module.

```bat
:: Old (no longer works)
run.bat spring  C:\apps\lib

:: New
run.bat upgrade C:\apps\lib
```
