package effortanalyzer.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Configuration management for the Analyzer.
 * Loads settings from properties file or uses defaults.
 */
public class AnalyzerConfig {

    private static final Logger logger = LogManager.getLogger(AnalyzerConfig.class);
    private static final String CONFIG_FILE = "analyzer.properties";

    // Configuration keys
    private static final String KEY_RESOURCE_FOLDER = "analyzer.resource.folder";
    private static final String KEY_OUTPUT_FILE = "analyzer.output.file";
    private static final String KEY_EXCLUDED_RULES = "analyzer.excluded.rules";
    private static final String KEY_PARALLEL_PROCESSING = "analyzer.parallel.enabled";
    private static final String KEY_MAX_CELL_LENGTH = "analyzer.excel.maxCellLength";

    // Default values
    private static final String DEFAULT_RESOURCE_FOLDER = "reports";
    private static final String DEFAULT_OUTPUT_FILE = "AnalyzerOutput.xlsx";
    private static final int DEFAULT_MAX_CELL_LENGTH = 32000;

    // Default excluded rules (informational or commonly skipped)
    private static final Set<String> DEFAULT_EXCLUDED_RULES = Set.of(
            "CLDRLocaleDataByDefault",
            "RemovedTransactionModule",
            "RemovedJavaXMLWSModuleNotProvided",
            "RemovedJaxBModuleNotProvided",
            "DetectCorbaJava",
            "URLClassLoaderArrayContainsNull",
            "DetectJaxApiRawTypeMethods",
            "RunJDeps",
            "Java11GeneralInfoAndPotentialIssues",
            "Java17GeneralInfoAndPotentialIssues",
            "Java21GeneralInfoAndPotentialIssues",
            "DefaultCharsetPrintWriter",
            "jre17RegexBehaviorChange",
            "DetectWeakNamedCurves",
            "DeprecatedPrimitiveClassConstructors",
            "jre17EJBRemotePotentialIssues"
    );

    private final String inputPath;        // external directory of JSON reports (may be blank)
    private final String resourceFolder;   // classpath sub-folder fallback
    private final String outputFile;
    private final Set<String> excludedRules;
    private final boolean parallelProcessingEnabled;
    private final int maxCellLength;

    private AnalyzerConfig(Properties props) {
        this.inputPath      = props.getProperty("input.path",         "").trim();
        this.resourceFolder = props.getProperty(KEY_RESOURCE_FOLDER, DEFAULT_RESOURCE_FOLDER);
        this.outputFile     = props.getProperty(KEY_OUTPUT_FILE, DEFAULT_OUTPUT_FILE);
        this.parallelProcessingEnabled = Boolean.parseBoolean(
                props.getProperty(KEY_PARALLEL_PROCESSING, "true"));
        this.maxCellLength = Integer.parseInt(
                props.getProperty(KEY_MAX_CELL_LENGTH, String.valueOf(DEFAULT_MAX_CELL_LENGTH)));

        // Parse excluded rules
        String excludedRulesStr = props.getProperty(KEY_EXCLUDED_RULES);
        if (excludedRulesStr != null && !excludedRulesStr.isBlank()) {
            Set<String> rules = new HashSet<>();
            for (String rule : excludedRulesStr.split(",")) {
                rules.add(rule.trim());
            }
            this.excludedRules = Collections.unmodifiableSet(rules);
        } else {
            this.excludedRules = DEFAULT_EXCLUDED_RULES;
        }

        logger.info("Configuration loaded - Resource folder: {}, Output: {}, Parallel: {}, Excluded rules: {}",
                resourceFolder, outputFile, parallelProcessingEnabled, excludedRules.size());
    }

    /**
     * Loads configuration from classpath or external file.
     */
    public static AnalyzerConfig load() {
        Properties props = new Properties();

        // Try loading from classpath first
        try (InputStream is = AnalyzerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded configuration from classpath: {}", CONFIG_FILE);
            }
        } catch (IOException e) {
            logger.warn("Could not load config from classpath: {}", e.getMessage());
        }

        // Try loading from external file (overrides classpath config)
        Path externalConfig = Path.of(CONFIG_FILE);
        if (Files.exists(externalConfig)) {
            try (InputStream is = Files.newInputStream(externalConfig)) {
                props.load(is);
                logger.info("Loaded configuration from external file: {}", externalConfig);
            } catch (IOException e) {
                logger.warn("Could not load external config: {}", e.getMessage());
            }
        }

        return new AnalyzerConfig(props);
    }

    /**
     * Creates a default configuration.
     */
    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(new Properties());
    }

    /**
     * Creates configuration from an already-resolved Properties object.
     * Used by EffortAnalyzerApp to bridge AppConfig values in.
     */
    public static AnalyzerConfig fromProperties(Properties props) {
        return new AnalyzerConfig(props);
    }

    /** External directory of JSON reports supplied via {@code --input}. Blank when not set. */
    public String getInputPath() {
        return inputPath;
    }

    public String getResourceFolder() {
        return resourceFolder;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public Set<String> getExcludedRules() {
        return excludedRules;
    }

    public boolean isParallelProcessingEnabled() {
        return parallelProcessingEnabled;
    }

    public int getMaxCellLength() {
        return maxCellLength;
    }

    public boolean isRuleExcluded(String ruleId) {
        return excludedRules.contains(ruleId);
    }

    /**
     * Returns the 16 hardcoded IBM TA informational/low-risk rules that are always excluded
     * from the analyze module. Exposed so the upgrade module can merge them into its exclusion set.
     */
    public static Set<String> getDefaultExcludedRules() {
        return DEFAULT_EXCLUDED_RULES;
    }
}
