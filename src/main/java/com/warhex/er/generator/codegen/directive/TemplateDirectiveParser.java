package com.warhex.er.generator.codegen.directive;

import com.warhex.er.generator.codegen.manifest.ForEachScope;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Parses {@code ##!} directive lines from the header of a Velocity {@code .vm}
 * or {@code .vtl} template file.
 *
 * <h2>Directive format</h2>
 * <pre>
 *   ##! for_each: STRUCT
 *   ##! filter:   .*Entity$
 *   ##! output:   {struct.name}Registrar.hpp
 *   ##! driver:   true
 * </pre>
 *
 * <p>Each line must start with {@code ##!} (optionally preceded by whitespace).
 * The parser stops as soon as it encounters a non-blank, non-comment line
 * (i.e. a line that does not start with {@code ##}), so the template body is
 * never touched.
 *
 * <p>Unknown directive keys are silently ignored so future keys are forward-compatible.
 */
public final class TemplateDirectiveParser {

    private static final Logger LOG =
            Logger.getLogger(TemplateDirectiveParser.class.getName());

    /** Prefix that distinguishes pipeline directives from plain Velocity comments. */
    private static final String DIRECTIVE_PREFIX = "##!";

    /** Velocity comment prefix (lines starting with this are part of the header). */
    private static final String COMMENT_PREFIX = "##";

    /** Maximum header lines scanned before giving up. */
    private static final int MAX_HEADER_LINES = 50;

    private TemplateDirectiveParser() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Parses the directive header of {@code templateFile}.
     *
     * @param templateFile path to a {@code .vm} or {@code .vtl} template
     * @return parsed directives; {@link TemplateDirectives#hasDirectives()} is
     *         {@code false} when no {@code ##!} lines are present
     * @throws IOException if the file cannot be read
     */
    public static TemplateDirectives parse(Path templateFile) throws IOException {
        ForEachScope forEachScope = null;
        String       outputPattern = null;
        String       filterPattern = null;
        boolean      driver        = false;
        boolean      hasDirectives = false;

        try (BufferedReader reader = Files.newBufferedReader(templateFile, StandardCharsets.UTF_8)) {
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = reader.readLine();
                if (line == null) break;

                String trimmed = line.trim();

                if (trimmed.startsWith(DIRECTIVE_PREFIX)) {
                    hasDirectives = true;
                    String rest  = trimmed.substring(DIRECTIVE_PREFIX.length()).trim();
                    int    colon = rest.indexOf(':');
                    if (colon < 0) {
                        LOG.warning("Malformed ##! directive (missing ':') in "
                                + templateFile.getFileName() + ": \"" + trimmed + "\"");
                        continue;
                    }
                    String key   = rest.substring(0, colon).trim();
                    String value = rest.substring(colon + 1).trim();
                    switch (key) {
                        case "for_each" -> {
                            try {
                                forEachScope = ForEachScope.valueOf(value.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                LOG.warning("Unknown for_each scope '" + value + "' in "
                                        + templateFile.getFileName()
                                        + ". Valid scopes: " + java.util.Arrays.toString(ForEachScope.values()));
                            }
                        }
                        case "output" -> outputPattern = value;
                        case "filter" -> filterPattern = value;
                        case "driver" -> driver = "true".equalsIgnoreCase(value);
                        default       -> LOG.fine("Unknown ##! key '" + key + "' in "
                                + templateFile.getFileName() + "; ignored (forward-compatible).");
                    }
                } else if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
                    // Plain comment or blank line — keep scanning the header.
                } else {
                    // First non-comment, non-blank line: end of header.
                    break;
                }
            }
        }

        return new TemplateDirectives(forEachScope, outputPattern, filterPattern, driver, hasDirectives);
    }
}
