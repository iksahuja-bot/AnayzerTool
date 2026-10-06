package effortanalyzer.source;

/** One row from the source inventory workbook. */
public record SourceComponent(
        String component,
        String repository,
        RepositoryType type,
        String branch,
        String revision,
        String path,
        boolean enabled,
        int rowNumber
) {
    public String displayName() {
        return component == null || component.isBlank() ? repository : component;
    }
}
