#!/usr/bin/env bash
# =============================================================================
#  EffortAnalyzer — Unix/Linux/macOS Launcher
#  Version: 2.0.0
#
#  USAGE (interactive):
#    ./run.sh
#
#  USAGE (with arguments):
#    ./run.sh upgrade    /app/lib  report.xlsx
#    ./run.sh wl-jboss26 /app/lib  migration-wf26.xlsx
#    ./run.sh wl-jboss27 /app/lib  migration-wf27.xlsx
#    ./run.sh analyze    /path/to/json  ta-analysis.xlsx
#    ./run.sh analyze                   ta-analysis.xlsx   (uses embedded reports)
#    ./run.sh merge
#    ./run.sh help
#
#  Arguments: [module] [input-path] [output-file]
# =============================================================================

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/EffortAnalyzer-2.0.0.jar"

# Set this if Java is not on your PATH
DEFAULT_JAVA_HOME=""
# Example: DEFAULT_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"

# ── Colours (disabled automatically on non-tty) ───────────────────────────────
if [ -t 1 ]; then
  BOLD="\033[1m"; CYAN="\033[0;36m"; GREEN="\033[0;32m"
  YELLOW="\033[0;33m"; RED="\033[0;31m"; RESET="\033[0m"
else
  BOLD=""; CYAN=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

# ── Banner ────────────────────────────────────────────────────────────────────
print_banner() {
  echo ""
  echo -e "${BOLD}  +===================================================+"
  echo -e "  |  EffortAnalyzer v2.0.0                           |"
  echo -e "  |  Migration Analysis & Effort Estimation Tool     |"
  echo -e "  +===================================================+${RESET}"
  echo ""
}

# ── Java detection ────────────────────────────────────────────────────────────
find_java() {
  JAVA_EXE=""

  if [ -n "$DEFAULT_JAVA_HOME" ] && [ -x "$DEFAULT_JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$DEFAULT_JAVA_HOME/bin/java"

  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"

  elif command -v java &>/dev/null; then
    JAVA_EXE="$(command -v java)"

  else
    for candidate in \
        /usr/lib/jvm/java-21*/bin/java \
        /usr/lib/jvm/temurin-21*/bin/java \
        /opt/java/21*/bin/java \
        /usr/local/lib/jvm/java-21*/bin/java; do
      # shellcheck disable=SC2086
      for f in $candidate; do
        if [ -x "$f" ]; then JAVA_EXE="$f"; break 2; fi
      done
    done
  fi

  if [ -z "$JAVA_EXE" ]; then
    echo -e "${RED}  [ERR] Java not found.${RESET}"
    echo "        Install Java 21+ and either:"
    echo "          - Add it to your PATH, or"
    echo "          - Set JAVA_HOME, or"
    echo "          - Set DEFAULT_JAVA_HOME at the top of this script."
    exit 1
  fi

  JAVA_VER=$("$JAVA_EXE" -version 2>&1 | head -1)
  echo -e "${CYAN}  [ver]${RESET}  $JAVA_VER"
  echo -e "${GREEN}  [OK]${RESET}   Java: $JAVA_EXE"
}

# ── JAR check ─────────────────────────────────────────────────────────────────
check_jar() {
  if [ ! -f "$JAR" ]; then
    echo ""
    echo -e "${RED}  [ERR] JAR not found: $JAR${RESET}"
    echo ""
    echo "        Build the project first:"
    echo "          mvn clean package -DskipTests -s settings-local.xml"
    echo ""
    exit 1
  fi
  echo -e "${GREEN}  [OK]${RESET}   JAR: $JAR"
}

# ── Run module ────────────────────────────────────────────────────────────────
run_module() {
  local module="$1" input="$2" output="$3"

  JAVA_ARGS="--module=$module"
  [ -n "$input"  ] && JAVA_ARGS="$JAVA_ARGS --input=$input"
  [ -n "$output" ] && JAVA_ARGS="$JAVA_ARGS --output=$output"

  echo ""
  echo -e "${BOLD}  ── Running: $module ────────────────────────────────${RESET}"
  echo -e "${CYAN}  [cmd]${RESET}  \"$JAVA_EXE\" -jar \"$JAR\" $JAVA_ARGS"
  echo ""

  # shellcheck disable=SC2086
  "$JAVA_EXE" -jar "$JAR" $JAVA_ARGS
  EXIT_CODE=$?

  echo ""
  if [ "$EXIT_CODE" -eq 0 ]; then
    echo -e "${GREEN}  [OK]${RESET}   Completed successfully."
    [ -n "$output" ] && [ -f "$output" ] && echo -e "${GREEN}  [OK]${RESET}   Report: $output"
  else
    echo -e "${RED}  [ERR] Failed with exit code $EXIT_CODE. Check output above.${RESET}"
  fi
  echo ""
}

run_merge() {
  local ticket="$1" component="$2" output="$3"

  JAVA_ARGS="--module=merge"
  [ -n "$ticket"    ] && JAVA_ARGS="$JAVA_ARGS --ticket-file=$ticket"
  [ -n "$component" ] && JAVA_ARGS="$JAVA_ARGS --component-file=$component"
  [ -n "$output"    ] && JAVA_ARGS="$JAVA_ARGS --output=$output"

  echo ""
  echo -e "${BOLD}  ── Running: merge ──────────────────────────────────${RESET}"
  echo -e "${CYAN}  [cmd]${RESET}  \"$JAVA_EXE\" -jar \"$JAR\" $JAVA_ARGS"
  echo ""

  # shellcheck disable=SC2086
  "$JAVA_EXE" -jar "$JAR" $JAVA_ARGS
  EXIT_CODE=$?

  echo ""
  if [ "$EXIT_CODE" -eq 0 ]; then
    echo -e "${GREEN}  [OK]${RESET}   Completed successfully."
    [ -n "$output" ] && [ -f "$output" ] && echo -e "${GREEN}  [OK]${RESET}   Report: $output"
  else
    echo -e "${RED}  [ERR] Failed with exit code $EXIT_CODE.${RESET}"
  fi
  echo ""
}

# ── IBM JSON reports folder management ────────────────────────────────────────
prepare_reports_folder() {
  local reports_dir="$SCRIPT_DIR/reports"
  echo ""
  if [ -d "$reports_dir" ]; then
    echo -e "${CYAN}  [>>]${RESET}  Cleaning reports folder: $reports_dir"
    # Remove contents but keep the directory
    find "$reports_dir" -mindepth 1 -delete 2>/dev/null || rm -rf "${reports_dir:?}"/* 2>/dev/null || true
    echo -e "${GREEN}  [OK]${RESET}  Reports folder cleaned."
  else
    echo -e "${CYAN}  [>>]${RESET}  Creating reports folder: $reports_dir"
    mkdir -p "$reports_dir"
    echo -e "${GREEN}  [OK]${RESET}  Reports folder created."
  fi
}

# ── Prompt helper ─────────────────────────────────────────────────────────────
prompt() {
  local label="$1" default="$2" var_name="$3"
  local val=""
  if [ -n "$default" ]; then
    read -rp "  $label [$default]: " val
    val="${val:-$default}"
  else
    read -rp "  $label: " val
  fi
  eval "$var_name='$val'"
}

confirm() {
  local module="$1" input="$2" output="$3"
  echo ""
  echo "  +---------------------------------------------+"
  echo "  |  Module : $module"
  echo "  |  Input  : $input"
  echo "  |  Output : $output"
  echo "  +---------------------------------------------+"
  echo ""
  read -rp "  Proceed? (Y/n): " CONFIRM
  if [[ "${CONFIRM,,}" == "n" ]]; then exit 0; fi
}

# ── Interactive menu ──────────────────────────────────────────────────────────
interactive_menu() {
  while true; do
    echo "  Select a module to run:"
    echo ""
    echo "    [1]  Upgrade Compatibility Analyzer"
    echo "           Java 8 > Java 21 JVM + Spring 5.3.39 / Guava 31.1 / Guice 5.1 / CGLib>ByteBuddy"
    echo ""
    echo "    [2]  WebLogic > WildFly 26 / JBoss EAP 7.4"
    echo "           Java 8  |  Jakarta EE 8  |  javax.*  (no namespace migration)"
    echo ""
    echo "    [3]  WebLogic > WildFly 27+ / JBoss EAP 8"
    echo "           Java 21 |  Jakarta EE 10 |  jakarta.* (namespace migration required)"
    echo ""
    echo "    [4]  IBM Transformation Advisor Report Analyzer"
    echo ""
    echo "    [5]  Excel Merger  (Ticket list + Component list)"
    echo ""
    echo "    [6]  Help"
    echo ""
    echo "    [Q]  Quit"
    echo ""
    read -rp "  Enter choice: " CHOICE

    case "${CHOICE,,}" in
      1)
        echo ""
        echo "  IBM binaryAppScanner.jar is auto-detected from this script's folder."
        echo "  If not present, download from:"
        echo "    https://www.ibm.com/support/pages/migration-toolkit-application-binaries"
        echo ""
        prompt "Path to JAR file or directory of JARs" "" INPUT
        prompt "Output Excel file" "Upgrade-Compatibility-Report.xlsx" OUTPUT
        confirm "upgrade" "$INPUT" "$OUTPUT"
        find_java; check_jar
        prepare_reports_folder
        run_module "upgrade" "$INPUT" "$OUTPUT"
        ;;
      2)
        prompt "Path to JAR/WAR/EAR file or directory" "" INPUT
        prompt "Output Excel file" "WlToJBoss-WildFly26-Report.xlsx" OUTPUT
        confirm "wl-jboss26" "$INPUT" "$OUTPUT"
        find_java; check_jar
        run_module "wl-jboss26" "$INPUT" "$OUTPUT"
        ;;
      3)
        prompt "Path to JAR/WAR/EAR file or directory" "" INPUT
        prompt "Output Excel file" "WlToJBoss-WildFly27-Report.xlsx" OUTPUT
        confirm "wl-jboss27" "$INPUT" "$OUTPUT"
        find_java; check_jar
        run_module "wl-jboss27" "$INPUT" "$OUTPUT"
        ;;
      4)
        echo ""
        echo "  Enter the directory containing IBM TA JSON report files."
        echo "  Leave blank to use the embedded reports folder inside the JAR."
        echo ""
        prompt "JSON reports directory (optional, press Enter to skip)" "" INPUT
        prompt "Output Excel file" "AnalyzerOutput.xlsx" OUTPUT
        local display_input="${INPUT:-(embedded reports)}"
        confirm "analyze" "$display_input" "$OUTPUT"
        find_java; check_jar
        run_module "analyze" "$INPUT" "$OUTPUT"
        ;;
      5)
        prompt "Ticket Excel file" "TicketReport.xlsx" TICKET
        prompt "Component Excel file" "ComponentList.xlsx" COMPONENT
        prompt "Output Excel file" "MergedOutput.xlsx" OUTPUT
        echo ""
        echo "  +---------------------------------------------+"
        echo "  |  Module     : merge"
        echo "  |  Tickets    : $TICKET"
        echo "  |  Components : $COMPONENT"
        echo "  |  Output     : $OUTPUT"
        echo "  +---------------------------------------------+"
        echo ""
        read -rp "  Proceed? (Y/n): " CONFIRM
        if [[ "${CONFIRM,,}" != "n" ]]; then
          find_java; check_jar
          run_merge "$TICKET" "$COMPONENT" "$OUTPUT"
        fi
        ;;
      6|help|-h|--help) show_help ;;
      q|quit) exit 0 ;;
      *) echo -e "${YELLOW}  [!] Invalid choice. Please enter 1-6 or Q.${RESET}" ;;
    esac
  done
}

# ── Help ──────────────────────────────────────────────────────────────────────
show_help() {
  echo ""
  echo "  USAGE"
  echo "    ./run.sh                                   Launch interactive menu"
  echo "    ./run.sh upgrade    <input> [output]       Java 21 JVM + library upgrade scan"
  echo "    ./run.sh wl-jboss26 <input> [output]       WebLogic to WildFly 26 / EAP 7.4 (Java 8)"
  echo "    ./run.sh wl-jboss27 <input> [output]       WebLogic to WildFly 27+ / EAP 8 (Java 21)"
  echo "    ./run.sh analyze    [input]  [output]       IBM TA report analysis (input = JSON dir)"
  echo "    ./run.sh merge                             Excel merge (prompts for files)"
  echo "    ./run.sh help                              Show this help"
  echo ""
  echo "  MODULES"
  echo "    upgrade     Two-phase scan:"
  echo "                  Phase 1 — Runs IBM binaryAppScanner.jar (auto-detected from script folder)"
  echo "                            for Java 8>21 API compatibility."
  echo "                            Download: https://www.ibm.com/support/pages/migration-toolkit-application-binaries"
  echo "                            IBM JSON results are saved to the 'reports/' folder next to this script."
  echo "                            The folder is created and cleaned automatically before each run."
  echo "                  Phase 2 — Library scan: Spring 5.3.39, Guava 31.1, Guice 5.1, CGLib>ByteBuddy"
  echo "                  Output: 6-sheet Excel with guidance on every sheet"
  echo "                  Exclusions: edit upgrade-excluded-rules.txt"
  echo "                  IBM scanner override: add --ibm-scanner=<path> to the command"
  echo ""
  echo "    wl-jboss26  WebLogic migration targeting:"
  echo "                WildFly 26 / JBoss EAP 7.4  --  Java 8  --  javax.*"
  echo "                NO javax->jakarta namespace migration needed"
  echo ""
  echo "    wl-jboss27  WebLogic migration targeting:"
  echo "                WildFly 27+ / JBoss EAP 8  --  Java 21  --  jakarta.*"
  echo "                javax->jakarta namespace migration IS required"
  echo ""
  echo "    analyze     Reads IBM Transformation Advisor JSON reports,"
  echo "                produces a grouped Excel workbook."
  echo "                --input <dir>  external directory of *.json report files"
  echo "                              (omit to use the embedded reports/ folder in the JAR)"
  echo ""
  echo "    merge       Merges a JIRA ticket report with a component list"
  echo ""
  echo "  EXAMPLES"
  echo "    ./run.sh upgrade    /opt/app/lib  Upgrade-Compatibility-Report.xlsx"
  echo "    ./run.sh upgrade    /opt/app/lib  (IBM scanner auto-detected from same folder)"
  echo "    ./run.sh wl-jboss26 /opt/app/lib  WlToJBoss-WildFly26-Report.xlsx"
  echo "    ./run.sh wl-jboss27 /opt/app/lib  WlToJBoss-WildFly27-Report.xlsx"
  echo "    ./run.sh analyze    /opt/reports/json  AnalyzerOutput.xlsx"
  echo "    ./run.sh analyze                       AnalyzerOutput.xlsx"
  echo ""
  echo "  CONFIGURATION"
  echo "    Edit upgrade-excluded-rules.txt to suppress already-fixed findings."
  echo "    Add custom JVM rules to java21-custom-rules.txt."
  echo "    All options can also be set in analyzer.properties next to the JAR."
  echo ""
}

# ── Entry point ───────────────────────────────────────────────────────────────
print_banner

case "${1:-}" in
  help|-h|--help)
    show_help
    ;;
  upgrade|wl-jboss26|wl-jboss27|wl-jboss|analyze)
    MODULE="${1}"; INPUT="${2:-}"; OUTPUT="${3:-}"
    find_java
    check_jar
    [ "$MODULE" = "upgrade" ] && prepare_reports_folder
    run_module "$MODULE" "$INPUT" "$OUTPUT"
    ;;
  merge)
    TICKET="${2:-}"; COMPONENT="${3:-}"; OUTPUT="${4:-}"
    find_java
    check_jar
    run_merge "$TICKET" "$COMPONENT" "$OUTPUT"
    ;;
  "")
    interactive_menu
    ;;
  *)
    echo -e "${RED}  [ERR] Unknown module: $1${RESET}"
    echo "        Run './run.sh help' to see available modules."
    exit 1
    ;;
esac
