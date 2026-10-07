# WL14 remediation skill guide

This guide explains how to use and share the Cursor skill created at:

```text
C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation
```

The skill is designed for a WL14 combined report such as:

```text
C:\Development\Documents\Deliverable\WL14-Combined.xlsx
```

and the workspace created while generating that report:

```text
C:\Development\Documents\Deliverable\.ea-workspace
```

## What the skill does

The skill guides an AI coding agent to:

1. Read the hidden `Action Items Raw` sheet from the WL14 workbook.
2. Use `fixable_source_path` / `Fixable Source Location` as the patch target.
3. Use `trunk_source_path` / `Trunk Source Location` as read-only comparison input.
4. Fix safe, localized source findings in the checked-out current/fixable repositories.
5. Confirm whether each finding is also present in trunk/reference code.
6. Identify false positives or no-longer-present findings.
7. Run validation where possible.
8. Produce a remediation report showing what was fixed and what is pending.

The skill does **not** automatically copy trunk over current source. It only uses
trunk as evidence for focused, minimal patches.

## Files included

```text
.cursor\skills\wl14-report-remediation\SKILL.md
.cursor\skills\wl14-report-remediation\examples.md
.cursor\skills\wl14-report-remediation\scripts\export-action-items.ps1
.cursor\skills\wl14-report-remediation\templates\remediation-report-template.md
```

## Before you run it

Close the workbook in Excel before extraction or report regeneration.

Confirm these exist:

```powershell
Test-Path "C:\Development\Documents\Deliverable\WL14-Combined.xlsx"
Test-Path "C:\Development\Documents\Deliverable\.ea-workspace"
```

Optional but recommended:

```powershell
svn --version
java -version
mvn -v
```

SVN is only required if you plan to inspect/update SVN metadata. The skill can
work from the already-created `.ea-workspace` without doing new checkouts.

## Recommended prompt to use

Open the EffortAnalyzer repository in Cursor, then paste this prompt:

```text
Use the wl14-report-remediation skill.

ReportPath: C:\Development\Documents\Deliverable\WL14-Combined.xlsx
WorkspacePath: C:\Development\Documents\Deliverable\.ea-workspace

Please remediate safe HIGH and MEDIUM automation-readiness items first.
Patch only the fixable/current source paths from the workbook.
Treat trunk/reference source as read-only comparison input.
For every item, report whether it was fixed, pending, false positive/no-longer-present, or trunk has the same code.
Run validation where possible and share the final remediation report.
```

To review without edits, add:

```text
Review-only mode: do not change files.
```

To restrict scope, add one of:

```text
Only component: <component name>
Only action item IDs: <id1>, <id2>, <id3>
```

## Manual extraction command

The skill normally runs this itself, but you can run it manually from the
EffortAnalyzer repository root:

```powershell
$skillDir = "C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation"
& "$skillDir\scripts\export-action-items.ps1" `
  -ReportPath "C:\Development\Documents\Deliverable\WL14-Combined.xlsx" `
  -WorkspacePath "C:\Development\Documents\Deliverable\.ea-workspace" `
  -OutputDirectory ".cursor-output\wl14-remediation"
```

Expected output files:

```text
.cursor-output\wl14-remediation\action-items.json
.cursor-output\wl14-remediation\summary.json
.cursor-output\wl14-remediation\remediation-report.md
```

## How to review the fixes

The fixes are made inside repositories under:

```text
C:\Development\Documents\Deliverable\.ea-workspace
```

For each changed repository/component:

```powershell
cd "<fixable_source_path from the report>"
git status      # if Git checkout
svn status      # if SVN checkout
```

Review changed files and run the validation command listed in the remediation
report. Common examples:

```powershell
mvn -q test
mvn -q -DskipTests package
.\gradlew test
```

If validation cannot run because of `JAVA_HOME`, Maven settings, Nexus, or
credentials, the skill should mark the item as `PENDING_VALIDATION` and copy the
error into the report.

## Expected final report

The final report should include:

- report path and workspace path used;
- number of action items reviewed;
- fixed items;
- pending items and blockers;
- false positives / no-longer-present findings;
- items where trunk/reference has the same problematic code;
- files changed by component;
- validation commands and results;
- next steps.

The report skeleton is created at:

```text
.cursor-output\wl14-remediation\remediation-report.md
```

## How to share with team members

Share this folder:

```text
C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation
```

Team members should copy it into the same relative location in their working
repository:

```text
<their-repo>\.cursor\skills\wl14-report-remediation
```

For example:

```powershell
Copy-Item `
  -Recurse `
  -Force `
  "C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation" `
  "C:\Path\To\Their\Repo\.cursor\skills\wl14-report-remediation"
```

They should then open their repository in Cursor and use the prompt from
"Recommended prompt to use", changing `ReportPath` and `WorkspacePath` to their
local paths.

## Sharing as a zip

From the EffortAnalyzer repository root:

```powershell
Compress-Archive `
  -Path ".cursor\skills\wl14-report-remediation" `
  -DestinationPath "wl14-report-remediation-skill.zip" `
  -Force
```

Recipient install command:

```powershell
Expand-Archive "wl14-report-remediation-skill.zip" -DestinationPath "<their-repo>\.cursor\skills" -Force
```

After extraction, confirm this file exists:

```text
<their-repo>\.cursor\skills\wl14-report-remediation\SKILL.md
```

## Guardrails for the team

- Patch only paths listed as `fixable_source_path`.
- Never edit paths listed as `trunk_source_path`.
- Never patch generated binaries or build outputs.
- Do not treat trunk as automatically correct; compare the actual code.
- If current and trunk both contain the same finding, report `TRUNK_SAME`.
- If current source no longer contains the flagged symbol, report a false
  positive/no-longer-present finding rather than making unrelated edits.
- Always run or record validation.

## Recommended follow-up after fixes

1. Review diffs in each component repository.
2. Resolve validation blockers such as Maven/JAVA_HOME/Nexus access.
3. Rebuild affected components.
4. Regenerate the WL14 combined report using `both` mode.
5. Confirm fixed action items disappear or move to a resolved/validated state.