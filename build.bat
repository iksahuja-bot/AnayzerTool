@echo off
setlocal EnableDelayedExpansion

:: =============================================================================
::  EffortAnalyzer — Build Script
::  Tries multiple Maven strategies to handle corporate network restrictions.
:: =============================================================================

set "SCRIPT_DIR=%~dp0"
set "SETTINGS_LOCAL=%SCRIPT_DIR%settings-local.xml"
set "JAVA_HOME_OVERRIDE="

:: ── Find Java 21 ──────────────────────────────────────────────────────────────
set "JAVA_EXE="
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)
if "%JAVA_EXE%"=="" (
    for /f "tokens=*" %%i in ('where java 2^>nul') do ( set "JAVA_EXE=%%i" & goto :java_ok )
)
:java_ok
if "%JAVA_EXE%"=="" (
    echo [ERR] Java not found. Set JAVA_HOME to your JDK 21 directory.
    pause & exit /b 1
)
echo [OK]  Java: %JAVA_EXE%

:: ── Maven ─────────────────────────────────────────────────────────────────────
where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERR] mvn not found on PATH.
    pause & exit /b 1
)

:: ── Strategy 1: local settings (Nexus + Maven Central) ───────────────────────
echo.
echo [1/3] Trying build with local settings (Nexus + Maven Central)...
echo       mvn clean package -DskipTests -s settings-local.xml
echo.
mvn clean package -DskipTests -s "%SETTINGS_LOCAL%"
if %errorlevel%==0 goto :success

:: ── Strategy 2: Maven Central only (bypass Nexus entirely) ───────────────────
echo.
echo [2/3] Nexus failed. Trying Maven Central directly...
echo       mvn clean package -DskipTests -Dmaven.repo.remote=https://repo1.maven.org/maven2
echo.
mvn clean package -DskipTests ^
    -Dmaven.repo.remote=https://repo1.maven.org/maven2 ^
    "--global-settings=%SCRIPT_DIR%settings-local.xml" ^
    -s "%SETTINGS_LOCAL%"
if %errorlevel%==0 goto :success

:: ── Strategy 3: Offline using whatever is in local repo ──────────────────────
echo.
echo [3/3] Trying offline build using cached dependencies...
echo       mvn clean package -DskipTests -o
echo.
mvn clean package -DskipTests -o
if %errorlevel%==0 goto :success

:: ── All strategies failed ─────────────────────────────────────────────────────
echo.
echo ============================================================
echo  BUILD FAILED — All strategies exhausted.
echo ============================================================
echo.
echo  Options:
echo  A) Ask your Nexus admin to add these artifacts to the Nexus:
echo       org.apache.poi:poi-ooxml:5.2.5
echo       org.apache.logging.log4j:log4j-api:2.23.1
echo       org.apache.logging.log4j:log4j-core:2.23.1
echo       com.fasterxml.jackson.core:jackson-databind:2.17.1
echo.
echo  B) Run build.bat from a machine with direct internet access,
echo     then copy target\EffortAnalyzer-2.0.0.jar to this machine.
echo.
echo  C) Download the JARs manually and install them:
echo     See manual-install.bat (generated below)
echo.
call :write_manual_install
pause
exit /b 1

:success
echo.
echo ============================================================
echo  BUILD SUCCESSFUL
echo ============================================================
echo.
echo  JAR: %SCRIPT_DIR%target\EffortAnalyzer-2.0.0.jar
echo.
echo  You can now run:   run.bat   or   .\run.ps1
echo.
pause
exit /b 0

:: ── Generate manual install helper ───────────────────────────────────────────
:write_manual_install
echo @echo off > "%SCRIPT_DIR%manual-install.bat"
echo :: Download these JARs from https://search.maven.org and then run this script >> "%SCRIPT_DIR%manual-install.bat"
echo :: to install them into your local Maven repo (c:\m2p). >> "%SCRIPT_DIR%manual-install.bat"
echo. >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=poi-ooxml-5.2.5.jar              -DgroupId=org.apache.poi                 -DartifactId=poi-ooxml              -Dversion=5.2.5   -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=poi-5.2.5.jar                    -DgroupId=org.apache.poi                 -DartifactId=poi                    -Dversion=5.2.5   -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=log4j-api-2.23.1.jar             -DgroupId=org.apache.logging.log4j       -DartifactId=log4j-api              -Dversion=2.23.1  -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=log4j-core-2.23.1.jar            -DgroupId=org.apache.logging.log4j       -DartifactId=log4j-core             -Dversion=2.23.1  -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=jackson-databind-2.17.1.jar      -DgroupId=com.fasterxml.jackson.core     -DartifactId=jackson-databind       -Dversion=2.17.1  -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=jackson-core-2.17.1.jar          -DgroupId=com.fasterxml.jackson.core     -DartifactId=jackson-core           -Dversion=2.17.1  -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=jackson-annotations-2.17.1.jar   -DgroupId=com.fasterxml.jackson.core     -DartifactId=jackson-annotations    -Dversion=2.17.1  -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=commons-collections4-4.4.jar     -DgroupId=org.apache.commons             -DartifactId=commons-collections4   -Dversion=4.4     -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo mvn install:install-file -Dfile=poi-ooxml-schemas-4.1.2.jar      -DgroupId=org.apache.poi                 -DartifactId=poi-ooxml-schemas       -Dversion=4.1.2   -Dpackaging=jar >> "%SCRIPT_DIR%manual-install.bat"
echo.
echo [OK]  manual-install.bat created.
goto :eof
endlocal
