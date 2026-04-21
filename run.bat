@echo off
setlocal EnableDelayedExpansion

:: =============================================================================
::  EffortAnalyzer — Windows Batch Launcher
::  Version: 2.0.0
::
::  USAGE (interactive):
::    run.bat
::
::  USAGE (with arguments):
::    run.bat upgrade    C:\app\lib  report.xlsx
::    run.bat wl-jboss26 C:\app\lib  migration26.xlsx
::    run.bat wl-jboss27 C:\app\lib  migration27.xlsx
::    run.bat analyze    C:\reports\json  ta-analysis.xlsx
::    run.bat analyze                     ta-analysis.xlsx   (uses embedded reports)
::    run.bat merge
::    run.bat help
::
::  Arguments: [module] [input-path] [output-file]
:: =============================================================================

:: ── Configuration ─────────────────────────────────────────────────────────────
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%EffortAnalyzer-2.0.0.jar"

:: Edit this if Java is not on your PATH
set "DEFAULT_JAVA_HOME="
:: Example: set "DEFAULT_JAVA_HOME=C:\Program Files\Java\jdk-21"

:: ── Banner ────────────────────────────────────────────────────────────────────
call :print_banner

:: ── Handle command-line arguments ─────────────────────────────────────────────
if /i "%~1"=="help"       goto :show_help
if /i "%~1"=="-h"         goto :show_help
if /i "%~1"=="--help"     goto :show_help
if /i "%~1"=="upgrade"    goto :run_from_args
if /i "%~1"=="wl-jboss26" goto :run_from_args
if /i "%~1"=="wl-jboss27" goto :run_from_args
if /i "%~1"=="wl-jboss"   goto :run_from_args
if /i "%~1"=="analyze"    goto :run_from_args
if /i "%~1"=="merge"      goto :run_from_args
if not "%~1"==""          goto :show_help

:: No arguments — show interactive menu
goto :interactive_menu

:: ── Run from command-line arguments ───────────────────────────────────────────
:run_from_args
set "ARG_MODULE=%~1"
set "ARG_INPUT=%~2"
set "ARG_OUTPUT=%~3"
call :find_java
if errorlevel 1 goto :end
call :check_jar
if errorlevel 1 goto :end
call :run_module
goto :end

:: ── Interactive menu ──────────────────────────────────────────────────────────
:interactive_menu
echo   Select a module to run:
echo.
echo     [1]  Upgrade Compatibility Analyzer
echo            Java 8 ^> Java 21 JVM + Spring 5.3.39 / Guava 31.1 / Guice 5.1 / CGLib^>ByteBuddy
echo.
echo     [2]  WebLogic ^> WildFly 26 / JBoss EAP 7.4
echo            Java 8  ^|  Jakarta EE 8  ^|  javax.*  ^(no namespace migration^)
echo.
echo     [3]  WebLogic ^> WildFly 27+ / JBoss EAP 8
echo            Java 21 ^|  Jakarta EE 10 ^|  jakarta.* ^(namespace migration required^)
echo.
echo     [4]  IBM Transformation Advisor Report Analyzer
echo.
echo     [5]  Excel Merger  (Ticket list + Component list)
echo.
echo     [6]  Help
echo.
echo     [Q]  Quit
echo.
set /p CHOICE="  Enter choice: "

if /i "!CHOICE!"=="1" goto :menu_upgrade
if /i "!CHOICE!"=="2" goto :menu_wljboss26
if /i "!CHOICE!"=="3" goto :menu_wljboss27
if /i "!CHOICE!"=="4" goto :menu_analyze
if /i "!CHOICE!"=="5" goto :menu_merge
if /i "!CHOICE!"=="6" goto :show_help
if /i "!CHOICE!"=="Q" goto :end
echo   [!] Invalid choice. Please enter 1-6 or Q.
goto :interactive_menu

:: ── Menu handlers ─────────────────────────────────────────────────────────────

:menu_upgrade
set "ARG_MODULE=upgrade"
echo.
echo   IBM Migration Toolkit (binaryAppScanner.jar) is auto-detected from
echo   the same folder as this script. Download it if not present:
echo     https://www.ibm.com/support/pages/migration-toolkit-application-binaries
echo.
set /p ARG_INPUT="  Path to JAR file or directory of JARs: "
set /p ARG_OUTPUT="  Output Excel file [Upgrade-Compatibility-Report.xlsx]: "
if "!ARG_OUTPUT!"=="" set "ARG_OUTPUT=Upgrade-Compatibility-Report.xlsx"
goto :confirm

:menu_wljboss26
set "ARG_MODULE=wl-jboss26"
echo.
set /p ARG_INPUT="  Path to JAR/WAR/EAR file or directory: "
set /p ARG_OUTPUT="  Output Excel file [WlToJBoss-WildFly26-Report.xlsx]: "
if "!ARG_OUTPUT!"=="" set "ARG_OUTPUT=WlToJBoss-WildFly26-Report.xlsx"
goto :confirm

:menu_wljboss27
set "ARG_MODULE=wl-jboss27"
echo.
set /p ARG_INPUT="  Path to JAR/WAR/EAR file or directory: "
set /p ARG_OUTPUT="  Output Excel file [WlToJBoss-WildFly27-Report.xlsx]: "
if "!ARG_OUTPUT!"=="" set "ARG_OUTPUT=WlToJBoss-WildFly27-Report.xlsx"
goto :confirm

:menu_analyze
set "ARG_MODULE=analyze"
echo.
echo   Enter the directory containing IBM TA JSON report files.
echo   Leave blank to use the embedded reports folder inside the JAR.
echo.
set /p ARG_INPUT="  JSON reports directory (optional): "
set /p ARG_OUTPUT="  Output Excel file [AnalyzerOutput.xlsx]: "
if "!ARG_OUTPUT!"=="" set "ARG_OUTPUT=AnalyzerOutput.xlsx"
goto :confirm

:menu_merge
set "ARG_MODULE=merge"
set "ARG_INPUT="
echo.
set /p TICKET_FILE="  Ticket Excel file: "
set /p COMP_FILE="  Component Excel file: "
set /p ARG_OUTPUT="  Output Excel file [MergedOutput.xlsx]: "
if "!ARG_OUTPUT!"=="" set "ARG_OUTPUT=MergedOutput.xlsx"
goto :confirm_merge

:confirm
echo.
echo   +---------------------------------------------+
echo   ^|  Module : !ARG_MODULE!
echo   ^|  Input  : !ARG_INPUT!
echo   ^|  Output : !ARG_OUTPUT!
echo   +---------------------------------------------+
echo.
set /p CONFIRM="  Proceed? (Y/n): "
if /i "!CONFIRM!"=="n" goto :end
call :find_java
if errorlevel 1 goto :end
call :check_jar
if errorlevel 1 goto :end
call :run_module
goto :end

:confirm_merge
echo.
echo   +---------------------------------------------+
echo   ^|  Module     : merge
echo   ^|  Tickets    : !TICKET_FILE!
echo   ^|  Components : !COMP_FILE!
echo   ^|  Output     : !ARG_OUTPUT!
echo   +---------------------------------------------+
echo.
set /p CONFIRM="  Proceed? (Y/n): "
if /i "!CONFIRM!"=="n" goto :end
call :find_java
if errorlevel 1 goto :end
call :check_jar
if errorlevel 1 goto :end
call :run_merge
goto :end

:: ── Java detection ────────────────────────────────────────────────────────────
:find_java
set "JAVA_EXE="

:: 1. Explicitly configured above
if not "!DEFAULT_JAVA_HOME!"=="" (
    if exist "!DEFAULT_JAVA_HOME!\bin\java.exe" (
        set "JAVA_EXE=!DEFAULT_JAVA_HOME!\bin\java.exe"
        goto :java_found
    )
)

:: 2. JAVA_HOME environment variable
if not "!JAVA_HOME!"=="" (
    if exist "!JAVA_HOME!\bin\java.exe" (
        set "JAVA_EXE=!JAVA_HOME!\bin\java.exe"
        goto :java_found
    )
)

:: 3. java on PATH
where java >nul 2>&1
if %errorlevel%==0 (
    for /f "tokens=*" %%i in ('where java') do (
        set "JAVA_EXE=%%i"
        goto :java_found
    )
)

echo   [ERR] Java not found.
echo         Install Java 21+ and set the JAVA_HOME environment variable,
echo         or set DEFAULT_JAVA_HOME at the top of this script.
pause
exit /b 1

:java_found
for /f "tokens=*" %%v in ('"!JAVA_EXE!" -version 2^>^&1') do (
    echo   [ver]  %%v
    goto :java_ver_done
)
:java_ver_done
echo   [OK]  Java: !JAVA_EXE!
goto :eof

:: ── JAR check ─────────────────────────────────────────────────────────────────
:check_jar
if not exist "!JAR!" (
    echo.
    echo   [ERR] JAR not found: !JAR!
    echo.
    echo         Build the project first:
    echo           mvn clean package -DskipTests -s settings-local.xml
    echo.
    pause
    exit /b 1
)
echo   [OK]  JAR: !JAR!
goto :eof

:: ── Prepare IBM JSON reports folder (upgrade module only) ────────────────────
:prepare_reports_folder
set "REPORTS_DIR=%SCRIPT_DIR%reports"
echo.
if exist "!REPORTS_DIR!\" (
    echo   Cleaning reports folder: !REPORTS_DIR!
    del /Q "!REPORTS_DIR!\*" 2>nul
    for /D %%d in ("!REPORTS_DIR!\*") do rd /S /Q "%%d" 2>nul
    echo   [OK]  Reports folder cleaned.
) else (
    echo   Creating reports folder: !REPORTS_DIR!
    mkdir "!REPORTS_DIR!"
    echo   [OK]  Reports folder created.
)
goto :eof

:: ── Run module ────────────────────────────────────────────────────────────────
:run_module
if /i "!ARG_MODULE!"=="upgrade" (
    call :prepare_reports_folder
)
set "JAVA_ARGS=--module=!ARG_MODULE!"
if not "!ARG_INPUT!"==""  set "JAVA_ARGS=!JAVA_ARGS! --input=!ARG_INPUT!"
if not "!ARG_OUTPUT!"=="" set "JAVA_ARGS=!JAVA_ARGS! --output=!ARG_OUTPUT!"

echo.
echo   ── Running: !ARG_MODULE! ──────────────────────────────
echo   [cmd]  "!JAVA_EXE!" -jar "!JAR!" !JAVA_ARGS!
echo.

"!JAVA_EXE!" -jar "!JAR!" !JAVA_ARGS!
set EXIT_CODE=!errorlevel!

echo.
if !EXIT_CODE!==0 (
    echo   [OK]  Completed successfully.
    if not "!ARG_OUTPUT!"=="" (
        if exist "!ARG_OUTPUT!" echo   [OK]  Report: !ARG_OUTPUT!
    )
) else (
    echo   [ERR] Failed with exit code !EXIT_CODE!. Check output above.
)
goto :eof

:run_merge
set "JAVA_ARGS=--module=merge"
if not "!TICKET_FILE!"=="" set "JAVA_ARGS=!JAVA_ARGS! --ticket-file=!TICKET_FILE!"
if not "!COMP_FILE!"==""   set "JAVA_ARGS=!JAVA_ARGS! --component-file=!COMP_FILE!"
if not "!ARG_OUTPUT!"==""  set "JAVA_ARGS=!JAVA_ARGS! --output=!ARG_OUTPUT!"

echo.
echo   ── Running: merge ─────────────────────────────────────
echo   [cmd]  "!JAVA_EXE!" -jar "!JAR!" !JAVA_ARGS!
echo.

"!JAVA_EXE!" -jar "!JAR!" !JAVA_ARGS!
set EXIT_CODE=!errorlevel!

echo.
if !EXIT_CODE!==0 (
    echo   [OK]  Completed successfully.
    if exist "!ARG_OUTPUT!" echo   [OK]  Report: !ARG_OUTPUT!
) else (
    echo   [ERR] Failed with exit code !EXIT_CODE!
)
goto :eof

:: ── Help ──────────────────────────────────────────────────────────────────────
:show_help
echo.
echo   USAGE
echo     run.bat                                   Launch interactive menu
echo     run.bat upgrade    ^<input^> [output]      Java 21 JVM + library upgrade scan
echo     run.bat wl-jboss26 ^<input^> [output]      WebLogic to WildFly 26 / EAP 7.4 (Java 8)
echo     run.bat wl-jboss27 ^<input^> [output]      WebLogic to WildFly 27+ / EAP 8 (Java 21)
echo     run.bat analyze    [input]  [output]      IBM TA report analysis (input = JSON dir)
echo     run.bat merge                             Excel merge (prompts for files)
echo     run.bat help                              Show this help
echo.
echo   MODULES
echo     upgrade     Two-phase scan:
echo                   Phase 1 — Runs IBM binaryAppScanner.jar (auto-detected from this folder)
echo                              for Java 8^>21 API compatibility. Download from:
echo                              https://www.ibm.com/support/pages/migration-toolkit-application-binaries
echo                              IBM JSON results are saved to the 'reports\' folder next to this script.
echo                              The folder is created/cleaned automatically before each run.
echo                   Phase 2 — Library scan: Spring 5.3.39, Guava 31.1, Guice 5.1, CGLib^>ByteBuddy
echo                   Output: 6-sheet Excel with guidance on every sheet
echo                   Exclusions: edit upgrade-excluded-rules.txt
echo                   IBM scanner override: add --ibm-scanner=^<path^> to the command
echo.
echo     wl-jboss26  WebLogic migration analysis targeting:
echo                 WildFly 26 / JBoss EAP 7.4  --  Java 8  --  javax.*
echo                 NO javax-^>jakarta namespace migration needed
echo.
echo     wl-jboss27  WebLogic migration analysis targeting:
echo                 WildFly 27+ / JBoss EAP 8  --  Java 21  --  jakarta.*
echo                 javax-^>jakarta namespace migration IS required
echo.
echo     analyze     Reads IBM Transformation Advisor JSON reports,
echo                 produces a grouped Excel workbook.
echo                 --input ^<dir^>  external directory of *.json report files
echo                               (omit to use the embedded reports/ folder in the JAR)
echo.
echo     merge       Merges JIRA ticket report with a component list
echo.
echo   EXAMPLES
echo     run.bat upgrade    C:\app\lib   Upgrade-Compatibility-Report.xlsx
echo     run.bat wl-jboss26 C:\app\lib   WlToJBoss-WildFly26-Report.xlsx
echo     run.bat wl-jboss27 C:\app\lib   WlToJBoss-WildFly27-Report.xlsx
echo     run.bat analyze    C:\reports\json   AnalyzerOutput.xlsx
echo     run.bat analyze                       AnalyzerOutput.xlsx
echo.
echo   CONFIGURATION
echo     Edit upgrade-excluded-rules.txt to suppress already-fixed findings.
echo     Add custom JVM rules to java21-custom-rules.txt.
echo     All options can also be set in analyzer.properties next to the JAR.
echo.
goto :end

:: ── Banner ────────────────────────────────────────────────────────────────────
:print_banner
echo.
echo   +===================================================+
echo   ^|  EffortAnalyzer v2.0.0                           ^|
echo   ^|  Migration Analysis ^& Effort Estimation Tool     ^|
echo   +===================================================+
echo.
goto :eof

:end
echo.
pause
endlocal
