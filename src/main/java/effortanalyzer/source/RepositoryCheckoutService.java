package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Prepares local source working trees using git/svn command-line clients. */
public class RepositoryCheckoutService {

    private static final Logger logger = LogManager.getLogger(RepositoryCheckoutService.class);

    private final Path workspace;
    private final boolean reuseWorkspace;
    private final boolean cleanWorkspace;
    private final CheckoutCredentials credentials;

    public RepositoryCheckoutService(Path workspace, boolean reuseWorkspace, boolean cleanWorkspace) {
        this(workspace, reuseWorkspace, cleanWorkspace, CheckoutCredentials.none());
    }

    public RepositoryCheckoutService(Path workspace, boolean reuseWorkspace, boolean cleanWorkspace, CheckoutCredentials credentials) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.reuseWorkspace = reuseWorkspace;
        this.cleanWorkspace = cleanWorkspace;
        this.credentials = credentials == null ? CheckoutCredentials.none() : credentials;
    }

    public CheckoutResult checkout(SourceComponent component) {
        return checkout(component, component.repository(), component.branch(), component.revision(), "");
    }

    public CheckoutResult checkoutTrunk(SourceComponent component) {
        if (component.trunk().isBlank()) {
            return CheckoutResult.failure(component, null, "TRUNK_NOT_PROVIDED", "No Trunk value was supplied");
        }
        return checkout(component, component.trunk(), RepositoryType.from("", component.trunk()), "", "", "-trunk");
    }

    private CheckoutResult checkout(SourceComponent component, String repository, String branch, String revision, String targetSuffix) {
        return checkout(component, repository, component.type(), branch, revision, targetSuffix);
    }

    private CheckoutResult checkout(SourceComponent component, String repository, RepositoryType type, String branch, String revision, String targetSuffix) {
        SourceComponent checkoutComponent = new SourceComponent(component.component(), repository, component.trunk(),
                type, branch, revision, component.path(), component.enabled(), component.rowNumber(),
                component.generatedJars(), component.applicationPackages(), component.ownership(), component.trunkValidated());
        Path target = workspace.resolve(safeName(component.displayName()) + targetSuffix + "-r" + component.rowNumber());
        try {
            Files.createDirectories(workspace);
            if (cleanWorkspace && Files.exists(target)) deleteRecursively(target);

            return switch (checkoutComponent.type()) {
                case LOCAL -> prepareLocal(checkoutComponent, target);
                case GIT -> prepareGit(checkoutComponent, target);
                case SVN -> prepareSvn(checkoutComponent, target);
            };
        } catch (Exception e) {
            logger.warn("Checkout failed for {}: {}", component.displayName(), e.getMessage());
            return CheckoutResult.failure(checkoutComponent, target, "ERROR", e.getMessage());
        }
    }

    private CheckoutResult prepareLocal(SourceComponent component, Path target) throws IOException {
        Path source = Path.of(component.repository()).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            return CheckoutResult.failure(component, source, "LOCAL_MISSING", "Local path does not exist: " + source);
        }
        return CheckoutResult.success(component, resolveSubPath(source, component.path()), "LOCAL", "Using local source directory");
    }

    private CheckoutResult prepareGit(SourceComponent component, Path target) throws IOException, InterruptedException {
        if (Files.exists(target.resolve(".git")) && reuseWorkspace) {
            run(target, "git", "fetch", "--all", "--tags", "--prune");
        } else {
            if (Files.exists(target)) deleteRecursively(target);
            List<String> cmd = new ArrayList<>(List.of("git", "clone"));
            if (!component.branch().isBlank() && component.revision().isBlank()) {
                cmd.add("--branch");
                cmd.add(component.branch());
            }
            cmd.add(credentials.applyToHttpsUrl(component.repository()));
            cmd.add(target.toString());
            run(workspace, cmd.toArray(String[]::new));
        }

        if (!component.revision().isBlank()) {
            run(target, "git", "checkout", component.revision());
        } else if (!component.branch().isBlank()) {
            run(target, "git", "checkout", component.branch());
        }
        return CheckoutResult.success(component, resolveSubPath(target, component.path()), "CHECKED_OUT", "Git working tree ready");
    }

    private CheckoutResult prepareSvn(SourceComponent component, Path target) throws IOException, InterruptedException {
        if (Files.exists(target.resolve(".svn")) && reuseWorkspace) {
            if (component.revision().isBlank()) {
                run(target, svnCommand("update").toArray(String[]::new));
            } else {
                List<String> cmd = svnCommand("update");
                cmd.add("-r");
                cmd.add(component.revision());
                run(target, cmd.toArray(String[]::new));
            }
        } else {
            if (Files.exists(target)) deleteRecursively(target);
            List<String> cmd = svnCommand("checkout");
            if (!component.revision().isBlank()) {
                cmd.add("-r");
                cmd.add(component.revision());
            }
            cmd.add(component.repository());
            cmd.add(target.toString());
            run(workspace, cmd.toArray(String[]::new));
        }
        return CheckoutResult.success(component, resolveSubPath(target, component.path()), "CHECKED_OUT", "SVN working copy ready");
    }

    private List<String> svnCommand(String operation) {
        List<String> cmd = new ArrayList<>(List.of("svn", operation, "--non-interactive", "--trust-server-cert"));
        if (credentials.present()) {
            cmd.add("--username");
            cmd.add(credentials.username());
            if (credentials.hasPassword()) {
                cmd.add("--password");
                cmd.add(credentials.password());
            }
        }
        return cmd;
    }

    private static Path resolveSubPath(Path root, String subPath) {
        return subPath == null || subPath.isBlank() ? root : root.resolve(subPath).normalize();
    }

    private static void run(Path directory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(maskCredentials(String.join(" ", command))
                    + " failed with exit code " + exit + ": " + output.strip());
        }
    }

    private static String maskCredentials(String value) {
        return value == null ? "" : value.replaceAll("(https?://[^:/\\s]+:)[^@\\s]+@", "$1****@");
    }

    private static String safeName(String value) {
        String safe = value == null ? "component" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "component" : (safe.length() > 80 ? safe.substring(0, 80) : safe);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
