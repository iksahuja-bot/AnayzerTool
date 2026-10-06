package effortanalyzer.source;

import effortanalyzer.library.DeprecatedApi;
import effortanalyzer.library.LibraryUpgradeRules;
import effortanalyzer.upgrade.UpgradeAnalyzer;
import effortanalyzer.wl14.Wl14LibraryRules;
import effortanalyzer.wl15.Wl15LibraryRules;
import effortanalyzer.wljboss.WlJBossRules;

import java.util.ArrayList;
import java.util.List;

/** Source-tree rule profile matching the selected EffortAnalyzer module. */
public record SourceScanProfile(
        String module,
        List<DeprecatedApi> libraryRules,
        WlJBossRules wlJBossRules
) {

    public static SourceScanProfile forModule(String module, WlJBossRules.TargetProfile targetProfile) {
        String normalized = module == null ? "" : module.trim().toLowerCase();
        return switch (normalized) {
            case "wl14" -> new SourceScanProfile(normalized, wl14Rules(), null);
            case "wl15" -> new SourceScanProfile(normalized, Wl15LibraryRules.load(), null);
            case "upgrade", "source-inventory" -> new SourceScanProfile(normalized,
                    LibraryUpgradeRules.load(UpgradeAnalyzer.loadAllExclusions()).getRules(), null);
            case "wl-jboss26" -> new SourceScanProfile(normalized, List.of(),
                    WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8));
            case "wl-jboss27" -> new SourceScanProfile(normalized, List.of(),
                    WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21));
            case "wl-jboss" -> new SourceScanProfile(normalized, List.of(), WlJBossRules.load(targetProfile));
            default -> throw new IllegalArgumentException("Source inventory is not supported for module: " + module);
        };
    }

    public boolean hasLibraryRules() {
        return libraryRules != null && !libraryRules.isEmpty();
    }

    public boolean hasWlJBossRules() {
        return wlJBossRules != null;
    }

    private static List<DeprecatedApi> wl14Rules() {
        List<DeprecatedApi> combined = new ArrayList<>();
        combined.addAll(LibraryUpgradeRules.load(UpgradeAnalyzer.loadAllExclusions()).getRules());
        combined.addAll(Wl14LibraryRules.load());
        return combined;
    }
}
