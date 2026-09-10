package com.warhex.er.generator.codegen.manifest;

/**
 * One entry in a {@link CodeGenManifest}: maps a Velocity template to an
 * iteration scope and an output-path pattern.
 *
 * <p>YAML key names use snake_case to match SnakeYAML's default field-mapping
 * convention.  A minimal entry looks like:
 * <pre>
 *   - template: entity_reactor_impl.hpp.vm
 *     for_each: TEMPLATE_INST
 *     output:   "{inst.alias}Impl.hpp"
 * </pre>
 *
 * <h2>Output path tokens</h2>
 * Tokens are resolved by {@link com.warhex.er.generator.codegen.pipeline.OutputPathResolver}.
 * Available tokens depend on the active {@link ForEachScope}; see that enum for details.
 *
 * <h2>Template search</h2>
 * {@code template} is a path relative to the {@code --template-dir} argument.
 * Sub-directories are allowed: {@code "cpp/entity_reactor_impl.hpp.vm"}.
 */
public class GenerationEntry {

    /**
     * Template file path relative to the user's {@code --template-dir}.
     * Must end in {@code .vm}.
     * Example: {@code "entity_reactor_impl.hpp.vm"}
     */
    public String template;

    /**
     * The scope that controls iteration.  Must be a valid {@link ForEachScope}
     * name (case-insensitive).
     * Example: {@code "TEMPLATE_INST"}, {@code "uop"}
     */
    public String for_each;

    /**
     * Optional regular expression matched against the primary name of the
     * current scope element before rendering.  When set, only elements whose
     * name matches the pattern are rendered; all others are silently skipped.
     *
     * <p>The name tested depends on the active scope:
     * <ul>
     *   <li>{@code STRUCT}        → {@code StructNode.name()}</li>
     *   <li>{@code INTERFACE}     → {@code InterfaceNode.name()}</li>
     *   <li>{@code TEMPLATE_INST} → {@code TemplateInstNode.alias()}</li>
     *   <li>{@code MODULE}        → {@code ModuleNode.name()}</li>
     *   <li>{@code UOP}           → {@code UoPData.getName()}</li>
     *   <li>{@code CONNECTION}    → {@code ConnectionData.getName()}</li>
     *   <li>{@code SPEC}          → (not applicable; pattern is ignored)</li>
     * </ul>
     *
     * <p>Uses {@link String#matches(String)} (full-string Java regex).
     * Example: {@code ".*Entity$"} matches only structs ending in "Entity".
     * Example: {@code "^EntityEvent$"} matches exactly one template instantiation.
     * Null or blank means "match everything."
     */
    public String name_pattern;

    /**
     * Output file path pattern relative to the effective output directory
     * (i.e. {@code --output-dir} with optional {@link CodeGenManifest#output_subdirectory}
     * appended).
     *
     * <p>Tokens in curly braces are replaced with properties of the current
     * scope element.  See {@link ForEachScope} for the token list per scope.
     * Example: {@code "{inst.alias}Impl.hpp"}
     */
    public String output;

    // -----------------------------------------------------------------------
    // Convenience
    // -----------------------------------------------------------------------

    /**
     * Parses and returns the {@link ForEachScope} for this entry.
     *
     * @return resolved scope
     * @throws IllegalArgumentException if {@link #for_each} is null or unrecognised
     */
    public ForEachScope resolvedScope() {
        if (for_each == null || for_each.isBlank()) {
            throw new IllegalArgumentException(
                    "Generation entry is missing 'for_each': template=" + template);
        }
        try {
            return ForEachScope.valueOf(for_each.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown for_each scope '" + for_each + "' in entry: template=" + template
                    + ". Valid values: " + java.util.Arrays.toString(ForEachScope.values()));
        }
    }

    /**
     * Returns {@code true} if {@code elementName} satisfies the {@link #name_pattern}
     * filter for this entry.  When {@code name_pattern} is null or blank the method
     * always returns {@code true}.
     *
     * @param elementName the name of the current scope element
     * @return {@code true} if the element should be rendered
     */
    public boolean matchesPattern(String elementName) {
        if (name_pattern == null || name_pattern.isBlank()) return true;
        return elementName != null && elementName.matches(name_pattern);
    }

    @Override
    public String toString() {
        return "GenerationEntry{template='" + template
                + "', for_each='" + for_each
                + (name_pattern != null ? "', name_pattern='" + name_pattern : "")
                + "', output='" + output + "'}";
    }
}
