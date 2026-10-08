package effortanalyzer.source;

import java.util.List;

/**
 * One row from the source inventory workbook.
 *
 * @param trunkValidated {@code TRUE} when the trunk/reference code already runs on the target
 *                       platform, {@code null} when the inventory row does not say
 */
public record SourceComponent(
        String component,
        String repository,
        String trunk,
        RepositoryType type,
        String branch,
        String revision,
        String path,
        boolean enabled,
        int rowNumber,
        List<String> generatedJars,
        List<String> applicationPackages,
        String ownership,
        Boolean trunkValidated
) {
    public SourceComponent(String component, String repository, RepositoryType type, String branch,
                           String revision, String path, boolean enabled, int rowNumber) {
        this(component, repository, "", type, branch, revision, path, enabled, rowNumber, List.of(), List.of(), "", null);
    }

    public SourceComponent(String component, String repository, RepositoryType type, String branch,
                           String revision, String path, boolean enabled, int rowNumber,
                           List<String> generatedJars, List<String> applicationPackages, String ownership) {
        this(component, repository, "", type, branch, revision, path, enabled, rowNumber,
                generatedJars, applicationPackages, ownership, null);
    }

    public SourceComponent(String component, String repository, String trunk, RepositoryType type, String branch,
                           String revision, String path, boolean enabled, int rowNumber,
                           List<String> generatedJars, List<String> applicationPackages, String ownership) {
        this(component, repository, trunk, type, branch, revision, path, enabled, rowNumber,
                generatedJars, applicationPackages, ownership, null);
    }

    public SourceComponent {
        trunk = trunk == null ? "" : trunk.trim();
        generatedJars = generatedJars == null ? List.of() : List.copyOf(generatedJars);
        applicationPackages = applicationPackages == null ? List.of() : List.copyOf(applicationPackages);
        ownership = ownership == null ? "" : ownership.trim();
    }

    public boolean isTrunkValidated() {
        return Boolean.TRUE.equals(trunkValidated) && !trunk.isBlank();
    }

    /** Applies the CLI default when the inventory row leaves Trunk Validated blank. */
    public SourceComponent withTrunkValidatedDefault(boolean defaultValue) {
        if (trunkValidated != null) return this;
        return new SourceComponent(component, repository, trunk, type, branch, revision, path, enabled, rowNumber,
                generatedJars, applicationPackages, ownership, defaultValue);
    }

    public String displayName() {
        return component == null || component.isBlank() ? repository : component;
    }

    public String generatedJarsDisplay() {
        return String.join(";", generatedJars);
    }

    public String applicationPackagesDisplay() {
        return String.join(";", applicationPackages);
    }
}
