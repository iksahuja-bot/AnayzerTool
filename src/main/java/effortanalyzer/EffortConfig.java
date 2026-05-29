package effortanalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Optional effort-scaling configuration loaded from effort-overrides.properties
 * placed next to the EffortAnalyzer JAR (or the current working directory as fallback).
 *
 * All properties are optional. Each module retains its built-in severity defaults;
 * only the fields explicitly listed in the file are overridden.
 *
 * Properties format:
 *   # Per-severity scale overrides (fields are independent - omit any to keep default)
 *   effort.HIGH.base=1.0
 *   effort.HIGH.perFile=0.25
 *   effort.HIGH.cap=12.0
 *
 *   # Flat per-rule / per-API overrides (bypasses formula entirely)
 *   override.java/lang/System#exit=8.0
 *   override.WLJBOSS-001=5.0
 */
public final class EffortConfig {

    // LOG must be declared before INSTANCE so it is non-null when load() runs.
    private static final Logger LOG = Logger.getLogger(EffortConfig.class.getName());

    public static final EffortConfig INSTANCE = load();

    private final Map<String, Double[]> scales;
    private final Map<String, Double>   ruleOverrides;

    private EffortConfig(Map<String, Double[]> scales, Map<String, Double> ruleOverrides) {
        this.scales        = scales;
        this.ruleOverrides = ruleOverrides;
    }

    /**
     * Returns a flat effort override (hours) for ruleKey, or empty if none is configured.
     * The key is matched case-insensitively. When present, the caller should return this
     * value directly, bypassing the formula.
     */
    public OptionalDouble flatOverride(String ruleKey) {
        if (ruleKey == null) return OptionalDouble.empty();
        Double v = ruleOverrides.get(ruleKey.toLowerCase());
        return v != null ? OptionalDouble.of(v) : OptionalDouble.empty();
    }

    /**
     * Returns per-severity scale overrides as a three-element array [base, perFile, cap].
     * Any element that is null was not configured; the caller keeps its own built-in
     * default for that field.
     */
    public Double[] scaleOverrides(String severity) {
        Double[] result = scales.get(severity == null ? "" : severity.toUpperCase());
        return result != null ? result : new Double[3];
    }

    private static EffortConfig load() {
        Properties props = new Properties();
        Path file = resolveFile();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                LOG.info("[EffortConfig] Loaded overrides from " + file.toAbsolutePath());
            } catch (IOException e) {
                LOG.warning("[EffortConfig] Could not read " + file + ": " + e.getMessage());
            }
        } else {
            LOG.fine("[EffortConfig] " + file.toAbsolutePath() + " not found -- using built-in defaults.");
        }
        return parse(props);
    }

    private static Path resolveFile() {
        try {
            Path jarDir = Path.of(
                    EffortConfig.class.getProtectionDomain()
                                      .getCodeSource().getLocation().toURI()
            ).getParent();
            if (jarDir != null) {
                Path candidate = jarDir.resolve("effort-overrides.properties");
                if (Files.exists(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return Path.of("effort-overrides.properties");
    }

    private static EffortConfig parse(Properties props) {
        Map<String, Double[]> scales        = new HashMap<>();
        Map<String, Double>   ruleOverrides = new HashMap<>();

        for (String key : props.stringPropertyNames()) {
            String rawVal = props.getProperty(key).trim();
            try {
                if (key.startsWith("effort.")) {
                    String[] parts = key.split("\\.", 3);
                    if (parts.length == 3) {
                        String   sev   = parts[1].toUpperCase();
                        String   field = parts[2].toLowerCase();
                        double   v     = Double.parseDouble(rawVal);
                        Double[] arr   = scales.computeIfAbsent(sev, x -> new Double[3]);
                        arr[fieldIndex(field)] = v;
                    }
                } else if (key.startsWith("override.")) {
                    String ruleKey = key.substring("override.".length()).toLowerCase();
                    ruleOverrides.put(ruleKey, Double.parseDouble(rawVal));
                }
            } catch (NumberFormatException e) {
                LOG.warning("[EffortConfig] Ignoring invalid value for '" + key + "': " + rawVal);
            }
        }
        return new EffortConfig(scales, ruleOverrides);
    }

    private static int fieldIndex(String field) {
        return switch (field) { case "perfile" -> 1; case "cap" -> 2; default -> 0; };
    }
}
