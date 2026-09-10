package com.warhex.er.generator.codegen.manifest;

import java.util.List;
import java.util.Map;

/**
 * Root POJO for a {@code codegen.yaml} manifest file.
 *
 * <p>A manifest lives in the user's template directory alongside the
 * {@code .vm} template files.  It drives the
 * {@link com.warhex.er.generator.codegen.pipeline.CodeGenPipeline}: one
 * {@link GenerationEntry} per template-to-output mapping.
 *
 * <h2>Minimal example — EntityReactor TSS</h2>
 * <pre>
 * output_subdirectory: cpp
 * language_dir: path/to/languages/cpp   # optional; enables $types in templates
 *
 * generations:
 *   - template: entity_reactor_ts_impl.hpp.vm
 *     for_each: TEMPLATE_INST
 *     output:   "{inst.alias}Impl.hpp"
 *
 *   - template: entity_reactor_ts_impl.cpp.vm
 *     for_each: TEMPLATE_INST
 *     output:   "{inst.alias}Impl.cpp"
 *
 *   - template: transport_service.hpp.vm
 *     for_each: SPEC
 *     output:   "TransportService.hpp"
 * </pre>
 *
 * <h2>UoP skeleton example</h2>
 * <pre>
 * generations:
 *   - template: uop_component.hpp.vm
 *     for_each: UOP
 *     output:   "{uop.name}/{uop.name}Impl.hpp"
 *
 *   - template: connection_ts_impl.hpp.vm
 *     for_each: CONNECTION
 *     output:   "{uop.name}/{conn.name}Impl.hpp"
 * </pre>
 */
public class CodeGenManifest {

    /**
     * Optional subdirectory appended to {@code --output-dir} before writing
     * generated files.  Useful when generating for multiple languages into
     * one root (e.g. {@code "cpp"}, {@code "java"}).
     * May be {@code null} (no subdirectory).
     */
    public String output_subdirectory;

    /**
     * Optional path to a {@code language.yaml} descriptor directory
     * (the same format used by {@code face-idl-binder}'s language descriptors).
     * When provided, a {@link com.warhex.er.generator.binding.generic.TypeResolver}
     * is constructed and exposed as {@code $types} in every template context,
     * giving templates the same type-name resolution helpers available in the
     * binder.
     *
     * <p>May be an absolute path or relative to the {@code --template-dir}.
     * If {@code null}, {@code $types} is not placed in the context.
     */
    public String language_dir;

    /**
     * Optional list of built-in helpers to activate for this template set.
     * Each entry names a registered helper and the Velocity context variable
     * it should be placed under.
     *
     * <pre>
     * helpers:
     *   - id: entity_reactor
     *     as: er
     * </pre>
     *
     * When null or empty, no helpers are added.  Templates then rely solely
     * on {@code $spec}, {@code $model}, {@code $types}, and the scope variables.
     */
    public List<HelperEntry> helpers;

    /**
     * Optional map of project-specific string variables placed directly into
     * the Velocity context under their key names.  Useful for values that are
     * constant across all renders but specific to a project deployment.
     *
     * <pre>
     * variables:
     *   model_namespace: "FACE::DM::SampleModel"
     *   project_namespace: "SampleModel"
     *   reactor_include: "WARHEX/EntityReactor"
     * </pre>
     *
     * Keys must be valid Velocity identifiers (no spaces).
     */
    public Map<String, String> variables;

    /**
     * Ordered list of generation entries.  Templates are rendered in document
     * order; output files from earlier entries are visible on disk when later
     * entries run (relevant if a later template reads previously generated
     * files, though that pattern is unusual).
     */
    public List<GenerationEntry> generations;

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /**
     * Validates that the manifest is structurally complete.
     *
     * @throws IllegalStateException if any required field is missing
     */
    public void validate() {
        if (generations == null || generations.isEmpty()) {
            // Empty generations is allowed: templates may be entirely self-describing
            // via ##! directives (Phase 5+). A WARNING is still logged so an accidental
            // empty manifest is visible.
            java.util.logging.Logger.getLogger(CodeGenManifest.class.getName())
                    .warning("codegen.yaml contains no 'generations' entries. "
                            + "All generation will be driven by ##! template directives.");
            return;
        }
        for (GenerationEntry e : generations) {
            if (e.template == null || e.template.isBlank()) {
                throw new IllegalStateException(
                        "A generation entry is missing 'template'.");
            }
            if (e.output == null || e.output.isBlank()) {
                throw new IllegalStateException(
                        "Generation entry '" + e.template + "' is missing 'output'.");
            }
            // Validate for_each parses cleanly (throws IllegalArgumentException on bad value)
            e.resolvedScope();
        }
    }

    @Override
    public String toString() {
        return "CodeGenManifest{generations=" + (generations != null ? generations.size() : 0)
                + ", output_subdirectory='" + output_subdirectory + "'}";
    }
}
