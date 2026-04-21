# =============================================================================
#  EffortAnalyzer — PowerShell Launcher
#  Version: 2.0.0
#
#  USAGE:
#    .\run.ps1                                      # interactive menu
#    .\run.ps1 -Module upgrade    -Input "C:\lib"
#    .\run.ps1 -Module wl-jboss26 -Input "C:\lib"
#    .\run.ps1 -Module wl-jboss27 -Input "C:\lib"
#    .\run.ps1 -Module analyze
#    .\run.ps1 -Module merge -TicketFile "tickets.xlsx" -ComponentFile "comp.xlsx"
#    .\run.ps1 -Help
# =============================================================================

param(
    [string] $Module        = "",
    [string] $Input         = "",
    [string] $Output        = "",
    [string] $TicketFile    = "",
    [string] $ComponentFile = "",
    [string] $JarList       = "",
    [string] $IbmScanner    = "",
    [string] $Config        = "",
    [string] $JavaHome      = "",
    [switch] $OpenReport,
    [switch] $Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Constants ─────────────────────────────────────────────────────────────────
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
$JAR_PATH   = Join-Path $SCRIPT_DIR "EffortAnalyzer-2.0.0.jar"

# Default output filenames per module
$DEFAULT_OUTPUT = @{
    "upgrade"    = "Upgrade-Compatibility-Report.xlsx"
    "wl-jboss26" = "WlToJBoss-WildFly26-Report.xlsx"
    "wl-jboss27" = "WlToJBoss-WildFly27-Report.xlsx"
    "wl-jboss"   = "WlToJBossMigrationReport.xlsx"
    "analyze"    = "AnalyzerOutput.xlsx"
    "merge"      = "MergedOutput.xlsx"
}

$VALID_MODULES = @("upgrade", "wl-jboss26", "wl-jboss27", "wl-jboss", "analyze", "merge")

# ── Output helpers ─────────────────────────────────────────────────────────────
function Write-Banner {
    Write-Host ""
    Write-Host "  +===================================================+" -ForegroundColor DarkBlue
    Write-Host "  |  EffortAnalyzer v2.0.0                            |" -ForegroundColor DarkBlue
    Write-Host "  |  Migration Analysis & Effort Estimation Tool      |" -ForegroundColor DarkBlue
    Write-Host "  +===================================================+" -ForegroundColor DarkBlue
    Write-Host ""
}

function Write-Section($text) { Write-Host ""; Write-Host "  -- $text" -ForegroundColor Cyan }
function Write-Ok($text)      { Write-Host "  [OK]  $text" -ForegroundColor Green }
function Write-Warn($text)    { Write-Host "  [!]   $text" -ForegroundColor Yellow }
function Write-Err($text)     { Write-Host "  [ERR] $text" -ForegroundColor Red }
function Write-Info($text)    { Write-Host "  [>>]  $text" -ForegroundColor Gray }

# ── Help ──────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Banner
    Write-Host "  PARAMETERS" -ForegroundColor White
    Write-Host ""
    Write-Host "    -Module        <string>   Module to run:"
    Write-Host "                              upgrade    - Java 21 JVM + Spring/Guava/Guice/CGLib scan"
    Write-Host "                              wl-jboss26 - WebLogic to WildFly 26 / EAP 7.4 (Java 8)"
    Write-Host "                              wl-jboss27 - WebLogic to WildFly 27+ / EAP 8  (Java 21)"
    Write-Host "                              analyze    - IBM Transformation Advisor report"
    Write-Host "                              merge      - Merge ticket + component Excel files"
    Write-Host ""
    Write-Host "    -Input         <path>     JAR/WAR/EAR file or directory of archives"
    Write-Host "                             (required for: upgrade, wl-jboss26, wl-jboss27)"
    Write-Host "                             (optional  for: analyze — directory of IBM TA *.json reports)"
    Write-Host "    -IbmScanner    <path>     Path to binaryAppScanner.jar"
    Write-Host "                             (upgrade only; default: auto-detect from script folder)"
    Write-Host "                             IBM JSON results are saved to 'reports\' next to this script."
    Write-Host "                             The folder is created and cleaned automatically before each run."
    Write-Host "    -Output        <path>     Output Excel report path"
    Write-Host "    -JarList       <path>     Text file listing JAR paths (one per line)"
    Write-Host "    -TicketFile    <path>     Ticket Excel file (merge module)"
    Write-Host "    -ComponentFile <path>     Component Excel file (merge module)"
    Write-Host "    -Config        <path>     Custom analyzer.properties file"
    Write-Host "    -JavaHome      <path>     Override JAVA_HOME for this run"
    Write-Host "    -OpenReport               Open the Excel report after completion"
    Write-Host "    -Help                     Show this help"
    Write-Host ""
    Write-Host "  EXAMPLES" -ForegroundColor White
    Write-Host ""
    Write-Host "    .\run.ps1"
    Write-Host "    .\run.ps1 -Module upgrade    -Input C:\app\lib -Output report.xlsx"
    Write-Host "    .\run.ps1 -Module upgrade    -JarList jars.txt -OpenReport"
    Write-Host "    .\run.ps1 -Module upgrade    -Input C:\app\lib -IbmScanner C:\tools\binaryAppScanner.jar"
    Write-Host "    .\run.ps1 -Module wl-jboss26 -Input C:\app\lib"
    Write-Host "    .\run.ps1 -Module wl-jboss27 -Input C:\app\lib"
    Write-Host "    .\run.ps1 -Module analyze"
    Write-Host "    .\run.ps1 -Module analyze -Input C:\reports\json -Output AnalyzerOutput.xlsx"
    Write-Host "    .\run.ps1 -Module merge -TicketFile tickets.xlsx -ComponentFile comp.xlsx"
    Write-Host ""
    Write-Host "  EXCLUSIONS" -ForegroundColor White
    Write-Host ""
    Write-Host "    Edit upgrade-excluded-rules.txt to suppress already-fixed findings."
    Write-Host "    Accepts our rule IDs (JR-001) or IBM TA names (RemovedJaxBModuleNotProvided)."
    Write-Host ""
}

# ── Java detection ────────────────────────────────────────────────────────────
function Find-Java {
    if ($JavaHome -ne "") {
        $candidate = Join-Path $JavaHome "bin\java.exe"
        if (Test-Path $candidate) { return $candidate }
        Write-Err "-JavaHome '$JavaHome' does not contain bin\java.exe"
        exit 1
    }

    if ($env:JAVA_HOME -ne "" -and $null -ne $env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    $searchRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Semeru",
        "C:\Program Files\Liberica",
        "C:\Development"
    )
    foreach ($root in $searchRoots) {
        if (Test-Path $root) {
            $found = Get-ChildItem $root -ErrorAction SilentlyContinue |
                     Where-Object { $_.Name -match "21|22|23|24" } |
                     Select-Object -First 1
            if ($found) {
                $candidate = Join-Path $found.FullName "bin\java.exe"
                if (Test-Path $candidate) { return $candidate }
            }
        }
    }

    $onPath = (Get-Command java -ErrorAction SilentlyContinue)
    if ($onPath) { return $onPath.Source }

    Write-Err "Java not found. Install Java 21+ and set JAVA_HOME, or pass -JavaHome."
    exit 1
}

function Assert-JavaVersion($javaExe) {
    $ver = & $javaExe -version 2>&1 | Select-String "version" | Select-Object -First 1
    Write-Info "Java: $ver"
    if ($ver -notmatch 'version "(21|22|23|24)') {
        Write-Warn "Java 21+ is required. Found: $ver"
        $answer = Read-Host "  Continue anyway? (y/N)"
        if ($answer -ne "y" -and $answer -ne "Y") { exit 1 }
    }
}

# ── JAR check ─────────────────────────────────────────────────────────────────
function Assert-Jar {
    if (-not (Test-Path $JAR_PATH)) {
        Write-Err "JAR not found: $JAR_PATH"
        Write-Host ""
        Write-Host "  Build the project first:" -ForegroundColor Yellow
        Write-Host "    mvn clean package -DskipTests -s settings-local.xml" -ForegroundColor Yellow
        exit 1
    }
    $size = [math]::Round((Get-Item $JAR_PATH).Length / 1MB, 1)
    Write-Ok "JAR: $JAR_PATH ($size MB)"
}

# ── Interactive menu ──────────────────────────────────────────────────────────
function Prompt-Path($label, $default = "") {
    $hint = if ($default -ne "") { " [default: $default]" } else { "" }
    $val  = Read-Host "  $label$hint"
    if ($val -eq "" -and $default -ne "") { return $default }
    return $val
}

function Run-Interactive {
    Write-Host "  Select a module to run:" -ForegroundColor White
    Write-Host ""
    Write-Host "    [1]  Upgrade Compatibility Analyzer"
    Write-Host "           Java 8->21 JVM + Spring 5.3.39 / Guava 31.1 / Guice 5.1 / CGLib->ByteBuddy"
    Write-Host "    [2]  WebLogic -> WildFly 26 / JBoss EAP 7.4"
    Write-Host "           Java 8  |  Jakarta EE 8  |  javax.*  (no namespace migration)"
    Write-Host "    [3]  WebLogic -> WildFly 27+ / JBoss EAP 8"
    Write-Host "           Java 21 |  Jakarta EE 10 |  jakarta.* (namespace migration required)"
    Write-Host "    [4]  IBM Transformation Advisor Report Analyzer"
    Write-Host "    [5]  Excel Merger (Tickets + Components)"
    Write-Host "    [6]  Help"
    Write-Host "    [Q]  Quit"
    Write-Host ""
    $choice = Read-Host "  Enter choice"

    switch ($choice.ToUpper()) {
        "1" {
            $script:Module = "upgrade"
            Write-Host ""
            Write-Host "  IBM binaryAppScanner.jar is auto-detected from this script's folder." -ForegroundColor Gray
            Write-Host "  If not present, download from:" -ForegroundColor Gray
            Write-Host "    https://www.ibm.com/support/pages/migration-toolkit-application-binaries" -ForegroundColor Cyan
            Write-Host ""
            $script:Input  = Prompt-Path "Path to JAR file or directory of JARs"
            $script:Output = Prompt-Path "Output Excel file" "Upgrade-Compatibility-Report.xlsx"
        }
        "2" {
            $script:Module = "wl-jboss26"
            $script:Input  = Prompt-Path "Path to JAR/WAR/EAR file or directory"
            $script:Output = Prompt-Path "Output Excel file" "WlToJBoss-WildFly26-Report.xlsx"
        }
        "3" {
            $script:Module = "wl-jboss27"
            $script:Input  = Prompt-Path "Path to JAR/WAR/EAR file or directory"
            $script:Output = Prompt-Path "Output Excel file" "WlToJBoss-WildFly27-Report.xlsx"
        }
        "4" {
            $script:Module = "analyze"
            Write-Host ""
            Write-Host "  Enter the directory containing IBM TA JSON report files." -ForegroundColor Gray
            Write-Host "  Leave blank to use the embedded reports folder inside the JAR." -ForegroundColor Gray
            Write-Host ""
            $script:Input  = Prompt-Path "JSON reports directory (optional, press Enter to skip)" ""
            $script:Output = Prompt-Path "Output Excel file" "AnalyzerOutput.xlsx"
        }
        "5" {
            $script:Module        = "merge"
            $script:TicketFile    = Prompt-Path "Ticket Excel file"
            $script:ComponentFile = Prompt-Path "Component Excel file"
            $script:Output        = Prompt-Path "Output Excel file" "MergedOutput.xlsx"
        }
        "6" { Show-Help; exit 0 }
        "Q" { exit 0 }
        default { Write-Err "Invalid choice. Enter 1-6 or Q."; exit 1 }
    }
}

# ── IBM JSON reports folder management ────────────────────────────────────────
function Prepare-ReportsFolder {
    $reportsDir = Join-Path $SCRIPT_DIR "reports"
    Write-Host ""
    if (Test-Path $reportsDir) {
        Write-Info "Cleaning reports folder: $reportsDir"
        Get-ChildItem -Path $reportsDir -ErrorAction SilentlyContinue |
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
        Write-Ok "Reports folder cleaned."
    } else {
        Write-Info "Creating reports folder: $reportsDir"
        New-Item -ItemType Directory -Path $reportsDir | Out-Null
        Write-Ok "Reports folder created."
    }
}

# ── Build the java argument list ───────────────────────────────────────────────
function Build-Args {
    $javaArgs = @("--module=$Module")

    if ($Output -ne "") {
        $javaArgs += "--output=$Output"
    } elseif ($DEFAULT_OUTPUT.ContainsKey($Module)) {
        $javaArgs += "--output=$($DEFAULT_OUTPUT[$Module])"
    }

    if ($Input         -ne "") { $javaArgs += "--input=$Input" }
    if ($JarList       -ne "") { $javaArgs += "--jar-list=$JarList" }
    if ($IbmScanner    -ne "") { $javaArgs += "--ibm-scanner=$IbmScanner" }
    if ($TicketFile    -ne "") { $javaArgs += "--ticket-file=$TicketFile" }
    if ($ComponentFile -ne "") { $javaArgs += "--component-file=$ComponentFile" }
    if ($Config        -ne "") { $javaArgs += "--config=$Config" }

    return $javaArgs
}

# ── Main ──────────────────────────────────────────────────────────────────────
Write-Banner

if ($Help) { Show-Help; exit 0 }

if ($Module -eq "") { Run-Interactive }

if ($Module -notin $VALID_MODULES) {
    Write-Err "Unknown module '$Module'. Valid modules: $($VALID_MODULES -join ' | ')"
    exit 1
}

Write-Section "Environment"
$javaExe = Find-Java
Assert-JavaVersion $javaExe
Assert-Jar

if ($Module -eq "upgrade") {
    Write-Section "Preparing IBM JSON reports folder"
    Prepare-ReportsFolder
}

$effectiveOutput = if ($Output -ne "") { $Output } elseif ($DEFAULT_OUTPUT.ContainsKey($Module)) { $DEFAULT_OUTPUT[$Module] } else { "output.xlsx" }
$effectiveOutput = [System.IO.Path]::GetFullPath($effectiveOutput)

$appArgs = Build-Args

Write-Section "Running module: $Module"
Write-Info "Command: java -jar `"$JAR_PATH`" $($appArgs -join ' ')"
Write-Host ""

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& $javaExe -jar $JAR_PATH @appArgs
$exitCode = $LASTEXITCODE
$stopwatch.Stop()

Write-Host ""
$elapsed = [math]::Round($stopwatch.Elapsed.TotalSeconds, 1)

if ($exitCode -eq 0) {
    Write-Ok "Completed in ${elapsed}s"
    if (Test-Path $effectiveOutput) {
        $sizeKB = [math]::Round((Get-Item $effectiveOutput).Length / 1KB, 0)
        Write-Ok "Report: $effectiveOutput  (${sizeKB} KB)"
        if ($OpenReport) {
            Write-Info "Opening report..."
            Start-Process $effectiveOutput
        }
    } else {
        Write-Warn "Report not found at: $effectiveOutput"
        Write-Warn "Check the console output above for the actual location."
    }
} else {
    Write-Err "Process exited with code $exitCode. Check output above for details."
    exit $exitCode
}
Write-Host ""
