package com.warhex.er.generator.codegen.helpers;

import com.warhex.er.generator.ast.IdlSpecification;
import com.warhex.er.generator.binding.TemplateInstantiator;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Central registry of named built-in helpers for the code generation pipeline.
 *
 * <p>A helper is an opaque Java object placed into the Velocity context so that
 * templates can call methods on it.  Helpers are activated per-project via the
 * {@code helpers} list in {@code codegen.yaml}:
 *
 * <pre>
 * helpers:
 *   - id: entity_reactor   # matches key in this registry
 *     as: er               # Velocity context variable name (overrides default)
 * </pre>
 *
 * <h2>Registered helpers</h2>
 * <pre>
 *   "entity_reactor"  →  {@link EntityReactorHelper}  (default var: {@code $er})
 * </pre>
 *
 * <h2>Adding a new helper</h2>
 * Add an entry to {@link #FACTORIES} with a lower-case kebab or snake_case ID.
 * The factory receives the current {@link IdlSpecification} and
 * {@link TemplateInstantiator} so the helper can inspect IDL types at
 * construction time.
 */
public final class HelperRegistry {

    /** Factory type: {@code (spec, instantiator) → helper instance} */
    private static final Map<String, BiFunction<IdlSpecification, TemplateInstantiator, Object>>
            FACTORIES = Map.of(
                    "entity_reactor",
                    (spec, instantiator) -> new EntityReactorHelper(spec, instantiator)
            );

    /** Default Velocity context variable names keyed by helper ID. */
    private static final Map<String, String> DEFAULT_NAMES = Map.of(
            "entity_reactor", EntityReactorHelper.DEFAULT_VAR_NAME
    );

    private HelperRegistry() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Instantiates a helper by its registered ID.
     *
     * @param id           helper ID from the manifest (e.g. {@code "entity_reactor"})
     * @param spec         IDL specification to pass to the helper factory
     * @param instantiator template instantiator to pass to the helper factory
     * @return the helper object to place in the Velocity context
     * @throws IllegalArgumentException if {@code id} is not registered
     */
    public static Object get(String id,
                              IdlSpecification spec,
                              TemplateInstantiator instantiator) {
        BiFunction<IdlSpecification, TemplateInstantiator, Object> factory = FACTORIES.get(id);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "Unknown helper id '" + id + "'. Registered helpers: " + FACTORIES.keySet());
        }
        return factory.apply(spec, instantiator);
    }

    /**
     * Returns the default Velocity context variable name for a helper.
     *
     * @param id helper ID
     * @return default variable name (e.g. {@code "er"} for {@code "entity_reactor"})
     * @throws IllegalArgumentException if {@code id} is not registered
     */
    public static String defaultName(String id) {
        String name = DEFAULT_NAMES.get(id);
        if (name == null) {
            throw new IllegalArgumentException(
                    "Unknown helper id '" + id + "'. Registered helpers: " + FACTORIES.keySet());
        }
        return name;
    }

    /**
     * Returns {@code true} if the given ID is registered.
     */
    public static boolean isKnown(String id) {
        return FACTORIES.containsKey(id);
    }
}
