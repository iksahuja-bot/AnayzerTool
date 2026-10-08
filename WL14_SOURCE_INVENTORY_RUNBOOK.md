# WL14 Source Inventory Working Model and Runbook

Audience: management, application owners, migration leads, and tool users who need to run or understand the EffortAnalyzer WL14 source inventory report.

This document explains how the WL14 source inventory module works, what inputs are required, where generated JAR/WAR/EAR artifacts should be copied, how to run the tool in both supported source-inventory modes, and how to interpret the output workbook.

---

## 1. Executive Summary

The EffortAnalyzer `wl14` module supports WebLogic 12c to WebLogic 14.1.2 migration planning. It identifies migration work from two complementary perspectives:

1. **Repository/source evidence** — scans checked-out Git, SVN, or local source trees listed in a component workbook.
2. **Compiled artifact evidence** — scans generated JAR/WAR/EAR files from the same components to confirm which source findings are present in build outputs and to run the WL14 binary checks.

For management, the report provides a component-level view of risk, migration action items, and estimated remediation effort. For users, it provides exact source files, rules, validation status, generated artifact matches, and practical remediation guidance.

The most important workbook sheet for planning is **`🎯 Action Items`**. It appears immediately after **`📊 Summary`** and groups the migration backlog by component so application teams can start triage quickly.

---

## 2. Supported WL14 Source Inventory Modes

| Mode | Purpose | Main Inputs | When to Use |
|------|---------|-------------|-------------|
| `repo` | Source-only inventory scan | `ComponentList.xlsx` | Use when you want fast source-level visibility or generated artifacts are not ready yet. |
| `both` | Combined source + generated artifact/binary scan | `ComponentList.xlsx` plus a folder containing generated JAR/WAR/EAR files | Use for management-ready assessment because it confirms source findings against generated artifacts and runs WL14 binary checks only on workbook-declared artifacts. |

There is also a plain `binary` mode for compiled archives only, but this runbook focuses on the source inventory usage requested here: **`repo` and `both`**.

---

## 3. What WL14 Checks

The `wl14` module is intended for WebLogic 12c to 14.1.2 migration assessment. It includes:

- IBM binary scanner integration for Java 21 compatibility checks, when `binaryAppScanner.jar` is available.
- General upgrade rules for libraries such as Spring, Guava, Guice, Jersey, and CGLib.
- WL14-specific WebLogic deprecated/proprietary API checks, including examples such as `T3StartupDef`, `T3ShutdownDef`, `MessageLogger`, `TrustManager`, and `HostnameVerifier`.
- Bundled library version checks for compiled inputs.
- Source inventory scanning from a component workbook.
- Source-to-bytecode correlation using the workbook columns `Generated JARs` and `Application Packages`.

---

## 4. Required Folder Layout

Recommended deployment folder:

```text
EffortAnalyzer\
├─ EffortAnalyzer-2.0.0.jar
├─ run.bat
├─ run.ps1
├─ binaryAppScanner.jar              optional but recommended for Java 21/WL14 binary checks
├─ ComponentList.xlsx                source inventory workbook
├─ Jars\                             generated JAR/WAR/EAR artifact folder for both mode
│  ├─ component-a.jar
│  ├─ component-b.war
│  └─ component-c.ear
├─ .ea-workspace\                    created by the tool for Git/SVN/local checkouts
└─ logs\                             created by the tool for logs
```

### Where to Copy JARs/WARs/EARs

For `both` mode, copy all generated component artifacts into one folder, for example:

```text
C:\Development\Documents\EffortAnalyzer\Jars
```

Then pass that folder as the compiled-input/artifact lookup folder:

```bat
run.bat wl14 both C:\Development\Documents\EffortAnalyzer\Jars ComponentList.xlsx WL14-Combined.xlsx
```

Important: in `both` mode, the folder is used as an **artifact lookup repository**, not as an unrestricted binary scan scope. The component workbook remains the source of truth. EffortAnalyzer copies and scans only artifacts named in enabled rows' `Generated JARs` cells.

---

## 5. Prerequisites

| Requirement | Notes |
|-------------|-------|
| Java 21+ | `java -version` should work in the same terminal used to run EffortAnalyzer. |
| EffortAnalyzer JAR | `EffortAnalyzer-2.0.0.jar` should be in the deployment folder next to `run.bat` / `run.ps1`. |
| IBM scanner | Download `binaryAppScanner.jar` from IBM and place it next to the EffortAnalyzer JAR, or pass `--ibm-scanner=<path>`. If missing, source scans still run, but Java 21 binary scanner output can be empty. |
| SVN CLI | Required for SVN repositories. `svn --version` must work. |
| Git CLI | Required for Git repositories. `git --version` must work. |
| Network/VPN | Required for corporate SVN/Git repository access. |
| Credentials | Use cached SCM credentials or let the launcher prompt once with `--prompt-credentials=true`. |
| Component workbook | Excel workbook with at least `Component` and `Repository` columns. |
| Generated artifacts | Required for `both` mode. Copy component JAR/WAR/EAR outputs into the artifact folder or ensure they exist under the checked-out source tree. |

For corporate SSO-backed SVN, complete any first-time certificate trust, MFA, VPN, or browser login step manually before running the tool. Example:

```bat
svn info https://your-svn-server/path/to/repo
```

The tool runs SVN non-interactively with certificate trust enabled, so the first-time interactive setup must already be completed if your environment requires it.

---

## 6. Component Workbook Format

The first sheet of `ComponentList.xlsx` is read. The workbook must contain `Component` and `Repository`; other columns improve filtering, checkout control, source-to-bytecode correlation, and optional comparison with already-fixed trunk code.

| Column | Required | Example | Purpose |
|--------|----------|---------|---------|
| `Component` | Yes | `CustomerManagement` | Business or technical component name shown in reports. |
| `Repository` | Yes | `https://svn.company.com/app/customer` or `C:\repos\customer` | SVN/Git URL or local source path. |
| `Trunk` | Optional | `https://svn.company.com/app/customer/trunk` | Latest trunk/reference location where compatible fixes may already exist. When populated, the tool prepares this source in `.ea-workspace` alongside the fixable/current checkout and reports its URL, path, and checkout status. |
| `Type` | Recommended | `SVN`, `GIT`, `LOCAL` | Repository type. If blank, the tool infers the type from the repository value. |
| `Branch` | Optional | `main`, `release/14c` | Branch/tag hint for Git/SVN checkout. |
| `Revision` | Optional | `123456` | SVN revision or Git commit reference where supported. |
| `Path` | Optional | `src`, `service-module` | Subfolder under the repository to scan. |
| `Enabled` | Optional | `true`, `false`, `skip` | Blank means enabled. Use `false`, `no`, `n`, `0`, `disabled`, or `skip` to exclude a row. |
| `Generated JARs` | Strongly recommended for `both` | `target/customer.jar` or `customer-service.war` | JAR/WAR/EAR filename, path, or glob pattern produced by this component. Multiple values can be separated by comma, semicolon, or line break. |
| `Application Packages` | Recommended | `com.company.customer, org.company.customer` | Package prefixes used to focus generated-JAR bytecode correlation on application-owned classes. |
| `Ownership` | Optional | `Customer Platform Team` | Team/system ownership note carried into report sheets for assignment. |

### Example Workbook Rows

| Component | Repository | Trunk | Type | Branch | Path | Enabled | Generated JARs | Application Packages | Ownership |
|-----------|------------|-------|------|--------|------|---------|----------------|----------------------|-----------|
| CustomerManagement | `https://svn.company.com/apps/customer/branches/wl12` | `https://svn.company.com/apps/customer/trunk` | SVN | `wl12` | `customer-service` | true | `target/customer-service.jar` | `com.company.customer` | Customer Team |
| BillingWeb | `https://git.company.com/billing/billing-web.git` | `https://git.company.com/billing/billing-web.git` | GIT | `release/wl14` |  | true | `billing-web.war` | `com.company.billing` | Billing Team |
| SharedUtil | `C:\repos\shared-util` | `https://svn.company.com/libs/shared-util/trunk` | LOCAL |  |  | true | `target/shared-util.jar` | `com.company.shared` | Platform Team |

### Trunk-Assisted Fix Guidance

Use `Trunk` when the latest trunk already contains WL14/Java compatibility fixes that can be reused. When `Trunk` is populated, EffortAnalyzer downloads/prepares that reference source into `.ea-workspace` next to the fixable/current checkout and records both locations in the generated workbook. Trunk checkout folders use a separate `-trunk` workspace suffix, so the fixable source and reference source remain distinct. It still does **not** automatically patch application code from trunk; the workbook provides the source/trunk locations and action-item guidance for a developer or automated remediation skill.

Recommended workflow:

1. Scan the current in-scope source via `Repository`.
2. Populate `Trunk` with the latest fixed/reference source location.
3. Review `🎯 Action Items` by component.
4. For each issue, compare the files/rules listed in the action item against the same area in `Trunk`.
5. Pass the full `.ea-workspace` plus the workbook to the remediation skill/developer so both the fixable/current source and trunk reference source are already available.
6. Port the smallest relevant compatible change, rebuild the component, regenerate the declared `Generated JARs`, and rerun `both` mode.

This is a good input for an automated fixing skill, but the skill should perform the code-level work: compare the prepared fixable/current source path against the prepared trunk source path, inspect diffs near the flagged files/rules, propose or apply a patch, and run validation. The report should make that skill easier by grouping work by component and providing `Action Item ID`, `Remediation Type`, `Automation Readiness`, `Detected Symbol`, `Replacement Symbol`, `Primary File`, `Line Hints`, `Fixable Source Location`, `Trunk Source Location`, sample files, rule/API, recommended change, guardrails, and validation instructions in one row.

### Generated JARs Matching Rules

The `Generated JARs` value can be:

- A simple filename: `customer-service.jar`
- A relative path: `target/customer-service.jar`
- A WAR/EAR file: `customer-web.war`, `customer-app.ear`
- A glob pattern: `target/*.jar`
- Multiple values separated by comma, semicolon, or line break.

The tool resolves each value first from the checked-out component folder and, in `both` mode, also from the compiled-input/artifact folder passed on the command line.

Example: if the workbook has `target/customer-service.jar` and the artifact folder contains:

```text
C:\Development\Documents\EffortAnalyzer\Jars\customer-service.jar
```

the tool can still match it by filename when using the artifact folder in `both` mode.

---

## 7. How the Tool Works

### Repo Mode Flow

```text
ComponentList.xlsx
  → read enabled rows
  → checkout/update current source into .ea-workspace
  → checkout/update Trunk source into a sibling .ea-workspace folder when Trunk is populated
  → scan source files with WL14 source rules
  → scan generated artifacts only if listed and available under the checkout
  → write standalone source inventory workbook
```

Output focus:

- Component checkout status.
- Trunk checkout status/path where a `Trunk` value was provided.
- Source findings by file and line.
- `🎯 Action Items` backlog grouped by component.
- Checkout errors for failed current or trunk repositories.

### Both Mode Flow

```text
ComponentList.xlsx + Jars folder
  → read enabled rows
  → checkout/update current source into .ea-workspace
  → checkout/update Trunk source into a sibling .ea-workspace folder when Trunk is populated
  → resolve each row's Generated JARs from checkout and supplied Jars folder
  → copy only matched generated artifacts to a temporary binary scan folder
  → run WL14 binary/library/IBM checks against that temporary folder
  → append source inventory sheets into the same WL14 report workbook
```

Output focus:

- Management-ready combined workbook.
- WL14 compiled artifact findings.
- Source findings and action items.
- Fixable/current and trunk source locations for remediation workflows.
- Evidence fields showing source-only vs confirmed bytecode matches.

If no generated JAR/WAR/EAR files are found from enabled rows in `both` mode, the tool stops with an error. Populate `Generated JARs`, build/copy the artifacts, then rerun.

---

## 8. Runbook: Repo Mode

Use `repo` mode when you want a source-only scan from the component workbook.

### Step 1 — Prepare the Workbook

Create/update `ComponentList.xlsx` with at least:

- `Component`
- `Repository`
- `Type` where needed, especially `SVN` for SVN rows

Recommended also:

- `Trunk` when a latest fixed/reference source tree is available
- `Generated JARs`
- `Application Packages`
- `Ownership`

### Step 2 — Verify Access

From the same terminal:

```bat
java -version
svn --version
git --version
```

For SVN/SSO repositories, run one manual `svn info` first if required by your corporate setup.

### Step 3 — Run Repo Mode

Recommended Windows batch command:

```bat
run.bat wl14 repo ComponentList.xlsx SourceInventory-Report.xlsx
```

PowerShell equivalent:

```powershell
.\run.ps1 -Module wl14 -Mode repo -SourceInventory "ComponentList.xlsx" -Output "SourceInventory-Report.xlsx" -PromptCredentials
```

Direct Java equivalent:

```bat
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 ^
  --mode=repo ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --workspace=.ea-workspace ^
  --output=SourceInventory-Report.xlsx
```

### Step 4 — Review Output

Open `SourceInventory-Report.xlsx` and review in this order:

1. `📊 Summary`
2. `🎯 Action Items`
3. `Source Inventory`
4. `Source Findings`
5. `Checkout Errors`

---

## 9. Runbook: Both Mode

Use `both` mode for a combined WL14 report containing source evidence and generated artifact/binary evidence.

### Step 1 — Build Components

Build the components listed in `ComponentList.xlsx` using the normal project build process.

### Step 2 — Copy Generated Artifacts

Copy the generated JAR/WAR/EAR files into a single artifact folder, for example:

```text
C:\Development\Documents\EffortAnalyzer\Jars
```

Example contents:

```text
C:\Development\Documents\EffortAnalyzer\Jars\customer-service.jar
C:\Development\Documents\EffortAnalyzer\Jars\billing-web.war
C:\Development\Documents\EffortAnalyzer\Jars\shared-util.jar
```

### Step 3 — Update `Generated JARs`

In `ComponentList.xlsx`, make sure each enabled component row has the artifact name/path in `Generated JARs`.

Examples:

```text
customer-service.jar
target/customer-service.jar
billing-web.war
target/*.jar
```

Use comma, semicolon, or line breaks when a component produces multiple artifacts.

### Step 4 — Place IBM Scanner

For best WL14 binary coverage, place this file next to `EffortAnalyzer-2.0.0.jar`:

```text
binaryAppScanner.jar
```

If it is stored elsewhere, use direct Java and pass:

```text
--ibm-scanner=C:\tools\binaryAppScanner.jar
```

### Step 5 — Run Both Mode

Recommended Windows batch command:

```bat
run.bat wl14 both C:\Development\Documents\EffortAnalyzer\Jars ComponentList.xlsx WL14-Combined.xlsx
```

PowerShell equivalent:

```powershell
.\run.ps1 -Module wl14 -Mode both -Input "C:\Development\Documents\EffortAnalyzer\Jars" -SourceInventory "ComponentList.xlsx" -Output "WL14-Combined.xlsx" -PromptCredentials
```

Direct Java equivalent:

```bat
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 ^
  --mode=both ^
  --input=C:\Development\Documents\EffortAnalyzer\Jars ^
  --source-inventory=ComponentList.xlsx ^
  --prompt-credentials=true ^
  --workspace=.ea-workspace ^
  --output=WL14-Combined.xlsx
```

### Step 6 — Confirm the Scope

During the run, check the console message similar to:

```text
[both] Inventory-scoped binary scan: N generated artifact(s) from ComponentList.xlsx and supplied compiled input
```

If `N` is lower than expected, review `Generated JARs`, artifact filenames, and the artifact folder contents.

### Step 7 — Review Output

Open `WL14-Combined.xlsx` and review in this order:

1. `📊 Summary` — management overview.
2. `🎯 Action Items` — primary migration backlog grouped by component.
3. `Source Inventory` — component checkout status, generated JAR mapping, package mapping, ownership, and bytecode-finding counts.
4. `Source Findings` — detailed source evidence.
5. `Checkout Errors` — repositories that could not be checked out or updated.
6. WL14 binary/library sheets, including remediation/checklist/library version sheets produced by the compiled scan.

---

## 10. Report Reading Guide

The output is an Excel workbook. Start with the summary sheets, then drill into component and file-level evidence only where needed. Do not start by reading every detailed finding row; use the workbook to identify priority components first.

### Recommended Reading Paths

| Reader | Start Here | Then Review | Main Question Answered |
|--------|------------|-------------|------------------------|
| Management / sponsors | `📊 Summary` | `🎯 Action Items` | How much migration work exists, where is the risk, and which teams/components need attention first? |
| Migration leads / architects | `📊 Summary` | `🎯 Action Items`, `Source Inventory`, WL14 binary/library sheets | Which components are highest priority, which findings are confirmed in generated artifacts, and what needs architectural review? |
| Application owners / team leads | `🎯 Action Items` | `Source Inventory`, `Source Findings` filtered by their component or ownership | What does my team need to fix, and which source files/artifacts prove it? |
| Developers | `Source Findings` | `🎯 Action Items`, WL14 binary/library sheets | What file, line, API, dependency, or configuration needs remediation? |
| Tool operators | `Source Inventory` | `Checkout Errors`, console log | Did every expected repository and generated artifact scan correctly? |

### Suggested Review Order

1. **Open `📊 Summary` first.** Use it for the management-level count of scanned components, findings, severities, and overall migration exposure.
2. **Open `🎯 Action Items` second.** Treat this as the working migration backlog. It groups actionable work by component so teams can triage quickly.
3. **Use filters by component, ownership, severity, and evidence.** This is the fastest way to split work by application team.
4. **Open `Source Inventory` to validate coverage.** Confirm each expected component checked out successfully and that generated artifact/package fields were populated.
5. **Open `Source Findings` for developer detail.** Use this only after selecting a component/action item; it contains the file-level evidence and remediation text.
6. **For `both` mode, review binary/library sheets.** These sheets identify compiled artifact risks, library versions, and findings that may not appear directly in source rows.
7. **Review `Checkout Errors` last unless counts look wrong.** If a component is missing or has no findings unexpectedly, this sheet explains repository checkout/update failures.

| Sheet | Audience | How to Use |
|-------|----------|------------|
| `📊 Summary` | Management, leads | Start here for component counts, severity totals, and overall migration exposure. |
| `🎯 Action Items` | Management, team leads, developers | Treat this as the primary backlog. Items are grouped by component and prioritized by severity/evidence. |
| `Source Inventory` | Tool users, migration leads | Verify every expected component was scanned; check checkout status, generated JARs, package filters, ownership, and bytecode evidence counts. |
| `Source Findings` | Developers | Use for file-level and line-level evidence, rule names, context, and remediation text. |
| `Checkout Errors` | Tool users | Fix repository access, credentials, branches, paths, or network/VPN issues. |
| WL14 binary/library sheets | Architects, developers | Review compiled artifact/library issues, bundled versions, and binary-only risks. |

### How to Read Each Main Sheet

#### `📊 Summary`

Use this sheet for the executive picture. It should answer:

- How many components were scanned?
- How many findings were found by severity or category?
- Are there high-risk components that should be prioritized first?
- Does the scan look complete enough to share?

Recommended action: use the summary to brief leadership, then move to `🎯 Action Items` for the actual migration backlog.

#### `🎯 Action Items`

Use this sheet as the primary remediation plan. It is physically grouped by component with visible component heading rows. Expand/collapse the grouped rows or filter within one component to plan work for a specific team. Each detail row represents work that should be reviewed, assigned, or tracked. Read it by:

1. Filtering by `Component` or `Ownership` to route work to the correct team.
2. Sorting by severity/priority so critical or high-confidence findings are addressed first.
3. Reviewing the finding description and recommended remediation.
4. Checking whether the item is confirmed by generated artifact evidence or is source-only.
5. Using `Action Item ID` as the stable key when tracking or feeding work into automation.
6. Using `Remediation Type` and `Automation Readiness` to separate targeted code rewrites from manual, dependency/build, config, or low-confidence review items.
7. Using `Primary File`, `Detected Symbol`, `Replacement Symbol`, and `Line Hints` as structured starting points for a remediation skill or developer.
8. Using `Fixable Source Location`, `Trunk Source Location`, and `Trunk Fix Guidance` when the latest trunk already contains related compatibility changes that can be ported. The report prepares both locations in `.ea-workspace` when possible, so a future fixing skill can receive the full workspace as input.

Recommended action: export or copy the relevant component group into the team backlog/Jira plan, preserving component, file, rule, remediation, fixable source location, trunk source location, and validation details.

#### `Action Items Raw`

This hidden sheet is a normalized technical extract of `🎯 Action Items` for scripts or future automated code-remediation skills. It intentionally omits component heading rows and uses stable machine-oriented column names such as `action_item_id`, `remediation_type`, `automation_readiness`, `detected_symbol`, `replacement_symbol`, `primary_file`, `line_hints`, `all_candidate_files`, `fixable_source_url`, `fixable_source_path`, `trunk_source_url`, `trunk_source_path`, `trunk_checkout_status`, `suggested_validation`, and `automation_guardrails`.

Recommended automation usage:

- Read `Action Items Raw` instead of scraping the human-visible grouped sheet.
- Treat `action_item_id` as the stable action key across reruns, as long as the component/rule/remediation grouping remains the same.
- Prefer `HIGH` and `MEDIUM` readiness rows before `LOW` readiness rows.
- Use `automation_guardrails` to avoid patching generated outputs or binary archives directly.
- Treat `fixable_source_path` as the path to patch and `trunk_source_path` as read-only comparison input. Pass the full `.ea-workspace` to the fixing skill so it has both prepared source trees.

#### `Source Inventory`

Use this sheet to confirm scan coverage and input quality. It is especially important before sharing the report externally or using totals for planning.

Check:

- Every expected component appears once.
- `Checkout Status` and `Checkout Path` are successful/present for each in-scope fixable/current repository.
- `Trunk Checkout Status` and `Trunk Checkout Path` are successful/present for rows where `Trunk` is populated. `TRUNK_NOT_PROVIDED` is expected when no `Trunk` value was supplied; `TRUNK_SKIPPED` means the current source checkout failed first.
- `Generated JARs` values are present for components expected to participate in `both` mode.
- `Application Packages` values are populated where package filtering is needed.
- Ownership/team information is present so action items can be routed.
- Bytecode-related counts look reasonable in `both` mode.

Recommended action: if a component is missing, has a failed current/trunk checkout, or has no generated artifact mapping, correct `ComponentList.xlsx` or repository access and rerun before treating the report as final.

#### `Source Findings`

Use this sheet for detailed developer evidence. It usually contains the most rows, so read it after filtering to one component or one rule.

Look for:

- Source file path and line/context.
- Rule/check name.
- Severity or category.
- Matched API, class, dependency, XML/config entry, or build-file evidence.
- Recommended remediation text.
- Validation/evidence status, especially whether the finding is confirmed in generated bytecode.

Recommended action: developers should use this sheet to locate the exact code/configuration to change and to confirm whether the issue is production code, test code, build configuration, or dependency-related.

#### `Checkout Errors`

Use this sheet to explain gaps in the report. A clean scan should have no unexpected checkout errors. The sheet distinguishes current/fixable repository failures from trunk/reference failures, so a failed trunk preparation can be fixed without confusing it with the source that was scanned.

Common causes include:

- Bad repository URL.
- Wrong repository type.
- Missing VPN/network access.
- Expired or missing SVN/Git credentials.
- Invalid branch, tag, revision, or path.

Recommended action: fix the workbook row or access issue, rerun, then confirm the component appears successfully in `Source Inventory`.

#### WL14 Binary / Library Sheets

In `both` mode, the workbook also includes WL14 compiled artifact and library sheets. Use these to validate what is actually packaged in JAR/WAR/EAR files.

Use them to answer:

- Which libraries are bundled in the generated artifacts?
- Which library versions may need upgrade for WL14/Java 21?
- Are there binary-only risks that do not map cleanly to source inventory rows?
- Do source findings appear in the compiled output?

Recommended action: architects and application leads should combine these sheets with `🎯 Action Items` to decide whether remediation is a source change, dependency upgrade, packaging change, or platform validation item.

### Validation Status Meaning

| Status | Meaning | Recommended Action |
|--------|---------|--------------------|
| Confirmed bytecode evidence | The source finding is also visible in generated artifact bytecode. | Prioritize for remediation. |
| `CANDIDATE_SOURCE_ONLY` | Source owns the code, but no matching generated artifact evidence was found. | Keep visible. Verify build output, update `Generated JARs` / `Application Packages`, rerun `both` mode if needed. |
| Config/build evidence | Finding is in configuration or build files. | Assign to build/config owner. |
| Test-only evidence | Finding appears in test scope. | Fix if tests must run on the target platform; otherwise lower priority. |
| Bytecode-only evidence | Compiled artifact contains a match but source mapping is incomplete. | Map the JAR/class back to the source owner before remediation. |

### Java 21 Impact and WL14 Platform-Provided APIs

Read `Java 21 Impact` (`java21_impact` in `Action Items Raw`) before `Severity`:

| Impact | Recommended Action |
|--------|--------------------|
| `BREAKS_COMPILE` / `BREAKS_RUNTIME` | Required for Java 21. Check trunk first; port the trunk fix when one exists. |
| `BREAKS_ACCESS` | Replace the JDK internal; `--add-opens` / `--add-exports` is a temporary workaround. |
| `DEPRECATED_FOR_REMOVAL` | Works on Java 21; plan before the next JDK upgrade. |
| `DEPRECATED` (`OPTIONAL_CLEANUP`) | Works on Java 21; fix only when touching the code. |
| `RUNTIME_RISK` (for example `setAccessible(true)`) | Only fails when the target is a JDK-internal class; verify the target. |

The WL14 target is WebLogic 14.1.2 on Java 21. It is still Java EE 8, so the server supplies `javax.annotation`, `javax.transaction`, JAXB, JAX-WS, SAAJ, JAF, and JWS, even though JDK 21 does not. The `wl14` profile reports them as `PLATFORM_PROVIDED` in `Source Findings` only, and they are counted on `📊 Summary`. Keep the `javax.*` imports, and declare the API jar with `provided` scope (or compile against the WebLogic 14.1.2 API jar) so the code compiles with JDK 21. Never migrate WL14 code to `jakarta.*`.

In `both` mode, findings with scanner `JDK Tools` come from `jdeprscan --release 21`, `jdeps --jdk-internals`, and a check of every `java.*` class/member reference against JDK 21. They are exact, because they are resolved by owner class and descriptor. Disable them with `--jdk-tools=false`.

### Trunk Validated and the compile check

Both features are on by default. Trunk is treated as already running on WebLogic 14.1.2 with Java 21 (`--trunk-validated=true`). For a component whose trunk is not validated yet, set its `Trunk Validated` column in `ComponentList.xlsx` to `No`, or pass `--trunk-validated=false` for the whole run. Each finding carries a `Trunk Status`:

| Trunk Status | Recommended Action |
|--------------|--------------------|
| `TRUNK_SAME_VALIDATED` | Not required. The same code already runs on the target, so leave it unchanged. It is excluded from the checklist and the effort. |
| `TRUNK_FIXED` | Port the change from the trunk file named in `Trunk Evidence`. |
| `TRUNK_REMOVED` | Trunk restructured the code. Review trunk before editing. |
| `TRUNK_SAME` | Trunk has the same code but is not marked validated. Confirm it with the trunk owner. |

For ground-truth Java 21 evidence without pre-built JARs, the compile check (`--compile-check`, default `true`; `--compile-check=false` skips it) compiles each checkout with `javac --release 21`. Findings with validation `CONFIRMED_COMPILER` or `CONFIRMED_SOURCE_AND_COMPILER` are the compiler's own verdict. For WL14, missing `javax.*` server APIs show up as `BUILD_CLASSPATH` (INFO) and are not code changes. Pass `--maven-settings=settings-local.xml` for Maven checkouts, or `--compile-classpath=<WebLogic API jar;lib folder>` for Ant checkouts, so the `Compile Check` status reaches `CLEAN` / `JAVA21_FINDINGS` rather than `INCOMPLETE_CLASSPATH`.

```powershell
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 --mode=both --source-inventory=ComponentList.xlsx `
  --maven-settings=settings-local.xml --output=WL14-Combined.xlsx
```

---

## 11. Operational Tips

- Close the output workbook before rerunning; Excel file locks can prevent report creation.
- Keep `.ea-workspace` between runs for faster updates.
- Use a clean workspace if repository state looks wrong:

```bat
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 ^
  --mode=repo ^
  --source-inventory=ComponentList.xlsx ^
  --clean-workspace=true ^
  --prompt-credentials=true ^
  --output=SourceInventory-Report.xlsx
```

- For direct Java, add memory if needed:

```bat
java -Xmx2g -jar EffortAnalyzer-2.0.0.jar --module=wl14 --mode=both --input=C:\Development\Documents\EffortAnalyzer\Jars --source-inventory=ComponentList.xlsx --output=WL14-Combined.xlsx
```

- Use `Ownership` in the workbook to make the report easier to route to teams.
- Use `Application Packages` to reduce noise and focus generated artifact correlation on application-owned code.

---

## 12. Troubleshooting

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| `Java not found` | Java is not on `PATH`. | Install Java 21+ or set `JAVA_HOME`; for `run.bat`, `DEFAULT_JAVA_HOME` can be set in the script. |
| `JAR not found: EffortAnalyzer-2.0.0.jar` | Tool was not built/copied to the run folder. | Copy/build `EffortAnalyzer-2.0.0.jar` next to the launcher. |
| `IBM scanner not found` | `binaryAppScanner.jar` is missing. | Copy `binaryAppScanner.jar` next to EffortAnalyzer or pass `--ibm-scanner=<path>`. |
| SVN command fails | SVN CLI missing, VPN disconnected, or first-time SSO/certificate trust not completed. | Verify `svn --version`, connect VPN, run manual `svn info`/`svn checkout` once, then rerun. |
| Authentication fails | Wrong or missing credentials. | Use `--prompt-credentials=true`; verify cached SVN/Git credentials. |
| Output workbook not created | File is open/locked or output folder does not exist. | Close Excel; create the output folder manually. |
| `No generated JAR/WAR/EAR files were found` in `both` mode | `Generated JARs` values do not match files under checkout or artifact folder. | Build/copy artifacts into the JAR folder; correct workbook names/paths/globs; rerun. |
| Findings are `CANDIDATE_SOURCE_ONLY` | Source finding has no matching generated artifact evidence. | Populate `Generated JARs` and `Application Packages`, build/copy the artifact, rerun `both`. |
| Checkout errors sheet has rows | Some repositories failed but the tool continued. | Fix repository URL, type, branch/revision, path, credentials, or network; optionally rerun with clean workspace. |

---

## 13. Quick Command Reference

### Source-only WL14 inventory

```bat
run.bat wl14 repo ComponentList.xlsx SourceInventory-Report.xlsx
```

### Combined WL14 source + generated artifact report

```bat
run.bat wl14 both C:\Development\Documents\EffortAnalyzer\Jars ComponentList.xlsx WL14-Combined.xlsx
```

### Direct Java source-only

```bat
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 --mode=repo --source-inventory=ComponentList.xlsx --prompt-credentials=true --output=SourceInventory-Report.xlsx
```

### Direct Java combined

```bat
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 --mode=both --input=C:\Development\Documents\EffortAnalyzer\Jars --source-inventory=ComponentList.xlsx --prompt-credentials=true --output=WL14-Combined.xlsx
```

---

## 14. Recommended Sharing Notes

When sending the report to management and potential users, include these notes:

- Start with `📊 Summary` for overall risk and counts.
- Use `🎯 Action Items` as the primary remediation backlog.
- `both` mode is preferred for final assessment because it confirms source findings with generated artifact evidence.
- Source-only findings are still actionable, especially when ownership is clear, but should be validated by building the component and populating `Generated JARs` before final commitment.
- The quality of the output depends on the quality of `ComponentList.xlsx`, especially `Generated JARs`, `Application Packages`, and `Ownership`.