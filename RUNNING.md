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


| Requirement | Minimum | Notes                                                        |
| ----------- | ------- | ------------------------------------------------------------ |
| Java        | 21      | OpenJDK or Oracle JDK — must be on `PATH` or `JAVA_HOME` set |
| Maven       | 3.8     | Required only if building from source                        |
| SVN CLI     | Any current 1.x | Required only for `--source-inventory` rows with SVN repositories; verify with `svn --version` |
| Git CLI     | Any current version | Required only for `--source-inventory` rows with Git repositories; verify with `git --version` |




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

### Check SVN access for source inventory

If you will scan source from SVN, verify the SVN command-line client and your corporate network/session before running EffortAnalyzer:

```bat
svn --version
svn info https://your-svn-host/path/to/repository
```

For corporate SSO-backed SVN, have your SSO username and password ready. EffortAnalyzer can prompt once and pass them to SVN with `--prompt-credentials=true`. The checkout/update commands are executed as `svn ... --non-interactive --trust-server-cert`, so complete any first-time certificate trust, VPN, MFA, or browser-based SSO bootstrap with a manual `svn info`/`svn checkout` first if your environment requires it.

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

1. Go to: [https://www.ibm.com/support/pages/migration-toolkit-application-binaries](https://www.ibm.com/support/pages/migration-toolkit-application-binaries)
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
java -jar EffortAnalyzer-2.0.0.jar --module=upgrade --input=C:\apps\lib ^
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


| Module       | Input required | Default output file                 |
| ------------ | -------------- | ----------------------------------- |
| `upgrade`    | yes            | `Upgrade-Compatibility-Report.xlsx` |
| `wl15`       | yes            | `WL15-Migration-Report.xlsx`        |
| `wl14`       | yes            | `WL14-Migration-Report.xlsx`        |
| `wl-jboss26` | yes            | `WlToJBoss-WildFly26-Report.xlsx`   |
| `wl-jboss27` | yes            | `WlToJBoss-WildFly27-Report.xlsx`   |
| `analyze`    | optional       | `AnalyzerOutput.xlsx`               |
| `merge`      | no (see note)  | `MergedOutput.xlsx`                 |


> **upgrade module:** Place `binaryAppScanner.jar` next to the launcher scripts before running
> (see [Section 3](#3-ibm-scanner-setup-upgrade-module)). It is auto-detected automatically.



#### Examples

```bat
:: Java 21 + library upgrade scan (IBM scanner auto-detected from same folder)
run.bat upgrade  c:\Development\JBoss\Jars2\   UpgradeCompatibilityReport.xlsx

:: WebLogic 15 library migration scan
run.bat wl15     c:\Development\JBoss\Jars2\   WL15-Migration-Report.xlsx

:: WebLogic 12c → 14.1.2 library/API migration scan (IBM Java 21 check + general library upgrades + WL14-specific APIs)
run.bat wl14     c:\Development\JBoss\Jars2\   WL14-Migration-Report.xlsx

:: WebLogic → WildFly 26 / JBoss EAP 7.4  (Java 8, javax.*)
run.bat wl-jboss26 c:\Development\JBoss\Jars2\  MigrationWF26.xlsx

:: WebLogic → WildFly 27+ / JBoss EAP 8   (Java 21, jakarta.*)
run.bat wl-jboss27 c:\Development\JBoss\Jars2\  MigrationWF27.xlsx

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

# WebLogic 15 library migration scan
./run.sh wl15      /opt/app/lib  WL15-Migration-Report.xlsx
./run.sh wl14      /opt/app/lib  WL14-Migration-Report.xlsx

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


| Distribution     | Typical path                                                 |
| ---------------- | ------------------------------------------------------------ |
| OpenJDK (apt)    | `/usr/lib/jvm/java-21-openjdk-amd64`                         |
| Eclipse Temurin  | `/usr/lib/jvm/temurin-21`                                    |
| SDKMAN           | `~/.sdkman/candidates/java/21.x.x-tem/`                      |
| Homebrew (macOS) | `/opt/homebrew/opt/openjdk@21`                               |
| macOS system     | `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home` |


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

# WebLogic 15 library migration scan
java -jar EffortAnalyzer-2.0.0-shaded.jar \
  --module=wl15 \
  --input=/opt/app/lib \
  --output=WL15-Migration-Report.xlsx

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

The JSON output files are written to the `**reports/` folder next to the EffortAnalyzer JAR**,
then parsed and filtered by the exclusion list, and the findings are written to the Excel report.

`**reports/` folder lifecycle:**


| Event     | What happens                                                                     |
| --------- | -------------------------------------------------------------------------------- |
| First run | `reports/` is created automatically                                              |
| Every run | `reports/` is cleaned before the IBM scanner runs (stale JSONs removed)          |
| After run | JSON files are **kept** for reference — open them to inspect raw IBM WAMT output |


> **Prerequisite:** download `binaryAppScanner.jar` from IBM and place it next to the
> EffortAnalyzer JAR. See [Section 3](#3-ibm-scanner-setup-upgrade-module).

---



#### Phase 2 — Library Upgrade Compatibility (built-in)


| Library          | Target version | Migration scope                                                         |
| ---------------- | -------------- | ----------------------------------------------------------------------- |
| Spring Framework | 5.3.39         | 3.x / 4.x / 5.x → 5.3.39                                                |
| Guava            | 31.1-jre       | Any prior version → 31.1                                                |
| Guice            | 5.1.0          | 3.x / 4.x → 5.1.0                                                       |
| Jersey           | 2.22.2         | 1.x → 2.x (complete rewrite: `com.sun.jersey` → `org.glassfish.jersey`) |
| CGLib            | → ByteBuddy    | Any CGLib usage → ByteBuddy                                             |


---



#### Arguments


| Argument               | Required | Description                                                           |
| ---------------------- | -------- | --------------------------------------------------------------------- |
| `--input=<path>`       | yes*     | JAR/WAR/EAR file or directory of archives                             |
| `--jar-list=<file>`    | yes*     | Text file with one JAR path per line (alternative to `--input`)       |
| `--ibm-scanner=<path>` | no       | Path to `binaryAppScanner.jar` (default: auto-detect from JAR folder) |
| `--output=<file>`      | no       | Output Excel file (default: `Upgrade-Compatibility-Report.xlsx`)      |


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

---



### `wl15` — WebLogic 15 Library Migration

Scans JARs, WARs, and EARs for API compatibility issues across the library upgrade
set required for **WebLogic 15**. In addition, every bundled third-party library
(archive names and `WEB-INF/lib/*.jar` style nested archives, or exact coordinates
from embedded `META-INF/maven/.../pom.properties`) is version-checked against the
built-in **LibraryUpgradeList** target table (28 rows — every library with a
planned upgrade version) — results appear in the **🔢 Library Versions** sheet as
`OUTDATED` / `OK` rows.

#### Libraries and targets


| Library                                      | Target        | Key APIs flagged                                                                                                        |
| -------------------------------------------- | ------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Spring Framework                             | 6.2.11        | `org.springframework.remoting.*` (removed), `HandlerInterceptorAdapter` (removed), `CommonsMultipartResolver` (removed) |
| Spring Security                              | 6.5.9         | `WebSecurityConfigurerAdapter`, `antMatchers`/`mvcMatchers`, `authorizeRequests`, `@EnableGlobalMethodSecurity`         |
| Jackson                                      | 2.18.9        | `enableDefaultTyping()`, `DefaultTyping.EVERYTHING`, Joda-Time module                                                   |
| Netty                                        | 4.1.135.Final | `ChannelHandlerContext.attr()`, `userEventTriggered()`, `HttpHeaders.addHeader/setHeader/getHeader`                     |
| Log4j                                        | 2.25.4        | `org.apache.log4j` (log4j 1.x), `PatternLayout.createLayout()`, `ConsoleAppender.createAppender()`                      |
| Jetty                                        | 12.0.33       | `javax.servlet` package, `AbstractHandler`, `HandlerWrapper`, `HandlerList`, WebSocket API                              |
| JasperReports                                | 7.0.4         | `JRPdfExporter` (module split), `JRProperties`, `JasperExportManager` static methods                                    |
| EhCache                                      | 3.11.1        | Entire `net.sf.ehcache` package (EhCache 2 → 3 full rewrite to `org.ehcache`)                                           |
| commons-fileupload                           | 1.6.0         | `FileUpload`, `DiskFileItemFactory`, `ServletFileUpload`                                                                |
| commons-beanutils                            | 1.11.0        | `BeanUtils.populate`, `ConvertUtils.convert`                                                                            |
| hibernate-validator                          | 6.2.0         | `@NotEmpty`/`@NotBlank` in `org.hibernate.validator.constraints`                                                        |
| c3p0                                         | 0.12.0        | `ComboPooledDataSource`, `C3P0Registry`                                                                                 |
| MINA                                         | 2.0.28        | `IoHandlerAdapter`, `IoSession.write`                                                                                   |
| nimbus-jose-jwt                              | 9.37.2        | `JWTClaimsSet.parse(JSONObject)`, `SecurityContext`                                                                     |
| OWASP HTML Sanitizer                         | 20280101.1    | `PolicyFactory`, `HtmlPolicyBuilder`                                                                                    |
| lz4-java, neethi, commons-vfs2, assertj-core | various       | Minor API changes                                                                                                       |




#### Arguments


| Argument            | Required | Description                                                     |
| ------------------- | -------- | --------------------------------------------------------------- |
| `--input=<path>`    | yes*     | JAR/WAR/EAR file or directory of archives                       |
| `--jar-list=<file>` | yes*     | Text file with one JAR path per line (alternative to `--input`) |
| `--output=<file>`   | no       | Output Excel file (default: `WL15-Migration-Report.xlsx`)       |
| `--library-versions=<file>` | no | Custom `library-versions.properties` target table (overrides built-in targets; can add artifacts with `key=version:SEVERITY:Display` or remove with `disable.<artifact>=true`) |


*One of `--input` or `--jar-list` is required.

```bat
:: Windows
run.bat wl15  C:\apps\lib   WL15-Migration-Report.xlsx

:: Unix
./run.sh wl15 /opt/app/lib  WL15-Migration-Report.xlsx
```

---



### `wl14` — WebLogic 14 Library Migration

WebLogic 12c → 14.1.2 migration scan. **Java 21 compatibility is a prerequisite**
and is checked first with the IBM WAMT `binaryAppScanner.jar` (auto-detected; if
absent the sheet is empty and a download hint is shown). After the Java 21 check,
the scan runs the general library upgrade rules (Spring, Guava, Guice, Jersey,
CGLib) plus WL14-specific deprecated WebLogic API rules (T3StartupDef,
T3ShutdownDef, MessageLogger, TrustManager, HostnameVerifier), plus bundled-library
version checks. Produces an 8-sheet Excel report with the `☕ Java 21 Issues (IBM)`
and `🏛 WebLogic API Issues` sheets; the Library Issues sheet is grouped by
archive/component. All `wl15` arguments — including `--library-versions` — apply.


| Argument            | Required | Description                                                     |
| ------------------- | -------- | --------------------------------------------------------------- |
| `--input=<path>`    | yes*     | JAR/WAR/EAR file or directory of archives                       |
| `--jar-list=<file>` | yes*     | Text file with one JAR path per line (alternative to `--input`) |
| `--output=<file>`   | no       | Output Excel file (default: `WL14-Migration-Report.xlsx`)       |


```bat
:: Windows
run.bat wl14  C:\apps\lib   WL14-Migration-Report.xlsx

:: Direct
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl14 --input=/opt/app/lib
```

---



### `wl-jboss26` — WebLogic → WildFly 26 / JBoss EAP 7.4

Scans JARs, WARs, and EARs for patterns that need attention when migrating
from WebLogic to **WildFly 26 / JBoss EAP 7.4**.


| Target attribute | Value            |
| ---------------- | ---------------- |
| Java version     | Java 8           |
| EE specification | Jakarta EE 8     |
| API namespace    | `javax.*`        |
| Namespace change | **Not required** |


Reports cover: WebLogic-specific APIs, EJB legacy patterns (CMP/BMP), JNDI
naming, JMS configuration, classloading, and third-party library compatibility.

**Required argument:** `--input=<path>` — path to a JAR/WAR/EAR or directory

---



### `wl-jboss27` — WebLogic → WildFly 27+ / JBoss EAP 8

Scans JARs, WARs, and EARs for patterns that need attention when migrating
from WebLogic to **WildFly 27+ / JBoss EAP 8**.


| Target attribute | Value         |
| ---------------- | ------------- |
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

`**--input=<dir>`** (optional) — directory containing `*.json` IBM TA report files.
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


| Colour    | Severity   | Meaning                                                             |
| --------- | ---------- | ------------------------------------------------------------------- |
| 🟠 Orange | **HIGH**   | Will fail at runtime — fix before deployment                        |
| 🟡 Yellow | **MEDIUM** | Deprecated or behaviorally changed — plan to fix                    |
| 🟢 Green  | **LOW**    | Informational or soft-deprecated — low risk, review when convenient |


Fix all **HIGH** items first. These APIs have been removed from Java 21 or the
target library version and will cause runtime failures.

---



### `upgrade` module — 6-sheet report

The report opens to the **📋 Instructions** sheet, which contains a complete
in-report guide. Read it first before looking at the findings.
Every other sheet has a light-blue instruction bar at the top.


| Sheet                       | What to do here                                                                                                                                                                                                              |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **📋 Instructions**         | Read once. Explains every sheet, severity guide, migration steps, and how to manage exclusions.                                                                                                                              |
| **📊 Summary**              | Triage. Find components with the most HIGH issues. Sort by the Priority column to know where to start.                                                                                                                       |
| **☕ Java 21 Issues (IBM)**  | Fix HIGH rules first. Each row has an IBM WAMT Rule ID — search it at [ibm.com/docs/en/wamt](https://www.ibm.com/docs/en/wamt) for code-before/after migration guidance. The `Next Steps` column gives a direct search hint. |
| **📦 Library Issues**       | Simple substitutions. The `Replacement API` and `What to Do` columns tell you exactly what to change.                                                                                                                        |
| **✅ Remediation Checklist** | Your migration task list. One row = one unique fix (deduplicated). Sorted by severity. Mark `Done?` as you complete each fix. Share with your team to coordinate work.                                                       |
| **🚫 Excluded Rules**       | Reference. Shows every rule that was filtered out, with the reason. Edit `upgrade-excluded-rules.txt` to re-enable or add exclusions.                                                                                        |


**IBM Rule IDs** in the `☕ Java 21 Issues` sheet are IBM WAMT rule names
(e.g. `FinalizationDeprecated`, `RemovedSunAPIs`, `DetectThreadStop`).
Search any Rule ID at [https://www.ibm.com/docs/en/wamt](https://www.ibm.com/docs/en/wamt) for detailed code examples.

**Default exclusions:** 16 IBM TA informational rules are always excluded.
The `🚫 Excluded Rules` sheet lists them all with human-readable explanations.

---



### `wl15` / `wl14` modules — 6-sheet report (WL14: 7 sheets with IBM Java 21)


| Sheet                     | What to do here                                                                                                                                                                      |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **📋 Instructions**       | One-time read: what the report covers and how each sheet is organised.                                                                                                                 |
| **📊 Summary**            | Check total findings per library plus the outdated-library counts. Libraries with the most CRITICAL / HIGH findings are the highest-risk upgrade components.                          |
| **📦 Library Issues**     | Full detail of every finding: JAR, source file, line number, library, deprecated API, replacement guidance.                                                                            |
| **✅ Remediation Checklist** | Your migration task list. Each row is a unique API change, de-duplicated across all JARs. The `# Files` column shows how many files use each deprecated API — use it to prioritise.  |
| **⏱ Effort Analysis**     | Estimated remediation hours per JAR with subtotals and a grand total (override via `effort-overrides.properties`).                                                                     |
| **🔢 Library Versions**   | Bundled third-party artifacts detected in the scanned archives with detected vs. target versions. `OUTDATED` rows (sorted first, severity-coloured) are upgrade tasks; `OK` rows confirm compliance. |


**Reading the Checklist:**

- Sort by **Severity** (CRITICAL first) to prioritise your migration backlog.
- The **Replacement / Action** column gives the exact API to switch to.
- The **# Files** column indicates effort — a count of 1 is a quick fix; a count of 20+ warrants a team task.

---



### `wl-jboss26` / `wl-jboss27` module report sheets


| Sheet                  | Purpose                                                                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Summary**            | Severity/category breakdown, per-JAR issue counts, note on excluded WebLogic stubs                                                                     |
| **All Findings**       | Full detail per JAR: Source column (green = developer code, grey = WL-generated stub), API pattern, severity, remediation                              |
| **JAR Inventory**      | Per-JAR class count, issue count, generated stub count                                                                                                 |
| **Checklist**          | Deduplicated action list. `☐` = action needed, `—` = WL-generated stub (informational only)                                                            |
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
2. `**upgrade-excluded-rules.txt`** — user-editable file for additional exclusions.

The `🚫 Excluded Rules` sheet in the report lists every excluded rule with its reason.

### Supported exclusion formats


| Format              | Example                               | Effect                                |
| ------------------- | ------------------------------------- | ------------------------------------- |
| IBM WAMT rule name  | `RemovedJaxBModuleNotProvided`        | Suppresses that IBM rule              |
| Spring library name | `Guava 31.1-jre`                      | Suppresses all Guava library findings |
| Class prefix        | `org.springframework.remoting.jaxrpc` | Suppresses a specific Spring class    |




### How to use the exclusion file

1. Open `upgrade-excluded-rules.txt` in any text editor.
2. Add the rule ID or IBM WAMT rule name on a new line.
3. Lines starting with `#` are comments.
4. Re-run the tool — that rule will no longer appear in the report.

**Example — suppress after confirming JAXB dependency is added to** `pom.xml`**:**

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

# Source inventory / SVN-Git-local source checkout scan with a migration rule profile
# module=wl14
# input.mode=repo
# source.inventory.file=ComponentList.xlsx
# source.workspace.dir=.ea-workspace
# source.reuse.workspace=true
# source.clean.workspace=false
# source.fail.on.checkout.error=false
# source.prompt.credentials=true
```

See the bundled `analyzer.properties` for all available keys with descriptions.

---

## Source Inventory / SVN runbook

Use this when your component workbook points to SVN repositories.

### Workbook columns

Required columns:

| Column | Purpose |
| ------ | ------- |
| `Component` | Display name used in the report and workspace folder name |
| `Repository` | SVN URL, Git URL, or local source path |

Optional columns:

| Column | Purpose |
| ------ | ------- |
| `Trunk` | Latest fixed/reference repository URL or local path. When populated, the tool prepares this source in `.ea-workspace` alongside the fixable/current checkout and reports the trunk URL/path/status for remediation workflows. |
| `Type` | `SVN`, `GIT`, or `LOCAL`; recommended for SVN rows |
| `Branch` | Used by Git checkout; SVN branch should normally be part of the repository URL |
| `Revision` | SVN revision number or Git revision/commit/tag to checkout/update |
| `Path` | Subdirectory under the checked-out repository to scan |
| `Enabled` | Set to `false`, `no`, or `0` to skip a row |
| `Generated JARs` | Paths or glob patterns for JARs generated by the component, relative to the checkout or `Path`; separate multiple entries with comma, semicolon, or new line |
| `Application Packages` | Package prefixes used to focus generated-JAR bytecode correlation, for example `com.example` or `org.example.app`; separate multiple entries with comma, semicolon, or new line |
| `Ownership` | Team/system owner, portfolio, or triage note copied to the output workbook |

### Source + generated-JAR correlation

Repository scans always inspect source files first. When `Generated JARs` is populated, EffortAnalyzer also scans those generated artifacts and correlates bytecode references back to source findings for the same component.

Use this when the source checkout is available but you also have build outputs for that component:

1. Build the component outside EffortAnalyzer, or otherwise make its generated JARs available under the checkout.
2. Add each generated JAR path or glob to `Generated JARs`, for example `target/*.jar`, `build/libs/*.jar`, or `module-a/target/module-a.jar`.
3. Add application package prefixes to `Application Packages` so bytecode-only evidence is limited to classes owned by the component.
4. Add `Ownership` when you want the report to show the responsible team or application area.

Correlation fields in the `Source Findings` sheet:

| Field | Meaning |
| ----- | ------- |
| `Detection Source` | `SOURCE`, `BYTECODE`, or `SOURCE_AND_BYTECODE` |
| `Validation Status` | `CANDIDATE_SOURCE_ONLY`, `CONFIRMED_SOURCE_AND_BYTECODE`, or `CONFIRMED_BYTECODE_ONLY` |
| `Confidence` | Evidence confidence, independent from severity |
| `Matched In Source` / `Matched In Bytecode` | Boolean evidence flags |
| `Matched JARs` | Generated artifacts where matching bytecode was found |
| `Reason Code` | Why a finding was classified as source-only, source+bytecode, or bytecode-only |
| `Matched Classes` | Source or bytecode class names involved in the match |
| `Ownership` | Owner value from the inventory row |

Important behavior: source-only findings are retained as candidates. Bytecode evidence increases confidence/status; it does not hide source-only findings.

Use the `🎯 Action Items` sheet as the focused working view. It groups duplicate raw findings by component/rule, ranks confirmed bytecode evidence ahead of source-only candidates, keeps components that only appear in source findings visible, and adds a `Recommended Code Change` plus `Automation Starting Point` column for future assisted fixes. Treat `Source Findings` as raw evidence for drill-down rather than the primary backlog.

When an inventory row includes `Trunk`, EffortAnalyzer prepares the trunk/reference source in `.ea-workspace` next to the fixable/current checkout. Use `Source Inventory` to confirm `Checkout Status`, `Checkout Path`, `Trunk Checkout Status`, and `Trunk Checkout Path`; use `🎯 Action Items` or hidden `Action Items Raw` to pass `Fixable Source Location`/`fixable_source_path` and `Trunk Source Location`/`trunk_source_path` into a remediation workflow. The fixable path is the patch target; the trunk path is read-only comparison input. Current checkout failures and trunk checkout failures are both listed on `Checkout Errors`.

### What you need for SVN

1. Java 21+.
2. The built EffortAnalyzer JAR in the same folder as the launcher scripts.
3. `svn` available on `PATH` in the same shell that runs the tool.
4. VPN/network access to the SVN server.
5. Your corporate SSO username and password, unless your local SVN client already has valid cached credentials.
6. A workbook with `Type=SVN` rows and valid SVN repository URLs.

### Recommended Windows command

Use the target migration module (`upgrade`, `wl14`, `wl15`, `wl-jboss26`, `wl-jboss27`, or `wl-jboss`) with explicit launcher mode `repo`. The launcher passes the component workbook as `--source-inventory`, not `--input`:

```bat
run.bat upgrade repo ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl14 repo ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl15 repo ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl-jboss26 repo ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl-jboss27 repo ComponentList.xlsx SourceInventory-Report.xlsx
```

The Windows batch launcher automatically passes `--prompt-credentials=true` for `repo` and `both` runs.

To combine repository source checks with component-scoped binary/IBM checks in one workbook, use mode `both`. The workbook is the source of truth: binary scanning is limited to artifacts declared in enabled rows' `Generated JARs` column.

```bat
run.bat upgrade both C:\apps\lib ComponentList.xlsx Upgrade-Combined.xlsx
run.bat wl14 both C:\apps\lib ComponentList.xlsx WL14-Combined.xlsx
run.bat wl-jboss26 both C:\apps\lib ComponentList.xlsx WlToJBoss26-Combined.xlsx
run.bat wl-jboss27 both C:\apps\lib ComponentList.xlsx WlToJBoss27-Combined.xlsx
```

### Recommended PowerShell command

```powershell
.\run.ps1 -Module upgrade -Mode repo -SourceInventory "ComponentList.xlsx" -Output "SourceInventory-Report.xlsx" -PromptCredentials
.\run.ps1 -Module wl14 -Mode repo -SourceInventory "ComponentList.xlsx" -Output "SourceInventory-Report.xlsx" -PromptCredentials
.\run.ps1 -Module wl-jboss26 -Mode repo -SourceInventory "ComponentList.xlsx" -Output "SourceInventory-Report.xlsx" -PromptCredentials
.\run.ps1 -Module upgrade -Mode both -Input "C:\apps\lib" -SourceInventory "ComponentList.xlsx" -Output "Upgrade-Combined.xlsx" -PromptCredentials
.\run.ps1 -Module wl14 -Mode both -Input "C:\apps\lib" -SourceInventory "ComponentList.xlsx" -Output "WL14-Combined.xlsx" -PromptCredentials
.\run.ps1 -Module wl-jboss26 -Mode both -Input "C:\apps\lib" -SourceInventory "ComponentList.xlsx" -Output "WlToJBoss26-Combined.xlsx" -PromptCredentials
.\run.ps1 -Module wl-jboss27 -Mode both -Input "C:\apps\lib" -SourceInventory "ComponentList.xlsx" -Output "WlToJBoss27-Combined.xlsx" -PromptCredentials
```

### Direct Java command

```bat
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl-jboss26 ^
  --mode=repo ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --workspace=.ea-workspace ^
  --output=SourceInventory-Report.xlsx
```

Combined direct Java example:

```bat
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade ^
  --mode=both ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --output=Upgrade-Combined.xlsx
```

Useful source-inventory options:

| Option | Default | Notes |
| ------ | ------- | ----- |
| `--source-inventory=<xlsx>` | none | Component workbook to read |
| `--workspace=<dir>` | `.ea-workspace` | Checkout/update location for current/fixable source and sibling trunk/reference source folders |
| `--reuse-workspace=true|false` | `true` | Reuses existing `.svn`/`.git` working trees and runs update/fetch |
| `--clean-workspace=true|false` | `false` | Deletes each component checkout before checkout; useful after corrupted or wrong-revision working copies |
| `--fail-on-checkout-error=true|false` | `false` | Stop immediately on first checkout error instead of writing the error sheet |
| `--prompt-credentials=true|false` | `false` | Prompt once for username/password and pass them to Git/SVN commands |

Source-only workbook sheets are `Source Inventory`, `Source Findings`, `🎯 Action Items`, and `Checkout Errors`. The `Source Inventory` sheet includes `Generated JARs`, `Application Packages`, `Ownership`, and `Bytecode Findings` so you can confirm whether each component had bytecode evidence available.

Combined runs (`--mode=both`) treat `ComponentList.xlsx` as the source of truth. EffortAnalyzer checks out enabled inventory rows, resolves their `Generated JARs` values relative to each checkout, runs the compiled/IBM scan only on those resolved artifacts, then appends/replaces `Source Inventory`, `Source Findings`, `🎯 Action Items`, and `Checkout Errors` sheets in that same output file. `--input` is still accepted for backward-compatible command lines, but it is not used as the binary scan scope in `both` mode.

> Important: `--module=<migration-module> --mode=repo --source-inventory=...` uses that module's source-scan rule profile. Use `upgrade` for upgrade library rules, `wl14`/`wl15` for WebLogic library rules, and `wl-jboss26`/`wl-jboss27`/`wl-jboss` for WebLogic-to-JBoss rules.

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



### SVN command not found / checkout fails

If source inventory rows use SVN, EffortAnalyzer shells out to the `svn` executable.

- Install an SVN command-line client such as TortoiseSVN command line tools, SlikSVN, CollabNet SVN, or your corporate-standard SVN client.
- Open the same Command Prompt/PowerShell you will use for EffortAnalyzer and run `svn --version`.
- If `svn` is installed but not found, add its `bin` folder to `PATH` and reopen the terminal.

---

### SVN authentication / corporate SSO failures

Use `--prompt-credentials=true` so EffortAnalyzer asks once for your corporate SSO username and password and passes them to SVN as `--username` and `--password`.

If authentication still fails:

- Confirm VPN/network access to the SVN host.
- Run `svn info <repo-url>` manually first to complete first-time certificate trust, MFA, or browser/SSO bootstrap.
- Verify the URL in the workbook points to a checkoutable SVN path, not only a web UI URL.
- If the password contains special characters, prefer PowerShell/direct Java prompting over embedding credentials in a command line.
- Check the `Checkout Errors` sheet for the exact SVN message.

---

### Source inventory workspace has stale or wrong files

By default, `--reuse-workspace=true` updates existing checkouts under `.ea-workspace`. Use one of these when you need a clean run:

```bat
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl14 ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --clean-workspace=true
```

You can also delete `.ea-workspace` manually.

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



### `wl15` report shows zero findings

The `wl15` scanner checks source code inside JARs for references to deprecated
APIs. Zero findings means none of the scanned JARs contain Java source or
bytecode that uses the listed APIs. Verify that:

- The `--input` path points to the correct JARs / directory.
- The JARs contain `.java` source files or `.class` files (not just resources).
- The application actually uses the flagged libraries (Spring, Jetty, Jackson, etc.).

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

