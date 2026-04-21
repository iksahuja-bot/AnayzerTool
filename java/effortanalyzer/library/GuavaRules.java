package effortanalyzer.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Guava upgrade compatibility rules (any older version → 31.1-jre).
 *
 * Sources:
 *   - Guava deprecated-list: guava.dev/releases/31.0-jre/api/docs/deprecated-list.html
 */
public class GuavaRules {

    public static final String LIBRARY = "Guava 31.1-jre";

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // ── Classes deprecated / moved ────────────────────────────────────────
        add(rules, "com.google.common.collect.ForwardingBlockingDeque", null, "WARNING",
                "Use com.google.common.util.concurrent.ForwardingBlockingDeque",
                "Moved to util.concurrent package");

        add(rules, "com.google.common.collect.TreeTraverser", null, "WARNING",
                "Use Traverser.forTree() where tree implements SuccessorsFunction",
                "TreeTraverser deprecated; scheduled for removal");

        add(rules, "com.google.common.collect.ComputationException", null, "WARNING",
                "Catch ExecutionException, UncheckedExecutionException, or ExecutionError instead",
                "ComputationException no longer thrown by Guava caches");

        // ── Throwables ───────────────────────────────────────────────────────
        add(rules, "com.google.common.base.Throwables", "propagate", "WARNING",
                "Use 'throw e' or 'throw new RuntimeException(e)' or Throwables.throwIfUnchecked()",
                "Throwables.propagate() deprecated");

        add(rules, "com.google.common.base.Throwables", "propagateIfInstanceOf", "WARNING",
                "Use Throwables.throwIfInstanceOf()",
                "propagateIfInstanceOf deprecated");

        add(rules, "com.google.common.base.Throwables", "propagateIfPossible", "WARNING",
                "Use Throwables.throwIfUnchecked()",
                "propagateIfPossible deprecated");

        add(rules, "com.google.common.base.Throwables", "lazyStackTrace", "WARNING",
                "No longer useful on current JVM platforms; use StackWalker",
                "lazyStackTrace deprecated in Guava 31.1");

        add(rules, "com.google.common.base.Throwables", "lazyStackTraceIsLazy", "WARNING",
                "No longer useful; remove usage",
                "lazyStackTraceIsLazy deprecated in Guava 31.1");

        // ── Hashing ───────────────────────────────────────────────────────────
        add(rules, "com.google.common.hash.Hashing", "murmur3_32", "CRITICAL",
                "Use Hashing.murmur3_32_fixed() – the old one produces incorrect hashes for non-BMP strings",
                "murmur3_32 produces wrong hashes for strings with supplementary characters");

        add(rules, "com.google.common.hash.Hashing", "md5", "WARNING",
                "Use Hashing.sha256() for security or goodFastHash() for speed",
                "md5 deprecated: neither fast nor secure");

        add(rules, "com.google.common.hash.Hashing", "sha1", "WARNING",
                "Use Hashing.sha256() for security or goodFastHash() for speed",
                "sha1 deprecated: neither fast nor secure");

        // ── CharMatcher ───────────────────────────────────────────────────────
        for (String method : new String[]{"digit", "invisible", "javaDigit", "javaLetter",
                "javaLetterOrDigit", "javaLowerCase", "javaUpperCase", "singleWidth"}) {
            add(rules, "com.google.common.base.CharMatcher", method, "WARNING",
                    "Many supplementary characters are missed; use CharMatcher.forPredicate() or standard Character methods",
                    "CharMatcher." + method + "() deprecated for Unicode correctness reasons");
        }

        // ── Collections ───────────────────────────────────────────────────────
        add(rules, "com.google.common.collect.ArrayListMultimap", "trimToSize", "WARNING",
                "Use ImmutableListMultimap for immutable maps, or remove trimToSize call",
                "trimToSize deprecated");

        add(rules, "com.google.common.collect.ContiguousSet", "builder", "WARNING",
                "Use ContiguousSet.create(range, domain)",
                "ContiguousSet.builder() deprecated");

        add(rules, "com.google.common.collect.FluentIterable", "from", "WARNING",
                "No need to convert FluentIterable to FluentIterable; call stream() directly",
                "FluentIterable.from(FluentIterable) deprecated");

        add(rules, "com.google.common.collect.ComparisonChain", "compare", "WARNING",
                "Use compareFalseFirst() or compareTrueFirst() with proper semantics",
                "ComparisonChain.compare(Boolean, Boolean) deprecated");

        // ── TreeTraverser methods ─────────────────────────────────────────────
        add(rules, "com.google.common.collect.TreeTraverser", "breadthFirstTraversal", "WARNING",
                "Use Traverser.breadthFirst()",
                "TreeTraverser deprecated");

        add(rules, "com.google.common.collect.TreeTraverser", "postOrderTraversal", "WARNING",
                "Use Traverser.depthFirstPostOrder()",
                "TreeTraverser deprecated");

        add(rules, "com.google.common.collect.TreeTraverser", "preOrderTraversal", "WARNING",
                "Use Traverser.depthFirstPreOrder()",
                "TreeTraverser deprecated");

        // ── Files (deprecated in ~23.0, scheduled removal) ───────────────────
        add(rules, "com.google.common.io.Files", "copy", "WARNING",
                "Use Files.asByteSource(file).copyTo(...) or asCharSource().copyTo()",
                "Files.copy(File,File) deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "hash", "WARNING",
                "Use Files.asByteSource(file).hash(hashFunction)",
                "Files.hash deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "readFirstLine", "WARNING",
                "Use Files.asCharSource(file, charset).readFirstLine()",
                "Files.readFirstLine deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "readLines", "WARNING",
                "Use Files.asCharSource(file, charset).readLines()",
                "Files.readLines deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "readBytes", "WARNING",
                "Use Files.asByteSource(file).read(processor)",
                "Files.readBytes deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "append", "WARNING",
                "Use Files.asCharSink(to, charset, FileWriteMode.APPEND).write(from)",
                "Files.append deprecated");

        add(rules, "com.google.common.io.Files", "createTempDir", "WARNING",
                "Use java.nio.file.Files.createTempDirectory() and .toFile() if needed",
                "Files.createTempDir deprecated; scheduled for removal");

        add(rules, "com.google.common.io.Files", "write", "WARNING",
                "Use Files.asCharSink(to, charset).write(from)",
                "Files.write deprecated");

        // ── MoreExecutors ────────────────────────────────────────────────────
        add(rules, "com.google.common.util.concurrent.MoreExecutors", "sameThreadExecutor", "CRITICAL",
                "Use MoreExecutors.directExecutor() or newDirectExecutorService()",
                "REMOVED in Guava 21.0: sameThreadExecutor deleted");

        add(rules, "com.google.common.util.concurrent.MoreExecutors", "listeningDecorator", "INFO",
                "Still supported but consider CompletableFuture for new code",
                "Guava futures still supported; CompletableFuture is the standard alternative");

        // ── Futures ──────────────────────────────────────────────────────────
        add(rules, "com.google.common.util.concurrent.Futures", "getChecked", "WARNING",
                "Use Futures.get() or getUnchecked() as appropriate",
                "Futures.getChecked deprecated");

        add(rules, "com.google.common.util.concurrent.Futures", "makeChecked", "CRITICAL",
                "No direct replacement; restructure future usage",
                "Futures.makeChecked removed in Guava 21.0");

        add(rules, "com.google.common.util.concurrent.Futures", "get", "WARNING",
                "Use Futures.getChecked() instead with explicit exception type",
                "Futures.get(Future, Class) deprecated");

        // ── Strings ───────────────────────────────────────────────────────────
        add(rules, "com.google.common.io.LittleEndianDataOutputStream", "writeBytes", "WARNING",
                "Use writeUTF(), writeChars(), or another write method instead",
                "writeBytes has dangerous semantics - discards high byte of every character");

        // ── Math ──────────────────────────────────────────────────────────────
        add(rules, "com.google.common.math.DoubleMath", "mean", "WARNING",
                "Use Stats.meanOf() instead",
                "DoubleMath.mean deprecated; less strict handling of non-finite values in replacement");

        // ── Graph ────────────────────────────────────────────────────────────
        add(rules, "com.google.common.graph.Traverser", "forTree", "INFO",
                "Use Traverser.forTree(SuccessorsFunction) - same API but note the interface requirement",
                "TreeTraverser fully replaced by Traverser");

        // ── Converter / Equivalence functional interface adapters ─────────────
        add(rules, "com.google.common.base.Converter", "apply", "WARNING",
                "Use Converter.convert(A) instead",
                "Converter.apply deprecated: provided to satisfy Function interface only");

        add(rules, "com.google.common.base.Equivalence", "test", "WARNING",
                "Use Equivalence.equivalent(T, T) instead",
                "Equivalence.test deprecated: provided to satisfy BiPredicate interface only");

        add(rules, "com.google.common.cache.LoadingCache", "apply", "WARNING",
                "Use LoadingCache.get(K) or getUnchecked(K) instead",
                "LoadingCache.apply deprecated: provided to satisfy Function interface only");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}
