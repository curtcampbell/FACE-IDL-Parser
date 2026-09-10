package com.warhex.er.generator.codegen.manifest;

/**
 * One entry in the {@link CodeGenManifest#helpers} list.
 *
 * <p>Each entry names a built-in helper from the
 * {@link com.warhex.er.generator.codegen.helpers.HelperRegistry} and optionally
 * overrides the Velocity context variable name it is placed under.
 *
 * <h2>Manifest syntax</h2>
 * <pre>
 * helpers:
 *   - id: entity_reactor    # required; must match a registered helper ID
 *     as: er                # optional; defaults to the helper's own default name
 * </pre>
 *
 * <h2>Currently registered helper IDs</h2>
 * <pre>
 *   entity_reactor  → EntityReactorHelper  (default context var: "er")
 * </pre>
 */
public class HelperEntry {

    /**
     * Registered helper ID.  Must match a key in
     * {@link com.warhex.er.generator.codegen.helpers.HelperRegistry}.
     */
    public String id;

    /**
     * Velocity context variable name under which the helper is placed.
     * Optional; when null or blank the helper's own default name is used.
     */
    public String as;

    @Override
    public String toString() {
        return "HelperEntry{id='" + id + "', as='" + as + "'}";
    }
}
