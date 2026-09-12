package com.warhex.er.generator;

import picocli.CommandLine.IVersionProvider;

import java.io.InputStream;
import java.util.Properties;

/**
 * Supplies the {@code --version} string for every FACE IDL tool CLI from the
 * build rather than from a hardcoded literal.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code /face-idl-tools-version.properties} on the classpath — written by
 *       {@code src/main/resources} with Maven resource filtering enabled, so it
 *       always carries the built {@code ${project.version}}.</li>
 *   <li>The jar manifest's {@code Implementation-Version}, set by the shade
 *       plugin's {@code ManifestResourceTransformer}.</li>
 *   <li>The literal {@code "unknown"} — never an out-of-date version number.</li>
 * </ol>
 *
 * <p>The same value is written to the {@code VERSION} file at the root of the
 * distribution zip, so a consumer can pin a toolchain version and verify it
 * either by reading that file or by running {@code <tool> --version}.
 */
public final class ToolVersionProvider implements IVersionProvider {

    /** Classpath resource written by Maven resource filtering. */
    private static final String VERSION_RESOURCE = "/face-idl-tools-version.properties";

    /** Value reported when no version can be determined. */
    public static final String UNKNOWN = "unknown";

    @Override
    public String[] getVersion() {
        return new String[] { "face-idl-tools " + version() };
    }

    /**
     * Returns the built version of the FACE IDL tools.
     *
     * @return the version string, or {@value #UNKNOWN} if it cannot be determined
     */
    public static String version() {
        String fromResource = fromVersionResource();
        if (fromResource != null) return fromResource;

        String fromManifest = fromJarManifest();
        if (fromManifest != null) return fromManifest;

        return UNKNOWN;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static String fromVersionResource() {
        try (InputStream in = ToolVersionProvider.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) return null;
            Properties props = new Properties();
            props.load(in);
            return usable(props.getProperty("version"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String fromJarManifest() {
        Package pkg = ToolVersionProvider.class.getPackage();
        return (pkg == null) ? null : usable(pkg.getImplementationVersion());
    }

    /**
     * Rejects null, blank, and unsubstituted values. An unfiltered resource
     * still contains the literal {@code ${project.version}}; reporting that
     * would be worse than reporting nothing.
     */
    private static String usable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("${")) return null;
        return trimmed;
    }

    /** Public no-arg constructor -- picocli instantiates this class reflectively. */
    public ToolVersionProvider() {
    }
}
