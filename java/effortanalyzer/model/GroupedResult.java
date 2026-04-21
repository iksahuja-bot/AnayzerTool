package effortanalyzer.model;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a grouped analysis result for a specific component and rule.
 * Thread-safe for use with parallel processing.
 */
public class GroupedResult {

    private final String jsonFileName;
    private final String componentName;
    private final String ruleId;
    private final String severity;
    private final ArrayNode detailsArray;
    private final Set<String> affectedFiles;

    public GroupedResult(String jsonFileName, String componentName, String ruleId, String severity) {
        this.jsonFileName = jsonFileName;
        this.componentName = componentName;
        this.ruleId = ruleId;
        this.severity = severity;
        // Use JsonNodeFactory for thread-safe ArrayNode creation
        this.detailsArray = JsonNodeFactory.instance.arrayNode();
        // Use ConcurrentHashMap-backed Set for thread safety
        this.affectedFiles = ConcurrentHashMap.newKeySet();
    }

    public String getJsonFileName() {
        return jsonFileName;
    }

    public String getComponentName() {
        return componentName;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getSeverity() {
        return severity;
    }

    public ArrayNode getDetailsArray() {
        return detailsArray;
    }

    public Set<String> getAffectedFiles() {
        return Collections.unmodifiableSet(affectedFiles);
    }

    public int getAffectedFilesCount() {
        return affectedFiles.size();
    }

    /**
     * Adds an affected file to the set.
     * Thread-safe.
     */
    public void addAffectedFile(String fileName) {
        affectedFiles.add(fileName);
    }

    /**
     * Generates a unique key for grouping.
     */
    public static String generateKey(String jsonFileName, String componentName, String ruleId) {
        return jsonFileName + "|" + componentName + "|" + ruleId;
    }

    @Override
    public String toString() {
        return "GroupedResult{" +
                "componentName='" + componentName + '\'' +
                ", ruleId='" + ruleId + '\'' +
                ", severity='" + severity + '\'' +
                ", affectedFiles=" + affectedFiles.size() +
                ", details=" + detailsArray.size() +
                '}';
    }
}
