package effortanalyzer.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Guice upgrade compatibility rules (3.x → 4.x → 5.1.0).
 *
 * Sources:
 *   - Guice deprecated-list: google.github.io/guice/api-docs/5.1.0/javadoc/deprecated-list.html
 */
public class GuiceRules {

    public static final String LIBRARY = "Guice 5.1.0";

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // ── Removed in Guice 4.0 (were in 3.0) ───────────────────────────────
        add(rules, "com.google.inject.binder", "toProvider", "CRITICAL",
                "Use bind(...).toProvider(Provider.class) with correct overload",
                "REMOVED in Guice 4.0: toProvider overloads changed");

        // ── Deprecated in 5.0 / 5.1 ───────────────────────────────────────────
        add(rules, "com.google.inject.throwingproviders.ThrowingProvider", null, "WARNING",
                "Use CheckedProvider instead",
                "ThrowingProvider deprecated; CheckedProvider is the replacement");

        add(rules, "com.google.inject.assistedinject.FactoryProvider", null, "WARNING",
                "Use FactoryModuleBuilder instead",
                "FactoryProvider deprecated; use FactoryModuleBuilder");

        add(rules, "com.google.inject.multibindings.MultibindingsScanner", null, "WARNING",
                "Remove all references; functionality is installed by default",
                "MultibindingsScanner deprecated; will be removed in Guice 4.4+");

        add(rules, "com.google.inject.struts2.GuiceObjectFactory", null, "WARNING",
                "Use Struts2Factory instead",
                "GuiceObjectFactory deprecated");

        add(rules, "com.google.inject.multibindings.MultibindingsScanner", "asModule", "WARNING",
                "Remove; returns empty scanner since functionality is now built-in",
                "asModule() deprecated");

        add(rules, "com.google.inject.multibindings.MultibindingsScanner", "scanner", "WARNING",
                "Remove; returns empty scanner",
                "scanner() deprecated");

        add(rules, "com.google.inject.servlet.ServletScopes", "continueRequest", "WARNING",
                "Use transferRequest instead",
                "continueRequest deprecated");

        add(rules, "com.google.inject.spi.ProviderInstanceBinding", "getProviderInstance", "WARNING",
                "Use getUserSuppliedProvider() instead",
                "getProviderInstance deprecated");

        add(rules, "com.google.inject.throwingproviders.ThrowingProviderBinder", "bind", "WARNING",
                "Use bind(Class, Class) or bind(Class, TypeLiteral)",
                "ThrowingProviderBinder.bind(Class, Type) deprecated");

        add(rules, "com.google.inject.util.Modules", "combine", "WARNING",
                "Install the module directly; no reason to combine a single module",
                "Modules.combine(Module) deprecated");

        add(rules, "com.google.inject.util.Modules", "override", "WARNING",
                "Use with arguments; no reason to call override() without arguments",
                "Modules.override() deprecated without arguments");

        add(rules, "com.google.inject.util.Modules.OverriddenModuleBuilder", "with", "WARNING",
                "Use with(Module...) with arguments",
                "OverriddenModuleBuilder.with() deprecated without arguments");

        // ── Providers.guicify() removed in Guice 5.0 ─────────────────────────
        add(rules, "com.google.inject.util.Providers", "guicify", "CRITICAL",
                "Remove Providers.guicify() — no longer needed since com.google.inject.Provider "
                + "extends javax.inject.Provider (Guice 4.0+). "
                + "Assign the javax.inject.Provider directly; toProvider() accepts it without wrapping.",
                "REMOVED in Guice 5.0: Providers.guicify() deleted; "
                + "com.google.inject.Provider now extends javax.inject.Provider so no conversion is needed");

        // ── Guice 5.0 removed CGLib dependency ───────────────────────────────
        add(rules, "com.google.inject.cglib", null, "CRITICAL",
                "REMOVED in Guice 5.0: Guice no longer bundles CGLib; uses ASM directly",
                "Guice 5.0 removed repackaged CGLib; if your code references it, it will break");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}
