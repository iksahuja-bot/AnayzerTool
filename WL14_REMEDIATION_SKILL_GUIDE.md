# WL14 remediation skill guide

The `wl14-report-remediation` Cursor skill takes an EffortAnalyzer WL14 workbook (for
example `WL14-Combined.xlsx`) and the `.ea-workspace` folder created with it. It then:

1. Reads every action item from the workbook's hidden `Action Items Raw` sheet.
2. Scans the **fixable/current** checkout and the **trunk/reference** checkout for each finding.
3. Fixes the safe findings in the fixable/current checkouts only. Trunk is never modified.
4. Validates the patched repositories (Maven, Ant, or a `javac --release 21` fallback).
5. Writes a report of what was fixed, what is pending and why, which findings are false positives
   or need no change, and where trunk has the same code. It also exports one `.patch` file per repository.

Nothing is committed. You review the patches and commit them yourself.

---

## 1. What is in the skill

```text
wl14-report-remediation\
  SKILL.md                      instructions the Cursor agent follows
  examples.md                   sample prompts and worked decisions
  reference\rule-playbook.md    how each rule family is decided and fixed
  scripts\
    _common.ps1                 shared helpers
    export-action-items.ps1     workbook -> action-items.json (no Excel needed)
    scan-evidence.ps1           current vs trunk evidence, false-positive detection
    check-patch-hygiene.ps1     line-ending/BOM guard, trunk-untouched check, patch export
    validate-maven-javac.ps1    compile fallback when "mvn compile" is blocked
    write-report.ps1            remediation-report.md / .csv
```

Master copy:

```text
C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation
```

Ready-to-share zip:

```text
C:\Development\Documents\Deliverable\wl14-report-remediation-skill.zip
```

---

## 2. Prerequisites (each team member)

| Tool | Why | Check |
|---|---|---|
| Cursor (Agent mode) | runs the skill | - |
| Windows PowerShell 5.1 or PowerShell 7 | runs the scripts | `$PSVersionTable.PSVersion` |
| SVN command-line client | diff/status/revert of `.ea-workspace` checkouts | `svn --version --quiet` |
| JDK 21 + `JAVA_HOME` | validation | `java -version` |
| Maven 3.8+ with corporate settings (Nexus) | PAL-style Maven components | `mvn -v` |
| Ant + BSystem (`BS4SVN_HOME`) + Ivy | optional, for Ant components | `ant -version` |

Excel is **not** required, and the workbook may stay open while the skill runs.
TortoiseSVN users need the "command line client tools" option, which isn't installed by default.

Run everything from native Windows PowerShell, not WSL. Under WSL, `JAVA_HOME` and `ant` are not available.

---

## 3. Install the skill

Pick one of these options.

### Option A: personal skill (recommended)

This makes the skill available in every folder you open in Cursor. It doesn't depend on the
EffortAnalyzer repository; it only needs the workbook and the workspace.

```powershell
$zip = "C:\Development\Documents\Deliverable\wl14-report-remediation-skill.zip"   # or wherever you received it
$dest = "$env:USERPROFILE\.cursor\skills"
New-Item -ItemType Directory -Force $dest | Out-Null
Expand-Archive $zip -DestinationPath $dest -Force
Get-ChildItem "$dest\wl14-report-remediation" -Recurse | Unblock-File
Test-Path "$dest\wl14-report-remediation\SKILL.md"    # must print True
```

`Unblock-File` removes the "downloaded from the internet" flag Windows adds to files received via Teams or email.

### Option B: project skill (one repository)

Copy the folder into the repository you open in Cursor:

```powershell
Copy-Item -Recurse -Force `
  "C:\Development\Documents\EffortAnalyzer\EffortAnalyzer\.cursor\skills\wl14-report-remediation" `
  "C:\Path\To\Repo\.cursor\skills\wl14-report-remediation"
```

### Option C: share through Git

The EffortAnalyzer `.gitignore` currently ignores the whole `.cursor/` folder, so skills are not
pushed. To version the project skills, replace the `.cursor/` line in `.gitignore` with:

```gitignore
.cursor/*
!.cursor/skills/
```

Then commit `.cursor/skills/wl14-report-remediation`. Team members get the skill with `git pull`.

### Updating the zip after you change the skill

```powershell
cd C:\Development\Documents\EffortAnalyzer\EffortAnalyzer
Compress-Archive -Path ".cursor\skills\wl14-report-remediation" `
  -DestinationPath "C:\Development\Documents\Deliverable\wl14-report-remediation-skill.zip" -Force
```

After installing or updating, restart Cursor (or open a new chat) so it picks up the skill.

---

## 4. Run it

1. Generate the WL14 workbook with EffortAnalyzer as usual. Keep the `.ea-workspace` folder next to it.
2. Open any folder in Cursor (EffortAnalyzer or the Deliverable folder). Results are written to
   `.cursor-output\wl14-remediation\` inside the opened folder.
3. Start a new chat in **Agent** mode and paste:

```text
Use the wl14-report-remediation skill.
ReportPath: C:\Development\Documents\Deliverable\WL14-Combined.xlsx
WorkspacePath: C:\Development\Documents\Deliverable\.ea-workspace
Fix what is safe, validate, and give me the report: fixed, pending, false positives,
and where trunk has the same code.
```

Useful variations:

```text
Only component: Common Utils v.10.0.8.1
Only action item IDs: WL14-AI-56B1556F, WL14-AI-CAE6C6DB
Review-only mode: do not change any file.
```

The agent asks before touching a checkout that already has local modifications.

---

## 5. Read the results

All files are in `.cursor-output\wl14-remediation\`:

| File | Content |
|---|---|
| `remediation-report.md` | the main report (summary, fixed, pending, false positives, trunk comparison, files, validation, next steps) |
| `remediation-report.csv` | the same rows for Excel filtering |
| `patches\<repository>.patch` | one SVN/Git diff per patched repository |
| `evidence-baseline.md` | per-item scan before patching (current vs trunk hit counts) |
| `evidence-after.json` | scan after patching (what is still left) |
| `decisions.json` | the agent's per-item decision and reasoning |
| `validation-*.log/json` | build/compile output |

Outcome meanings:

| Outcome | Meaning | What you do |
|---|---|---|
| `FIXED` | patched and compiled/built | review the patch, then commit |
| `PENDING_VALIDATION` | patched, but the build could not run here | run your normal build, then commit |
| `PENDING_MANUAL` | not patched: design decision or large change | assign to the component owner |
| `FALSE_POSITIVE` | the flagged code is not there or is not the flagged API | nothing; optionally tune the EffortAnalyzer rule |
| `NO_CHANGE_NEEDED` | the detection is real but harmless on WL14 | nothing |

Trunk comparison meanings:

| Value | Meaning |
|---|---|
| `TRUNK_SAME` | trunk has the same code or finding, so trunk offers no fix |
| `TRUNK_FIXED` | trunk no longer has the finding; the agent ports trunk's change or points to it |
| `TRUNK_FILES_NOT_FOUND` | the file or module no longer exists in trunk |
| `TRUNK_UNAVAILABLE` | no trunk checkout in the workspace |

The `Identical files x/y` detail shows how many candidate files are byte-identical to trunk.

### Workbooks generated with Trunk Validated / compile check

By default EffortAnalyzer treats trunk as already validated on WebLogic 14.1.2 with Java 21 (`--trunk-validated=true`). It also compiles each checkout for compiler-confirmed evidence (`--compile-check=true`). Set `Trunk Validated = No` in `ComponentList.xlsx` for a trunk that is not validated yet:

```powershell
java -jar EffortAnalyzer-2.0.0.jar --module=wl14 --mode=both --source-inventory=ComponentList.xlsx `
  --maven-settings=settings-local.xml --output=WL14-Combined.xlsx
```

The workbook then decides the trunk-same question itself. Items that are identical in the validated trunk are marked **NOT REQUIRED** (`trunk_status = TRUNK_SAME_VALIDATED`). The skill reports them as no change needed and does not patch them. `TRUNK_FIXED` items name the trunk file to port from in `trunk_evidence`.

---

## 6. Review, commit or undo the fixes

The fixes are in the checkouts under `.ea-workspace`:

```powershell
cd "C:\Development\Documents\Deliverable\.ea-workspace\<fixable checkout>"
svn status
svn diff
svn commit -m "WL14: <summary> (WL14-AI-xxxx)"     # when satisfied
svn revert -R .                                     # to undo everything in that checkout
```

To apply a patch to another checkout of the same branch:

```powershell
cd C:\path\to\other\checkout
svn patch C:\...\.cursor-output\wl14-remediation\patches\<repository>.patch
```

Then rebuild the components and re-run EffortAnalyzer WL14 in `both` mode. The fixed findings should drop out.

---

## 7. Guardrails built into the skill

- Patches only `fixable_source_path` checkouts. `check-patch-hygiene.ps1` fails if any trunk checkout is modified.
- **Never renames `javax.*` to `jakarta.*` for WL14.** WebLogic 14.1.2 on Java 21 is still Java EE 8 and needs `javax`, even though the
  workbook's `Replacement Symbol` column suggests `jakarta`. Only WL15 workbooks get jakarta migrations.
- Minimal line-level edits. Line endings and BOM are restored to match SVN, so a one-line fix shows as a one-line diff.
- Never copies whole trunk files; it ports only the specific change.
- A regex hit is not treated as proof. For example, `Constructor.newInstance()` is not the deprecated `Class.newInstance()`.
- Validation failures and blockers are reported, never hidden.

---

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `BLOCKED: Workbook does not contain required sheet 'Action Items Raw'` | the workbook was produced by an older EffortAnalyzer; regenerate it with the current version |
| `mvn compile` fails in `pom-validator-maven-plugin ... org/sonatype/aether/RepositorySystem` | the corporate plugin needs Maven 3.0.x. The skill falls back to `validate-maven-javac.ps1`; run the official build in the CI/build environment |
| `ant` not found / `Component BSystem is not available` | Ant components need Ant + `BS4SVN_HOME`; items stay `PENDING_VALIDATION` until you build them |
| `svn` not recognized | install the SVN command-line client and reopen Cursor |
| scripts blocked "not digitally signed" | run `Get-ChildItem <skill folder> -Recurse \| Unblock-File`; the agent calls them with `-ExecutionPolicy Bypass` anyway |
| every line shows as changed in `svn diff` | run `check-patch-hygiene.ps1 -FixLineEndings` (the skill does this automatically) |

---

## 9. Results of the first run (WL14-Combined.xlsx, 7 Oct 2026)

| Metric | Count |
|---|---:|
| Action items | 27 |
| Fixed (patched + compiled) | 1 |
| Pending validation (patched, Ant/official Maven build blocked) | 3 |
| Pending manual (CGLIB to ByteBuddy, DefaultContext reflection) | 3 |
| False positive (full) | 0 |
| No change needed on WL14 (`javax.*` APIs provided, test reflection) | 20 |
| Trunk has the same code | 21 |
| Trunk already fixed (CGLIB, Java level) | 3 |

Changed: 7 files, 11 changed lines, across Cluster and Runtime Services, Common Utils and
Platform Abstraction Layer. One additional trunk-backed fix was made outside the workbook
(`sun.misc.Service` replaced with `ServiceLoader`). Full details are in `.cursor-output\wl14-remediation\remediation-report.md`.
