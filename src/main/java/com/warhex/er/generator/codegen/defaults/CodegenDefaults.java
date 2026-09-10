package com.warhex.er.generator.codegen.defaults;

import com.warhex.er.generator.codegen.manifest.HelperEntry;

import java.util.List;
import java.util.Map;

/**
 * POJO for a {@code codegen-defaults.yaml} file co-located with a template set.
 *
 * <p>Template-set-level constants belong here rather than in a per-project
 * {@code codegen.yaml}.  Values here are injected into the Velocity context
 * at a lower priority than manifest variables, so a project can still override
 * them when needed.
 *
 * <h2>File format</h2>
 * <pre>
 * variables:
 *   reactor_namespace: "WARHEX::EntityReactor"
 *   reactor_include:   "WARHEX/EntityReactor"
 *
 * helpers:
 *   - id: entity_reactor
 *     as: er
 * </pre>
 *
 * <p>The file is optional.  When absent,
 * {@link CodegenDefaultsLoader#load(java.nio.file.Path)} returns an empty instance.
 */
public class CodegenDefaults {

    /**
     * Template-set-level string variables injected into every Velocity context
     * before manifest variables are applied (so the manifest can override them).
     * May be {@code null} or empty.
     */
    public Map<String, String> variables;

    /**
     * Built-in helpers to activate for this template set.
     * Same structure as {@link com.warhex.er.generator.codegen.manifest.CodeGenManifest#helpers}.
     * May be {@code null} or empty.
     */
    public List<HelperEntry> helpers;
}
