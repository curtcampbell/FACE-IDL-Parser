package com.warhex.er.generator.codegen.directive;

import com.warhex.er.generator.codegen.manifest.ForEachScope;

/**
 * Parsed {@code ##!} directive state for a single {@code .vm} template file.
 *
 * <p>Directives are declared in the file header as Velocity comment lines with
 * a {@code !} marker so the pipeline recognises them before Velocity renders:
 * <pre>
 *   ##! for_each: STRUCT
 *   ##! filter:   .*Entity$
 *   ##! output:   {struct.name}Registrar.hpp
 *   ##! driver:   true
 * </pre>
 *
 * <p>Produced by {@link TemplateDirectiveParser#parse(java.nio.file.Path)}.
 *
 * @param forEachScope  iteration scope declared by {@code ##! for_each};
 *                      {@code null} means {@link ForEachScope#GLOBAL} (single-pass,
 *                      no primary variable added to context)
 * @param outputPattern output path pattern from {@code ##! output};
 *                      {@code null} when not declared — required unless the
 *                      template is a {@code driver}
 * @param filterPattern Java full-string regex from {@code ##! filter};
 *                      {@code null} means accept all scope elements
 * @param driver        {@code true} when {@code ##! driver: true} is present;
 *                      the pipeline does not write direct output, instead
 *                      routing output through {@code $outFile} / {@code #parse}
 *                      interception (Phase 6)
 * @param hasDirectives {@code true} when at least one {@code ##!} line was found
 *                      in the template header
 */
public record TemplateDirectives(
        ForEachScope forEachScope,
        String       outputPattern,
        String       filterPattern,
        boolean      driver,
        boolean      hasDirectives) {

    /** Convenience factory — represents a file with no {@code ##!} directives. */
    public static TemplateDirectives none() {
        return new TemplateDirectives(null, null, null, false, false);
    }
}
