package effortanalyzer.source;

import java.util.Locale;

/** Supported source repository technologies for inventory-driven checkout. */
public enum RepositoryType {
    GIT,
    SVN,
    LOCAL;

    public static RepositoryType from(String value, String repository) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            return switch (normalized) {
                case "git" -> GIT;
                case "svn", "subversion" -> SVN;
                case "local", "filesystem", "file" -> LOCAL;
                default -> infer(repository);
            };
        }
        return infer(repository);
    }

    private static RepositoryType infer(String repository) {
        String repo = repository == null ? "" : repository.trim().toLowerCase(Locale.ROOT);
        if (repo.startsWith("file:") || repo.matches("^[a-z]:.*") || repo.startsWith("/") || repo.startsWith("\\\\")) {
            return LOCAL;
        }
        if (repo.endsWith(".git") || repo.startsWith("git@") || repo.startsWith("ssh://git@") || repo.contains("github.com") || repo.contains("gitlab.")) {
            return GIT;
        }
        return SVN;
    }
}
