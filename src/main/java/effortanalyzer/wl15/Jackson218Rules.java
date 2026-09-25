package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/** Jackson 2.9.x to 2.18.9 migration rules. */
public class Jackson218Rules {

    private static final String LIBRARY = "Jackson 2.18.9";

    private Jackson218Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // enableDefaultTyping removed -- serious security concern
        add(rules, "com.fasterxml.jackson.databind.ObjectMapper", "enableDefaultTyping", "CRITICAL",
                "Replace enableDefaultTyping() with activateDefaultTyping(ptv, DefaultTyping.NON_FINAL).",
                "ObjectMapper.enableDefaultTyping() removed in Jackson 2.12+ -- use activateDefaultTyping()");

        // Old mapper.configure(Feature) style for deprecated features
        add(rules, "com.fasterxml.jackson.databind.ObjectMapper", "configure", "WARNING",
                "Review ObjectMapper.configure() calls; several MapperFeature entries deprecated/removed.",
                "ObjectMapper.configure(MapperFeature,...) -- verify features still valid in 2.18");

        // DefaultTyping enum member EVERYTHING removed
        add(rules, "com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping", null, "HIGH",
                "Replace DefaultTyping.EVERYTHING with NON_FINAL or NON_FINAL_AND_ENUMS (added in 2.13).",
                "DefaultTyping.EVERYTHING removed in Jackson 2.16+");

        // SimpleModule registration changes
        add(rules, "com.fasterxml.jackson.databind.module.SimpleModule", "addDeserializer", "WARNING",
                "Verify SimpleModule.addDeserializer() type token -- generics handling tightened in 2.18.",
                "SimpleModule type-safe registration changes in Jackson 2.18");

        // TypeFactory.constructType changed
        add(rules, "com.fasterxml.jackson.databind.type.TypeFactory", "constructType", "WARNING",
                "Prefer TypeFactory.constructType(TypeReference) over raw Class variant.",
                "TypeFactory.constructType(Type, Class) overload deprecated; use constructType(TypeReference)");

        // MapperBuilder replaces ObjectMapper construction
        add(rules, "com.fasterxml.jackson.databind.json.JsonMapper", null, "WARNING",
                "Use JsonMapper.builder().build() instead of new ObjectMapper() for new code.",
                "JsonMapper.builder() is the recommended construction pattern since Jackson 2.10");

        // @JsonDeserialize(using=...) with abstract types
        add(rules, "com.fasterxml.jackson.databind.annotation.JsonDeserialize", null, "WARNING",
                "Verify @JsonDeserialize targetType; abstract type handling changed in Jackson 2.18.",
                "@JsonDeserialize resolution for abstract types tightened in Jackson 2.18");

        // Joda module deprecated
        add(rules, "com.fasterxml.jackson.datatype.joda", null, "HIGH",
                "Replace jackson-datatype-joda with jackson-datatype-jsr310 (java.time support).",
                "jackson-datatype-joda deprecated; migrate to jackson-datatype-jsr310");

        // XML mapper changes
        add(rules, "com.fasterxml.jackson.dataformat.xml.XmlMapper", "configure", "WARNING",
                "Review XmlMapper configuration; several XML features deprecated in 2.18.",
                "XmlMapper.configure() options changed in Jackson Dataformat XML 2.18");

        // readValue(byte[]) null handling changed
        add(rules, "com.fasterxml.jackson.databind.ObjectMapper", "readValue", "WARNING",
                "readValue(null, ...) now throws NullPointerException; add null checks before calling.",
                "ObjectMapper.readValue(null,...) behaviour changed -- throws NPE instead of returning null");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}