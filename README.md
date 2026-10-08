# EffortAnalyzer v2.0.0

A suite of analysis tools for migration planning, API deprecation detection, and effort estimation.

---

## Modules at a glance

| Module | What it does | Input |
|--------|-------------|-------|
| [`upgrade`](#upgrade--upgrade-compatibility-analyzer) | Runs IBM WAMT (`binaryAppScanner.jar`) for Java 8→21 JVM compatibility + built-in Spring / Guava / Guice / Jersey / CGLib library scan. Can also scan repository source inventory with the upgrade rule profile and combine source sheets into the compiled report. | JAR / WAR / EAR directory, source inventory workbook, or both |
| [`wl15`](#wl15--weblogic-15-library-migration) | Scans compiled archives or repository source inventory for API compatibility issues across the WL15 library upgrade set: Spring 6, Jetty 12, Jackson 2.18, Netty 4.1, Log4j 2.25, EhCache 3, JasperReports 7, and more — plus bundled-library **version checks** for compiled inputs. Produces a migration workbook with Checklist, Library Versions, and optional Source sheets. | JAR / WAR / EAR directory, source inventory workbook, or both |
| [`wl14`](#wl14--weblogic-14-library-migration) | WebLogic 12c → 14.1.2 migration scan: IBM WAMT Java 21 compatibility check for compiled inputs, general library upgrade rules, WL14-specific deprecated WebLogic APIs, bundled-library version checks, and repository source scanning from a component workbook. | JAR / WAR / EAR directory, source inventory workbook, or both |
| [`wl-jboss26`](#wl-jboss26--weblogic--wildfly-26) | WebLogic → WildFly 26 / JBoss EAP 7.4 migration analysis (Java 8, `javax.*`). Supports compiled archives, repository source inventory, or combined workbooks. | JAR / WAR / EAR directory, source inventory workbook, or both |
| [`wl-jboss27`](#wl-jboss27--weblogic--wildfly-27) | WebLogic → WildFly 27+ / JBoss EAP 8 migration analysis (Java 21, `jakarta.*`). Supports compiled archives, repository source inventory, or combined workbooks. | JAR / WAR / EAR directory, source inventory workbook, or both |
| [`analyze`](#analyze--ibm-transformation-advisor-report-analyzer) | Consolidates IBM Transformation Advisor JSON reports into a grouped Excel workbook | Optional external JSON folder |
| [`merge`](#merge--excel-merger) | Merges a JIRA ticket report with a component list for effort tracking | Two Excel files |

---

## Quick start

### Prerequisites

- **Java 21+** on `PATH` (or `JAVA_HOME` set)
- **Maven 3.8+** — only needed if building from source
- **SVN command-line client** — required only when `--source-inventory` rows use SVN repositories. Verify with `svn --version`.
- **Git command-line client** — required only when `--source-inventory` rows use Git repositories. Verify with `git --version`.
- **Corporate repository access** — for SVN over corporate SSO, have your SSO username/password available and run with `--prompt-credentials=true`.
- **`binaryAppScanner.jar`** — required for the `upgrade` module's Java 21 scan
  Download free from: <https://www.ibm.com/support/pages/migration-toolkit-application-binaries>
  Place it in the same folder as `EffortAnalyzer-2.0.0-shaded.jar`.

### Build

```bash
mvn clean package -DskipTests -s settings-local.xml
```

Output JAR: `target/EffortAnalyzer-2.0.0-shaded.jar`

### Run (Windows)

```bat
run.bat upgrade    binary C:\apps\lib
run.bat upgrade    repo   ComponentList.xlsx SourceInventory-Report.xlsx
run.bat upgrade    both   C:\apps\lib ComponentList.xlsx Upgrade-Combined.xlsx
run.bat wl15       binary C:\apps\lib
run.bat wl14       repo   ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl15       repo   ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl14       both   C:\apps\lib ComponentList.xlsx WL14-Combined.xlsx
run.bat wl-jboss26 binary C:\apps\lib
run.bat wl-jboss26 repo   ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl-jboss26 both   C:\apps\lib ComponentList.xlsx WlToJBoss26-Combined.xlsx
run.bat wl-jboss27 binary C:\apps\lib
run.bat wl-jboss27 repo   ComponentList.xlsx SourceInventory-Report.xlsx
run.bat wl-jboss27 both   C:\apps\lib ComponentList.xlsx WlToJBoss27-Combined.xlsx
run.bat analyze
run.bat help
```

### Run (Unix / Linux / macOS)

```bash
chmod +x run.sh          # first time only
./run.sh upgrade    binary /opt/app/lib
./run.sh upgrade    repo   ComponentList.xlsx SourceInventory-Report.xlsx
./run.sh upgrade    both   /opt/app/lib ComponentList.xlsx Upgrade-Combined.xlsx
./run.sh wl15       binary /opt/app/lib
./run.sh wl14       repo   ComponentList.xlsx SourceInventory-Report.xlsx
./run.sh wl15       repo   ComponentList.xlsx SourceInventory-Report.xlsx
./run.sh wl14       both   /opt/app/lib ComponentList.xlsx WL14-Combined.xlsx
./run.sh wl-jboss26 binary /opt/app/lib
./run.sh wl-jboss26 repo   ComponentList.xlsx SourceInventory-Report.xlsx
./run.sh wl-jboss26 both   /opt/app/lib ComponentList.xlsx WlToJBoss26-Combined.xlsx
./run.sh wl-jboss27 binary /opt/app/lib
./run.sh wl-jboss27 repo   ComponentList.xlsx SourceInventory-Report.xlsx
./run.sh wl-jboss27 both   /opt/app/lib ComponentList.xlsx WlToJBoss27-Combined.xlsx
./run.sh analyze
./run.sh help
```

### Run directly with Java

```bash
# IBM scanner auto-detected from the working directory
java -jar EffortAnalyzer-2.0.0.jar --module=upgrade --input=/opt/app/lib

# WL15 library migration scan
java -jar EffortAnalyzer-2.0.0.jar --module=wl15 --input=/opt/app/lib

# Explicit IBM scanner path
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade --input=/opt/app/lib \
     --ibm-scanner=/opt/tools/binaryAppScanner.jar

# Repository source scan from SVN/Git/local component workbook; prompts once for credentials
# Works with upgrade, wl14, wl15, wl-jboss26, wl-jboss27, and wl-jboss.
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl-jboss26 --mode=repo \
     --source-inventory=ComponentList.xlsx --prompt-credentials=true \
     --workspace=.ea-workspace --output=SourceInventory-Report.xlsx

# Combined compiled archive scan + repository source scan in one workbook
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=upgrade --mode=both \
     --input=/opt/app/lib --source-inventory=ComponentList.xlsx \
     --prompt-credentials=true --output=Upgrade-Combined.xlsx

java -jar EffortAnalyzer-2.0.0-shaded.jar --help
```

> For full usage details, argument reference, and report interpretation see **[RUNNING.md](RUNNING.md)**.

---

## Module details

### `upgrade` — Upgrade Compatibility Analyzer

Runs two phases in a single pass and produces a unified **6-sheet Excel report**.

---

#### Phase 1 — Java 8 → Java 21 JVM Compatibility (IBM Migration Toolkit)

The IBM `binaryAppScanner.jar` is invoked automatically on your input JARs.
Its JSON output is written to a `reports/` folder next to the EffortAnalyzer JAR,
then parsed, filtered by the exclusion list, and written to the Excel report.

Command used internally:
```
java -jar binaryAppScanner.jar <input>
     --analyzeJavaSE --sourceJava=oracle8 --targetJava=java21
     --format=json --output=<jar-folder>/reports
```

**`reports/` folder:** created automatically on first run, cleaned before every subsequent
run (so stale JSON files never carry over), and the JSON files are kept afterwards for reference.

> **Setup:** download `binaryAppScanner.jar` from IBM (free) and place it in the
> same folder as `EffortAnalyzer-2.0.0-shaded.jar`. See [RUNNING.md §3](RUNNING.md#3-ibm-scanner-setup-upgrade-module).

---

#### Phase 2 — Library Upgrade Compatibility (built-in scanner)

Detects deprecated or removed APIs when upgrading:

| Library          | Target version | Migration scope |
|------------------|----------------|-----------------|
| Spring Framework | 5.3.39         | 3.x / 4.x / 5.x → 5.3.39 |
| Guava            | 31.1-jre       | Any prior version → 31.1 |
| Guice            | 5.1.0          | 3.x / 4.x → 5.1.0 |
| Jersey           | 2.22.2         | 1.x → 2.x (complete rewrite: `com.sun.jersey` → `org.glassfish.jersey`) |
| CGLib            | → ByteBuddy    | Any CGLib usage → ByteBuddy |

---

#### Report sheets (6 total)

| Sheet | Contents |
|-------|---------|
| **📋 Instructions** | In-report guide: sheet descriptions, severity key, migration steps, exclusion instructions. Read first. |
| **📊 Summary** | Per-component overview: Java 21 HIGH / MEDIUM / LOW counts + library issue count. Sort by Priority to triage. |
| **☕ Java 21 Issues (IBM)** | One row per IBM WAMT rule × component: Rule ID, Description, Severity, # classes affected, sample classes, Next Steps. |
| **📦 Library Issues** | Spring / Guava / Guice / CGLib hits with file, line, deprecated API, replacement, and "What to Do". |
| **✅ Remediation Checklist** | Deduplicated action list sorted by severity. One row = one fix. Mark `Done?` as you go. |
| **🚫 Excluded Rules** | Every filtered rule with the reason it was excluded. |

**Exclusions:**

- **16 IBM TA informational rules are always excluded** (same set as the `analyze` module).
- User-editable exclusions: edit `upgrade-excluded-rules.txt` next to the JAR.
- Both IBM WAMT rule names (e.g. `RemovedJaxBModuleNotProvided`) and library names (e.g. `Guava 31.1-jre`) are supported.

**Custom rules** — add project-specific library patterns to `upgrade-compatibility-rules.txt`.

---

### `wl15` — WebLogic 15 Library Migration

Scans JARs / WARs / EARs for API compatibility issues across the library upgrade
set required for **WebLogic 15**, and detects bundled third-party libraries
whose **version** is below the WL15 target — producing a **6-sheet Excel report**.

#### Libraries covered

| Library | Target version | Key breaking changes flagged |
|---------|---------------|------------------------------|
| Spring Framework | 6.2.11 | Remoting packages removed; `HandlerInterceptorAdapter` removed; `CommonsMultipartResolver` removed |
| Spring Security | 6.5.9 | `WebSecurityConfigurerAdapter` removed; `antMatchers`/`mvcMatchers` removed; `@EnableGlobalMethodSecurity` removed |
| Jackson | 2.18.9 | `enableDefaultTyping()` removed; `DefaultTyping.EVERYTHING` removed; Joda module deprecated |
| Netty | 4.1.135.Final | `ChannelHandlerContext.attr()` deprecated; `userEventTriggered()` deprecated; `HttpHeaders` method renames |
| Log4j | 2.25.4 | log4j 1.x bridge flagged; `PatternLayout`/`ConsoleAppender` builder API |
| Jetty | 12.0.33 | Full `javax.servlet` → `jakarta.servlet` migration; `AbstractHandler`/`HandlerWrapper`/`HandlerList` removed |
| JasperReports | 7.0.4 | `JRPdfExporter` moved to separate module; `JRProperties` removed; export manager changes |
| EhCache | 3.11.1 | Complete `net.sf.ehcache` → `org.ehcache` namespace change (EhCache 2→3 rewrite) |
| commons-fileupload | 1.6.0 | API moved to `commons-fileupload2`; Jakarta variant for Servlet 5+ |
| commons-beanutils | 1.11.0 | Type conversion and null-handling tightened |
| hibernate-validator | 6.2.0 | `@NotEmpty`/`@NotBlank` — prefer `jakarta.validation` equivalents |
| c3p0 | 0.12.0 | Connection pool property naming changes |
| MINA | 2.0.28 | `IoHandlerAdapter`/`IoSession` API changes |
| nimbus-jose-jwt | 9.37.2 | `JWTClaimsSet.parse(JSONObject)` removed |
| OWASP HTML Sanitizer | 20280101.1 | `HtmlPolicyBuilder` API updates |
| lz4-java, neethi, commons-vfs2, assertj-core | various | Minor deprecations and removals |

#### Bundled library version checks

In addition to the API scan, every archive (and every library JAR nested inside
WAR/EAR archives such as `WEB-INF/lib/`) is identified — by file name or embedded
`META-INF/maven/.../pom.properties` — and its version is compared against a
built-in target-version table taken verbatim from the project's
**LibraryUpgradeList** (28 rows — every library that has a planned upgrade
version; entries without one, e.g. `spring-web` or `spring-xml`, are not
version-checked). Examples: `spring-core ≥ 6.2.11`, `spring-webflux ≥ 6.1.14`,
`netty-codec ≥ 4.1.135.Final`, `jetty-http ≥ 12.0.33`, `log4j-core ≥ 2.25.4`,
Bouncy Castle `*-jdk18on ≥ 1.85`, all `jackson-*` ≥ 2.18.9. Below
target → `OUTDATED` row; at/above target → `OK`.

The table is overridable at runtime via a `library-versions.properties` file
placed next to the EffortAnalyzer JAR (or passed with `--library-versions=<file>`):

```properties
# adjust a built-in target
spring-core=6.2.12
# add a private artifact check (version:SEVERITY:Display name)
acme-shared=3.4.1:HIGH:Acme Shared Libs
# drop a built-in check
disable.moment=true
```

Version comparison handles qualifiers such as `-SNAPSHOT`, `.Final`, `-jre`,
`v20241219`, and vendor suffixes (`-redhat-00002`, `-nc`, `-r136`) without
false "outdated" alarms.

#### Report sheets (6 total)

| Sheet | Contents |
|-------|---------|
| **📋 Instructions** | How to read and act on the report |
| **📊 Summary** | Total findings by library and severity; outdated-library counts; JARs scanned; active rule count |
| **📦 Library Issues** | Full detail table: JAR, source file, line, library, deprecated API, replacement, severity — sorted CRITICAL first |
| **✅ Remediation Checklist** | Deduplicated action items sorted by severity — one row per unique API change, with a Done? checkbox |
| **⏱ Effort Analysis** | Estimated remediation hours per JAR with subtotals and grand total |
| **🔢 Library Versions** | Every rule-matched bundled artifact: detected vs. target version, OUTDATED/OK status, severity and upgrade action |

**Required argument:** `--input=<path>` — path to a JAR/WAR/EAR or directory
**Optional:** `--library-versions=<file>` — custom version target table
**Default output:** `WL15-Migration-Report.xlsx`

```bat
:: Windows
run.bat wl15  C:\apps\lib  WL15-Migration-Report.xlsx

:: Unix
./run.sh wl15 /opt/app/lib  WL15-Migration-Report.xlsx

:: Direct invocation
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl15 --input=/opt/app/lib
```

---

### `wl14` — WebLogic 14 Library Migration

WebLogic 12c → 14.1.2 migration scan. **Java 21 compatibility is a prerequisite:**
the scan first runs the IBM WAMT `binaryAppScanner.jar` (if available) to detect
Java SE API incompatibilities. It then runs the general library upgrade rules
(Spring, Guava, Guice, Jersey, CGLib) together with WL14-specific WebLogic
proprietary API rules (T3StartupDef, T3ShutdownDef, MessageLogger, TrustManager,
HostnameVerifier), plus bundled-library version checks. The 8-sheet Excel report
is labelled for **WL14** and includes the `☕ Java 21 Issues (IBM)` and
`🏛 WebLogic API Issues` sheets; the Library Issues sheet is grouped by archive/component.

**Required argument:** `--input=<path>` — path to a JAR/WAR/EAR or directory
**Optional:** `--library-versions=<file>` — custom version target table
**Default output:** `WL14-Migration-Report.xlsx`

```bat
:: Windows
run.bat wl14  C:\apps\lib  WL14-Migration-Report.xlsx

:: Direct invocation
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl14 --input=/opt/app/lib
```

---

### `wl-jboss26` — WebLogic → WildFly 26

Scans JARs / WARs / EARs for patterns that need attention when migrating from
WebLogic to **WildFly 26 / JBoss EAP 7.4** (Java 8, `javax.*`, Jakarta EE 8).

Covers: WebLogic-specific APIs, EJB 2.x legacy (CMP/BMP entity beans, `EJBHome`,
`HomeInterfaceHelper`), JNDI naming, JMS/Artemis configuration, deployment
descriptors (`weblogic-ejb-jar.xml` → `jboss-ejb3.xml`), and classloading.

**Report sheets:** Summary · All Findings · JAR Inventory · Checklist · **Migration Playbook**

The **Migration Playbook** sheet provides numbered step-by-step guides for the most
complex migration scenarios: `HomeInterfaceHelper` / EJB entity bean migration,
session EJB exposure, `PortableRemoteObject.narrow()` removal, `Platform.jndi()` /
JNDI context migration, WebLogic security → Elytron, and `javax.*` → `jakarta.*`.

---

### `wl-jboss27` — WebLogic → WildFly 27+

Same scope as `wl-jboss26`, plus additional checks for the `javax.*` → `jakarta.*`
namespace migration required by Jakarta EE 10, and Java 21 incompatible APIs.

Target: **WildFly 27+ / JBoss EAP 8** (Java 21, `jakarta.*`, Jakarta EE 10).

---

### `analyze` — IBM Transformation Advisor Report Analyzer

Reads IBM Transformation Advisor JSON analysis reports and produces a consolidated,
grouped Excel workbook. Findings are grouped by component and rule. Configurable rule
exclusions suppress commonly informational items.

**`--input=<dir>`** (optional) — directory containing IBM TA `*.json` report files.
If omitted, the bundled `reports/` folder inside the JAR is used.

Configure via `analyzer.properties`.

---

### `merge` — Excel Merger

Merges a JIRA ticket report spreadsheet with a component list spreadsheet,
producing a combined effort-tracking workbook. Uses left-join semantics (all
tickets are preserved), flexible column-name matching, and automatic
seconds-to-hours time conversion.

**Arguments:** `--ticket-file=<file>` `--component-file=<file>` `--output=<file>`

---

## Project structure

```
src/main/java/effortanalyzer/
├── EffortAnalyzerApp.java          # Unified CLI entry point & module dispatcher
├── analyzer/
│   └── ReportAnalyzer.java         # IBM TA JSON report analyzer
├── config/
│   ├── AppConfig.java              # CLI argument parsing & config resolution
│   └── AnalyzerConfig.java         # analyze-module configuration + default exclusions
├── merger/
│   └── TicketComponentMerger.java  # JIRA ticket ↔ component merger
├── upgrade/
│   └── UpgradeAnalyzer.java        # upgrade orchestrator: IBM scanner + Spring scan + 6-sheet report
├── library/
│   ├── LibraryUpgradeAnalyzer.java # Core JAR scanner + Excel report generator (shared by upgrade & wl15)
│   ├── LibraryUpgradeRules.java    # upgrade rule aggregator
│   ├── DeprecatedApi.java          # Rule record type
│   ├── SpringRules.java            # Spring 5.3.39 rules
│   ├── GuavaRules.java             # Guava 31.1 rules
│   ├── GuiceRules.java             # Guice 5.1.0 rules
│   ├── JerseyRules.java            # Jersey 1.x → 2.x rules
│   └── CglibRules.java             # CGLib → ByteBuddy rules
├── wl15/
│   ├── Wl15Analyzer.java           # wl15 orchestrator: API scan + version scan + report
│   ├── Wl15ReportWriter.java       # 6-sheet Excel report (Instructions/Summary/Issues/Checklist/Effort/Versions)
│   ├── Wl15LibraryRules.java       # wl15 rule aggregator
│   ├── Spring6Rules.java           # Spring 6.2.11 / Security 6.5.9 rules
│   ├── Jackson218Rules.java        # Jackson 2.18.9 rules
│   ├── Netty4Rules.java            # Netty 4.1.135 rules
│   ├── Log4j225Rules.java          # Log4j 2.25.4 rules
│   ├── Jetty12Rules.java           # Jetty 12.0.33 rules
│   ├── JasperReports7Rules.java    # JasperReports 7.0.4 rules
│   └── Wl15MiscRules.java          # EhCache 3, commons-*, hibernate-validator, c3p0, MINA, Nimbus, OWASP, ...
├── wl14/
│   └── Wl14Analyzer.java           # wl14 orchestrator: reuses WL15 API rules + version checks, WL14 report identity
├── version/
│   ├── LibraryVersionRule.java     # Version-check rule record (exact or prefix artifact match)
│   ├── LibraryVersionRules.java    # Built-in 28-row LibraryUpgradeList target table + properties overrides
│   ├── LibraryVersionAnalyzer.java # Archive scanner: detects bundled libs by name / pom.properties, flags OUTDATED
│   └── VersionComparator.java      # Lenient version comparison (SNAPSHOT, .Final, -jre, v-prefixes, vendor suffixes)
├── wljboss/
│   ├── WlJBossAnalyzer.java        # WebLogic → JBoss/WildFly scanner
│   ├── WlJBossRules.java           # Migration rules + TargetProfile enum
│   └── WlJBossReportWriter.java    # Excel report writer (incl. Migration Playbook)
└── util/
    └── ExcelUtils.java             # Shared Apache POI utilities

src/main/resources/
├── analyzer.properties             # Default configuration
├── log4j2.xml                      # Logging configuration
├── upgrade-excluded-rules.txt      # User-editable rule exclusion list (IBM WAMT names supported)
├── upgrade-compatibility-rules.txt # Custom Spring / library rules hook
└── reports/                        # Bundled IBM TA JSON reports (used by analyze module)
```

---

## Exclusion file — `upgrade-excluded-rules.txt`

The `upgrade` module has two layers of exclusions:

1. **16 hardcoded IBM TA informational rules** — always excluded, listed in the
   `🚫 Excluded Rules` sheet of every report. These are the same rules excluded
   by the standalone `analyze` module.

2. **`upgrade-excluded-rules.txt`** — user-editable file for additional exclusions.

Supported formats:

| Format | Example |
|--------|---------|
| IBM WAMT rule name | `RemovedJaxBModuleNotProvided` |
| Spring library name | `Guava 31.1-jre` |
| Class prefix | `org.springframework.remoting.jaxrpc` |

Lines starting with `#` are comments and are ignored.

**Example:**
```
# Suppress JAXB removal finding after adding jakarta.xml.bind dependency to pom.xml:
RemovedJaxBModuleNotProvided

# Suppress all Guava findings after library upgrade is complete:
Guava 31.1-jre
```

---

## Custom rules

### Spring / library side — `upgrade-compatibility-rules.txt`

```
library|className|methodName|severity|replacement|description
```

### WebLogic migration side — `wl-jboss-custom-rules.txt`

```
CATEGORY|apiPattern|SEVERITY|description|remediation
```

---

## Configuration

### `analyzer.properties`

```properties
# Module to run (overridden by --module=)
module=upgrade

# Input path (upgrade, wl-jboss26, wl-jboss27)
input.path=C:/apps/lib

# IBM scanner path (upgrade module; auto-detected from JAR folder if blank)
# analyzer.ibm.scanner.jar=C:/tools/binaryAppScanner.jar

# Output file
output.file=Upgrade-Compatibility-Report.xlsx

# analyze module settings
analyzer.resource.folder=reports
analyzer.parallel.enabled=true
analyzer.excel.maxCellLength=32000
# analyzer.excluded.rules=Rule1,Rule2
```

Place next to the JAR (working directory) to override bundled defaults.
CLI arguments always win over the properties file.

### Logging

| Destination | Level | Location |
|-------------|-------|---------|
| Console | INFO | coloured output |
| File | DEBUG | `logs/effortanalyzer.log` (rotates at 10 MB, keeps 10 files) |

Edit `src/main/resources/log4j2.xml` to customise.

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Apache POI | 5.2.5 | Excel report generation |
| Jackson | 2.17.1 | IBM TA JSON parsing |
| Log4j 2 | 2.23.1 | Logging framework |
| Commons Collections | 4.4 | Utility collections |

---

## Version history

### v2.0.0 (current)

- **`wl15` module** — new WebLogic 15 Library Migration analyzer covering 16+ library upgrades
  (Spring 6, Spring Security 6, Jetty 12, Jackson 2.18, Netty 4.1, Log4j 2.25, JasperReports 7,
  EhCache 3, and more); produces a 4-sheet Excel report with Summary, All Findings,
  Critical & High, and a deduplicated **Checklist** sheet for migration planning
- **`upgrade` module** — orchestrates IBM WAMT (`binaryAppScanner.jar`) for Java 21 JVM
  analysis + built-in Spring/Guava/Guice/Jersey/CGLib scan in a single run; produces a
  6-sheet Excel report with an Instructions sheet, component Summary, Java 21 Issues
  (IBM), Library Issues, Remediation Checklist, and Excluded Rules
- **Jersey 1.x → 2.22.2** — 30+ rules covering the complete API namespace change from
  `com.sun.jersey.*` to `org.glassfish.jersey.*`, Client/WebResource/ResourceConfig
  rewrites, filter SPI changes, test framework migration, multipart and JSON module changes
- **IBM WAMT integration** — IBM scanner is auto-detected next to the JAR; can be
  overridden with `--ibm-scanner=<path>`; graceful fallback if not found
- **`wl-jboss26` / `wl-jboss27`** — WebLogic → WildFly migration analyzer with 5-sheet
  report and Migration Playbook
- **Rule exclusion file** — `upgrade-excluded-rules.txt` for user-controlled exclusions;
  16 IBM TA informational rules are always excluded by default
- **`analyze` module** — supports `--input=<dir>` for external IBM TA JSON reports in
  addition to embedded baseline reports
- **Migration Playbook** — step-by-step guides for `HomeInterfaceHelper`, EJB entity
  beans, `PortableRemoteObject`, `Platform.jndi()`, classloading, and `javax.*` → `jakarta.*`
- Externalized configuration (`analyzer.properties`)
- Log4j2 logging with file rotation
- Shaded (fat) JAR: `EffortAnalyzer-2.0.0-shaded.jar`

### v1.0.0

- Initial release: basic IBM TA report analyzer and JIRA ticket merger

---

## WL14/WL15 source inventory SVN requirements

To test WL14/WL15 compatibility checks against SVN repositories using `--source-inventory`, you need:

1. Java 21+ and the built EffortAnalyzer JAR.
2. `svn` installed and available on `PATH` (`svn --version` should work in the same terminal used to run EffortAnalyzer).
3. Network/VPN access to the corporate SVN host.
4. Your corporate SSO username and password, unless the SVN client already has valid cached credentials.
5. A component workbook with at least `Component` and `Repository` columns. Use `Type=SVN` for SVN rows when the repository URL is not self-evident. Optional columns are `Branch`, `Revision`, `Path`, `Enabled`, `Generated JARs`, `Application Packages`, and `Ownership`.

Source inventory workbooks can now correlate source findings with bytecode from generated build artifacts:

| Column | Purpose |
|--------|---------|
| `Generated JARs` | Optional comma/semicolon/newline-separated filenames, paths, or glob patterns for JAR/WAR/EAR files produced by that source tree. Relative paths are resolved first from the checked-out component and, in `both` mode, from the compiled input directory passed on the command line. Matching bytecode raises confidence for source findings and can add bytecode-only findings. |
| `Application Packages` | Optional comma/semicolon/newline-separated package prefixes, for example `com.example, org.example.app`, used to focus generated-JAR bytecode correlation on application classes. |
| `Ownership` | Optional team/system note carried into `Source Inventory` and `Source Findings` to support triage and assignment. |

In `both` mode, the compiled input directory is an artifact lookup repository, not the binary scan scope. For example, `run.bat wl14 both C:\Development\Documents\EffortAnalyzer\Jars ComponentList.xlsx WL14-Combined.xlsx` scans source from the enabled workbook rows, then copies only the artifacts named in those rows' `Generated JARs` cells from `C:\Development\Documents\EffortAnalyzer\Jars` into a temporary binary scan directory. If a workbook cell contains `platform-abstraction-layer.jar` or `target/platform-abstraction-layer.jar`, both can resolve to `C:\Development\Documents\EffortAnalyzer\Jars\platform-abstraction-layer.jar`.

If `Generated JARs` is blank, EffortAnalyzer still reports source-only findings as candidates. Bytecode evidence updates `Detection Source`, `Validation Status`, `Confidence`, `Matched In Bytecode`, `Matched JARs`, and `Matched Classes`; it does **not** suppress source-only findings.

Recommended WL14 command for a corporate SSO-backed SVN source scan:

```bat
run.bat wl14 repo ComponentList.xlsx SourceInventory-Report.xlsx
```

For WL15, use the same workbook with the WL15 rule profile:

```bat
run.bat wl15 repo ComponentList.xlsx SourceInventory-Report.xlsx
```

For a combined source + binary report, use `both`. In this mode the component workbook is the source of truth: EffortAnalyzer checks out enabled components and runs binary/IBM scans only on artifacts listed in each row's `Generated JARs` column.

```bat
run.bat wl14 both C:\Development\Documents\EffortAnalyzer\Jars ComponentList.xlsx WL14-Combined.xlsx
```

or directly:

```bat
java -jar EffortAnalyzer-2.0.0-shaded.jar --module=wl14 ^
  --mode=repo ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --workspace=.ea-workspace ^
  --output=SourceInventory-Report.xlsx
```

Use `--mode=repo` for source-only scanning and `--mode=both` for compiled + source scanning. The migration module (`upgrade`, `wl14`, `wl15`, or `wl-jboss*`) determines which source-rule profile is applied.

For source-inventory rows with a `Trunk` value, EffortAnalyzer also prepares the trunk/reference source in `.ea-workspace` alongside the fixable/current checkout. The workbook exposes both source locations for remediation workflows:

- `Source Inventory`: `Checkout Status`, `Checkout Path`, `Trunk Checkout Status`, and `Trunk Checkout Path`.
- `🎯 Action Items`: `Fixable Source Location`, `Trunk Source Location`, and `Trunk Fix Guidance`.
- hidden `Action Items Raw`: normalized `fixable_source_url`, `fixable_source_path`, `trunk_source_url`, `trunk_source_path`, and `trunk_checkout_status` fields for scripts/skills.
- `Checkout Errors`: distinct current and trunk checkout failures.

The tool does not automatically copy code from trunk. Treat the fixable source path as the patch target and the trunk source path as read-only comparison input for a developer or remediation skill.

**Java 21 accuracy for source inventory (`upgrade`, `wl14`, `wl-jboss27`):**

- **Target-platform aware (WL14).** The WL14 target is WebLogic 14.1.2 on Java 21. It is still Java EE 8, so `javax.annotation`, `javax.transaction`, JAXB, JAX-WS, SAAJ, JAF, and JWS are supplied by the server. In the `wl14` profile these rules are reported as `PLATFORM_PROVIDED` (INFO). They stay visible in `Source Findings` but are left out of `🎯 Action Items`, the checklist, and the effort estimate. The guidance is "keep `javax.*`; declare the API jar with `provided` scope". `jakarta.*` replacements are suggested only for `wl15` and `wl-jboss27`.
- **Impact by rule kind.** `🎯 Action Items` / `Action Items Raw` add `Java 21 Impact` / `java21_impact` (`BREAKS_COMPILE`, `BREAKS_RUNTIME`, `BREAKS_ACCESS`, `DEPRECATED_FOR_REMOVAL`, `DEPRECATED`, `PLATFORM_PROVIDED`, …). APIs that are deprecated but still work, such as `Class.newInstance()`, are typed `OPTIONAL_CLEANUP`.
- **JDK tool evidence (`--jdk-tools=true`, default).** For declared `Generated JARs`, EffortAnalyzer runs `jdeprscan --release 21`, `jdeps --jdk-internals`, and a check of every `java.*` class/member reference against JDK 21. Results appear under scanner `JDK Tools` and are owner-resolved, for example `sun.misc.Service` as a removed internal API with a `ServiceLoader` replacement, or `java.security.acl.*` as a removed class. A JRE without these tools still runs the built-in check. Bytecode-only findings are mapped back to the `.java` file that declares the class, with a line hint.
- **Compile check (`--compile-check=true`, opt-in).** Each checkout's main sources are compiled with `javac --release 21`. Removed, internal, `jdk.unsupported`, deprecated, and deprecated-for-removal APIs reported by the compiler become `CONFIRMED_COMPILER` findings (scanner `Compile Check`), so no pre-built JARs are needed. Missing non-JDK classes are counted in the `Compile Check` status (`INCOMPLETE_CLASSPATH`) and are never findings; add `--compile-classpath` or `--maven-settings` to resolve them. When a component compiles cleanly, the JDK tools also run on the compiler output.
- **Trunk comparison.** Trunk is scanned too, and every finding gets a `Trunk Status`: `TRUNK_FIXED` (Trunk Evidence names the trunk file to port from), `TRUNK_SAME`, `TRUNK_REMOVED`, or `NOT_COMPARED`. Classes are matched by package, so different module layouts still line up. When trunk already runs on the target, set `Trunk Validated = Yes` in `ComponentList.xlsx` (or `--trunk-validated=true`): trunk-same findings then become `TRUNK_SAME_VALIDATED`, are marked **NOT REQUIRED**, and leave the checklist and effort estimate.

To have Cursor fix the workbook findings automatically, use the `wl14-report-remediation` skill (`.cursor/skills/wl14-report-remediation`). It patches the fixable checkouts, compares each finding with trunk, validates, and reports fixed / pending / false-positive / trunk-same items. Installation, usage, and team sharing are covered in [WL14_REMEDIATION_SKILL_GUIDE.md](WL14_REMEDIATION_SKILL_GUIDE.md).

`--prompt-credentials=true` prompts once and passes the supplied username/password to SVN as `--username` and `--password`. The SVN command is run non-interactively with `--trust-server-cert`, so if your organization requires a first-time certificate acceptance or MFA/browser login, run one manual `svn checkout` or `svn info` first to complete that setup.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `IBM scanner not found` | Download `binaryAppScanner.jar` from IBM, place next to the JAR |
| `☕ Java 21 Issues` sheet is empty | IBM scanner was not found — see above |
| `Unknown module: 'spring'` | Use `--module=upgrade` instead |
| `Unknown module: 'java21'` | Use `--module=upgrade` instead |
| `wl15` report is empty | Verify `--input` points to JARs containing Java source compiled against the affected libraries |
| Report not created — file locked | Close the output `.xlsx` in Excel first |
| Report not created — directory missing | Create the output directory manually |
| Java not found | Ensure Java 21+ is on `PATH` or set `JAVA_HOME` |
| Build fails — can't resolve dependencies | Use `-s settings-local.xml` flag with Maven |
| SVN checkout fails / `svn` not found | Install the SVN CLI and verify `svn --version` works in the terminal used to run the tool |
| SVN authentication fails | Use `--prompt-credentials=true`; if corporate SSO requires MFA/browser login, first run `svn info <repo-url>` or `svn checkout <repo-url>` manually to seed credentials/certificates |
| Source inventory report has checkout errors | Review the `Checkout Errors` sheet and `.ea-workspace`; use `--clean-workspace=true` to force fresh checkouts |
| Source finding is `CANDIDATE_SOURCE_ONLY` | This means no matching generated-JAR bytecode was configured or found. Populate `Generated JARs` and `Application Packages`, then re-run after the component builds successfully. |
| Out of memory during scan | Add `-Xmx2g` before `-jar` |

For full troubleshooting, setup instructions, and report reading guide see **[RUNNING.md](RUNNING.md)**.

---

## Support

1. Check `logs/effortanalyzer.log` for detailed error output
2. Review `analyzer.properties` for configuration issues
3. Verify Java version: `java -version` (must be 21+)
4. Run with `--help` to see all available options
