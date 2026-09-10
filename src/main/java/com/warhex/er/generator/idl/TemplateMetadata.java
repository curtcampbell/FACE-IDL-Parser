package com.warhex.er.generator.idl;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Parsed header metadata from a {@code .vtl} IDL template file.
 *
 * <h2>Template header conventions</h2>
 * The generator recognises optional Velocity comment directives in the first
 * 30 lines of a template.  All directives begin with {@code ## @} and are
 * otherwise ignored by the Velocity engine.
 *
 * <pre>
 *   ## @foreach  entity
 *   ## @filename ${structName}.idl
 * </pre>
 *
 * <h3>{@code @foreach}</h3>
 * Declares that the template must be rendered once per item in a named
 * collection.  Currently the only supported value is {@code entity}, which
 * causes the pipeline to loop over {@code model.entities} and inject
 * {@code $entity}, {@code $structName}, etc. into the context for each
 * iteration.
 *
 * <h3>{@code @filename}</h3>
 * Overrides the default output filename (which is the template's base name
 * with {@code .vtl} replaced by {@code .idl}).  The value is a
 * Velocity-style expression evaluated once per render with the current
 * context, so it may reference any context variable (e.g.
 * {@code ${structName}.idl}).
 *
 * <p>If {@code @foreach entity} is present and {@code @filename} is absent,
 * the pipeline falls back to {@code ${structName}.idl} automatically.
 *
 * <h2>Default behaviour (no directives)</h2>
 * The template is rendered exactly once and the output filename is derived
 * from the template filename: {@code my_file.vtl} → {@code my_file.idl}.
 */
public class TemplateMetadata {

    /** Lines scanned for header directives (scanning stops after this many). */
    private static final int SCAN_LINES = 30;

    // -----------------------------------------------------------------------
    // Foreach target constants
    // -----------------------------------------------------------------------

    /** No looping — template renders once per model. */
    public static final String FOREACH_NONE          = null;

    /** Loop over {@code model.entities}. */
    public static final String FOREACH_ENTITY        = "entity";

    /** Loop over unique nested struct types collected from all entities. */
    public static final String FOREACH_NESTED_STRUCT = "nested_struct";

    /**
     * Loop over each {@link com.warhex.er.generator.reader.dto.UoPData} in the
     * UoP model.  Each iteration binds {@code $uop} and {@code $uopName} in the
     * Velocity context, and the output is placed in a per-UoP subdirectory
     * ({@code <outputDir>/<UoPName>/}) under the template's mirrored output path.
     */
    public static final String FOREACH_UOP = "uop";

    /**
     * Loop over every (UoP, connection) pair in the UoP model.  Each iteration
     * binds {@code $uop}, {@code $uopName}, {@code $conn}, and {@code $connName}
     * in the Velocity context.  Output goes into a per-UoP subdirectory
     * ({@code <outputDir>/<UoPName>/}), one file per connection.
     *
     * <p>Templates that handle only one TypedTS variant should guard with a
     * variant check and {@code #stop} at the top so that the pipeline sees an
     * empty render for connections of the wrong kind — empty renders are silently
     * skipped without creating a file.
     *
     * <p>The {@code @filename} expression is evaluated <em>after</em> rendering
     * (and only for non-blank output) so that expressions that reference
     * {@code $conn.responseMessageType.name} are never evaluated for connections
     * where that field is {@code null}.
     */
    public static final String FOREACH_UOP_CONNECTION = "uop_connection";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Absolute path to the {@code .vtl} file. */
    private final Path templatePath;

    /**
     * Relative path from the template root to this template.
     * Used to mirror the directory structure in the output.
     */
    private final Path relativeTemplatePath;

    /**
     * Loop target, or {@code null} for a single-render template.
     * @see #FOREACH_ENTITY
     */
    private final String foreach;

    /**
     * Velocity expression for the output filename, or {@code null} to use
     * the template base name.
     */
    private final String filenameExpression;

    // -----------------------------------------------------------------------
    // Constructor (private — use factory)
    // -----------------------------------------------------------------------

    private TemplateMetadata(Path templatePath,
                             Path relativeTemplatePath,
                             String foreach,
                             String filenameExpression) {
        this.templatePath         = templatePath;
        this.relativeTemplatePath = relativeTemplatePath;
        this.foreach              = foreach;
        this.filenameExpression   = filenameExpression;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Reads {@code templatePath}, scans up to {@value #SCAN_LINES} lines for
     * {@code ## @} directives, and returns a populated {@link TemplateMetadata}.
     *
     * @param templatePath         absolute path to the {@code .vtl} file
     * @param relativeTemplatePath path relative to the template root directory
     * @throws IOException if the file cannot be read
     */
    public static TemplateMetadata parse(Path templatePath,
                                         Path relativeTemplatePath)
            throws IOException {

        String foreach            = null;
        String filenameExpression = null;

        try (BufferedReader reader =
                     Files.newBufferedReader(templatePath, StandardCharsets.UTF_8)) {

            int linesRead = 0;
            String line;
            while ((line = reader.readLine()) != null && linesRead < SCAN_LINES) {
                linesRead++;
                line = line.trim();

                // Only process Velocity comment lines that start with ## @
                if (!line.startsWith("##")) continue;
                String content = line.substring(2).trim();
                if (!content.startsWith("@")) continue;

                // Split on first whitespace: "@foreach  entity" → ["@foreach", "entity"]
                int space = content.indexOf(' ');
                if (space < 0) continue;
                String directive = content.substring(0, space).toLowerCase();
                String value     = content.substring(space).trim();

                switch (directive) {
                    case "@foreach":
                        foreach = value.toLowerCase();
                        break;
                    case "@filename":
                        filenameExpression = value;
                        break;
                    default:
                        // Unknown directive — ignore
                        break;
                }
            }
        }

        return new TemplateMetadata(templatePath, relativeTemplatePath,
                foreach, filenameExpression);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Absolute path to the {@code .vtl} file on the filesystem. */
    public Path getTemplatePath() { return templatePath; }

    /**
     * Path relative to the template root directory.
     * The output directory is constructed by replacing {@code .vtl} with
     * {@code .idl} on the last component.
     */
    public Path getRelativeTemplatePath() { return relativeTemplatePath; }

    /**
     * Returns {@code true} if this template should be rendered once per
     * entity in the model ({@code @foreach entity} directive present).
     */
    public boolean isForeachEntity() {
        return FOREACH_ENTITY.equals(foreach);
    }

    /**
     * Returns {@code true} if this template should be rendered once per unique
     * nested struct type in the model ({@code @foreach nested_struct} directive present).
     */
    public boolean isForeachNestedStruct() {
        return FOREACH_NESTED_STRUCT.equals(foreach);
    }

    /**
     * Returns {@code true} if this template should be rendered once per UoP in
     * the UoP model ({@code @foreach uop} directive present).
     * Output is placed in a per-UoP subdirectory named after the UoP.
     */
    public boolean isForeachUoP() {
        return FOREACH_UOP.equals(foreach);
    }

    /**
     * Returns {@code true} if this template should be rendered once per
     * (UoP, connection) pair in the UoP model ({@code @foreach uop_connection}
     * directive present).  Output is placed in a per-UoP subdirectory.
     */
    public boolean isForeachUoPConnection() {
        return FOREACH_UOP_CONNECTION.equals(foreach);
    }

    /**
     * Returns the raw {@code @foreach} value, or {@link Optional#empty()} if
     * the directive was absent.
     */
    public Optional<String> getForeach() {
        return Optional.ofNullable(foreach);
    }

    /**
     * Returns the {@code @filename} expression, or {@link Optional#empty()}
     * if the directive was absent.  When present the expression is a Velocity
     * snippet evaluated at render time (e.g. {@code ${structName}.idl}).
     */
    public Optional<String> getFilenameExpression() {
        return Optional.ofNullable(filenameExpression);
    }

    /**
     * Derives the default output filename by replacing the {@code .vtl}
     * extension with {@code .idl} on the template's file name component.
     * This is used when no {@code @filename} directive is present.
     */
    public String defaultOutputFilename() {
        String name = templatePath.getFileName().toString();
        if (name.endsWith(".vtl")) {
            name = name.substring(0, name.length() - 4) + ".idl";
        }
        return name;
    }

    /**
     * Returns the relative output directory that mirrors the template's
     * position under the template root (parent of the template file).
     */
    public Path relativeOutputDirectory() {
        Path parent = relativeTemplatePath.getParent();
        return (parent != null) ? parent : Path.of("");
    }

    @Override
    public String toString() {
        return "TemplateMetadata{" +
               "path=" + relativeTemplatePath +
               ", foreach=" + foreach +
               ", filename=" + filenameExpression +
               '}';
    }
}
