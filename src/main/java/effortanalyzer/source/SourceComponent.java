package effortanalyzer.source;

import java.util.List;

/** One row from the source inventory workbook. */
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
        String ownership
) {
    public SourceComponent(String component, String repository, RepositoryType type, String branch,
                           String revision, String path, boolean enabled, int rowNumber) {
        this(component, repository, "", type, branch, revision, path, enabled, rowNumber, List.of(), List.of(), "");
    }

    public SourceComponent(String component, String repository, RepositoryType type, String branch,
                           String revision, String path, boolean enabled, int rowNumber,
                           List<String> generatedJars, List<String> applicationPackages, String ownership) {
        this(component, repository, "", type, branch, revision, path, enabled, rowNumber,
                generatedJars, applicationPackages, ownership);
    }

    public SourceComponent {
        trunk = trunk == null ? "" : trunk.trim();
        generatedJars = generatedJars == null ? List.of() : List.copyOf(generatedJars);
        applicationPackages = applicationPackages == null ? List.of() : List.copyOf(applicationPackages);
        ownership = ownership == null ? "" : ownership.trim();
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
