package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/** JasperReports 6.x to 7.0.4 migration rules. */
public class JasperReports7Rules {

    private static final String LIBRARY = "JasperReports 7.0.4";

    private JasperReports7Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // JRPdfExporter: class renamed to PdfExporter in some versions; and moved
        add(rules, "net.sf.jasperreports.engine.export.JRPdfExporter", null, "HIGH",
                "Migrate to net.sf.jasperreports.pdf.JRPdfExporter (jasperreports-pdf module in 7.x).",
                "JRPdfExporter moved to separate jasperreports-pdf module in JasperReports 7.0");

        add(rules, "net.sf.jasperreports.engine.export.JRPdfExporterParameter", null, "HIGH",
                "Replace JRPdfExporterParameter constants with PdfExporterConfiguration interface methods.",
                "JRPdfExporterParameter removed; use PdfExporterConfiguration in JasperReports 7.0");

        // FillManager
        add(rules, "net.sf.jasperreports.engine.JasperFillManager", "fillReport", "WARNING",
                "JasperFillManager.fillReport() retained; verify parameter map types (Map<String,Object>).",
                "JasperFillManager.fillReport() parameter typing tightened in JasperReports 7.0");

        // JasperReport compilation
        add(rules, "net.sf.jasperreports.engine.JasperCompileManager", "compileReport", "WARNING",
                "JasperCompileManager.compileReport() retained; verify classpath includes jasperreports-compiler.",
                "Compilation dependencies restructured; ensure jasperreports-compiler is on classpath");

        // HTML exporter
        add(rules, "net.sf.jasperreports.engine.export.HtmlExporter", null, "HIGH",
                "Replace HtmlExporter with net.sf.jasperreports.engine.export.HtmlExporter (package path changed).",
                "HtmlExporter package changed in JasperReports 7.0; update import statements");

        // ODS / ODP / ODT exporters
        add(rules, "net.sf.jasperreports.engine.export.oasis", null, "WARNING",
                "Verify ODS/ODT exporter classes; package structure changed in JasperReports 7.0.",
                "ODF exporters (ODS/ODT) restructured in JasperReports 7.0");

        // JRDataSource implementations
        add(rules, "net.sf.jasperreports.engine.data.JRBeanCollectionDataSource", null, "WARNING",
                "JRBeanCollectionDataSource retained; verify field name mapping with Java record types.",
                "JRBeanCollectionDataSource field access for Java records changed in JasperReports 7.0");

        // JRPropertiesUtil changes
        add(rules, "net.sf.jasperreports.engine.util.JRProperties", null, "HIGH",
                "Replace JRProperties with JRPropertiesUtil.getInstance(jasperReportsContext).",
                "JRProperties static API removed in JasperReports 5+; use JRPropertiesUtil");

        // DefaultJasperReportsContext
        add(rules, "net.sf.jasperreports.engine.DefaultJasperReportsContext", "getInstance", "WARNING",
                "Prefer creating a dedicated SimpleJasperReportsContext for thread safety.",
                "DefaultJasperReportsContext.getInstance() is global singleton; use SimpleJasperReportsContext");

        // JasperPrint export changes
        add(rules, "net.sf.jasperreports.engine.JasperExportManager", "exportReportToPdfFile", "WARNING",
                "Use JasperExportManager.getInstance(context).exportReportToPdfFile() in JasperReports 7.0.",
                "JasperExportManager static methods replaced with instance methods in JasperReports 7.0");

        // Scriptlet changes
        add(rules, "net.sf.jasperreports.engine.JRDefaultScriptlet", null, "WARNING",
                "Verify scriptlet class; JRDefaultScriptlet base class API changed in JasperReports 7.0.",
                "JRDefaultScriptlet API changed in JasperReports 7.0; review custom scriptlets");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}