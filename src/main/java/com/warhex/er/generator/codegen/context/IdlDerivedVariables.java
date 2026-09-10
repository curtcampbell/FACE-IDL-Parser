package com.warhex.er.generator.codegen.context;

import com.warhex.er.generator.ast.*;

import java.util.*;
import java.util.logging.Logger;

/**
 * Derives the five project-specific Velocity context variables that were
 * previously hard-coded in each project's {@code codegen.yaml} manifest.
 *
 * <h2>Derived variables</h2>
 * <pre>
 *   $model_namespace          FACE::DM::SampleModel
 *   $project_namespace        SampleModel
 *   $face_tss_namespace       FACE::TSS::SampleModel
 *   $entity_payload_idl       FACE/DM/SampleModel/EntityPayload.hpp
 *   $entity_payload_idl_enum  EntityTypeEnum
 * </pre>
 *
 * <h2>Derivation rules</h2>
 * <ol>
 *   <li>{@code model_namespace} — namespace path of the module that directly
 *       contains structs whose names end with {@code "Entity"}.  When multiple
 *       modules qualify, the one with the most matching structs wins; ties are
 *       broken by longest (most-specific) namespace path.</li>
 *   <li>{@code project_namespace} — the last {@code ::}-delimited segment of
 *       {@code model_namespace}.</li>
 *   <li>{@code face_tss_namespace} — search the spec for a module whose full
 *       path is {@code FACE::TSS::<project_namespace>}; if not found, derive
 *       mechanically by replacing the {@code DM} component of
 *       {@code model_namespace} with {@code TSS}.</li>
 *   <li>{@code entity_payload_idl} — {@code model_namespace} with {@code ::}
 *       replaced by {@code /} plus {@code /EntityPayload.hpp}.</li>
 *   <li>{@code entity_payload_idl_enum} — name of the first enum found anywhere
 *       in the spec whose value names all (or predominantly) start with
 *       {@code "ENTITY_TYPE_"}.</li>
 * </ol>
 *
 * <p>If the spec contains no entity structs, an empty map is returned and a
 * warning is logged.  No variable is ever set to {@code null}; absent variables
 * are simply omitted from the returned map so that higher-priority sources
 * (manifest or {@code --var} flags) can supply them.
 */
public final class IdlDerivedVariables {

    private static final Logger LOG =
            Logger.getLogger(IdlDerivedVariables.class.getName());

    private static final String ENTITY_SUFFIX   = "Entity";
    private static final String ENTITY_TYPE_PFX = "ENTITY_TYPE_";

    private IdlDerivedVariables() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Derives context variables from {@code spec}.
     *
     * @param spec the merged IDL specification
     * @return ordered map of variable-name → value; never {@code null},
     *         may be empty if the spec does not contain entity structs
     */
    public static Map<String, String> derive(IdlSpecification spec) {
        Map<String, String> result = new LinkedHashMap<>();

        // --- Step 1: find the module namespace that owns entity structs ------
        List<String> modelNsSegments = findModelNamespaceSegments(spec);
        if (modelNsSegments.isEmpty()) {
            LOG.warning("IdlDerivedVariables: no structs ending in 'Entity' found in spec. "
                    + "Skipping IDL-derived variable injection.");
            return result;
        }

        String modelNs = String.join("::", modelNsSegments);
        result.put("model_namespace", modelNs);
        LOG.info("Derived $model_namespace = " + modelNs);

        // --- Step 2: project_namespace ---------------------------------------
        String projectNs = modelNsSegments.get(modelNsSegments.size() - 1);
        result.put("project_namespace", projectNs);
        LOG.info("Derived $project_namespace = " + projectNs);

        // --- Step 3: face_tss_namespace --------------------------------------
        String tssNs = findOrDeriveTssNamespace(spec, modelNsSegments, projectNs);
        if (tssNs != null) {
            result.put("face_tss_namespace", tssNs);
            LOG.info("Derived $face_tss_namespace = " + tssNs);
        }

        // --- Step 4: entity_payload_idl --------------------------------------
        String payloadIdl = modelNs.replace("::", "/") + "/EntityPayload.hpp";
        result.put("entity_payload_idl", payloadIdl);
        LOG.info("Derived $entity_payload_idl = " + payloadIdl);

        // --- Step 5: entity_payload_idl_enum ---------------------------------
        String enumName = findEntityTypeEnum(spec);
        if (enumName != null) {
            result.put("entity_payload_idl_enum", enumName);
            LOG.info("Derived $entity_payload_idl_enum = " + enumName);
        } else {
            LOG.warning("IdlDerivedVariables: could not find an enum whose values "
                    + "start with '" + ENTITY_TYPE_PFX + "'. "
                    + "$entity_payload_idl_enum will not be injected.");
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Step 1: find the module namespace containing entity structs
    // -----------------------------------------------------------------------

    /**
     * Walks the spec looking for {@link StructNode}s whose names end with
     * {@code "Entity"}.  Counts how many such structs each candidate namespace
     * contains.  Returns the namespace segments of the winner (most structs;
     * longest path as a tiebreaker).
     */
    private static List<String> findModelNamespaceSegments(IdlSpecification spec) {
        // namespace-path (as joined string) → count of entity structs
        Map<String, Integer> scoreboard = new LinkedHashMap<>();
        // namespace-path → its segments list (preserved for return)
        Map<String, List<String>> segmentsByPath = new LinkedHashMap<>();

        walkForEntityStructs(spec.definitions(), new ArrayDeque<>(), scoreboard, segmentsByPath);

        if (scoreboard.isEmpty()) return List.of();

        // pick winner: highest score, then longest path as tiebreaker
        String winner = scoreboard.entrySet().stream()
                .max((a, b) -> {
                    int cmp = Integer.compare(a.getValue(), b.getValue());
                    if (cmp != 0) return cmp;
                    return Integer.compare(
                            segmentsByPath.get(a.getKey()).size(),
                            segmentsByPath.get(b.getKey()).size());
                })
                .map(Map.Entry::getKey)
                .orElse(null);

        if (winner == null) return List.of();

        if (scoreboard.size() > 1) {
            LOG.warning("IdlDerivedVariables: multiple module namespaces contain "
                    + "Entity structs: " + scoreboard.keySet()
                    + ". Selected: " + winner);
        }

        return segmentsByPath.get(winner);
    }

    private static void walkForEntityStructs(List<IdlDefinition> defs,
                                              Deque<String> nsStack,
                                              Map<String, Integer> scoreboard,
                                              Map<String, List<String>> segmentsByPath) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                nsStack.addLast(m.name());
                walkForEntityStructs(m.definitions(), nsStack, scoreboard, segmentsByPath);
                nsStack.removeLast();
            } else if (def instanceof StructNode s && s.name().endsWith(ENTITY_SUFFIX)) {
                if (!nsStack.isEmpty()) {
                    String key = String.join("::", nsStack);
                    scoreboard.merge(key, 1, Integer::sum);
                    segmentsByPath.computeIfAbsent(key, k -> List.copyOf(nsStack));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Step 3: face_tss_namespace
    // -----------------------------------------------------------------------

    /**
     * Looks for a module in the spec whose full path is
     * {@code FACE::TSS::<projectNs>}.  If found, returns that string.
     * Otherwise falls back to mechanically replacing the {@code DM} component
     * in {@code modelNsSegments} with {@code TSS}.
     */
    private static String findOrDeriveTssNamespace(IdlSpecification spec,
                                                    List<String> modelNsSegments,
                                                    String projectNs) {
        // Canonical expected path
        String candidate = "FACE::TSS::" + projectNs;
        if (moduleExists(spec.definitions(), new ArrayDeque<>(), candidate)) {
            return candidate;
        }

        // Mechanical fallback: replace "DM" segment with "TSS"
        List<String> tssSegments = new ArrayList<>(modelNsSegments);
        boolean replaced = false;
        for (int i = 0; i < tssSegments.size(); i++) {
            if ("DM".equals(tssSegments.get(i))) {
                tssSegments.set(i, "TSS");
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            LOG.warning("IdlDerivedVariables: could not find '" + candidate
                    + "' in spec and 'DM' component not present in model_namespace '"
                    + String.join("::", modelNsSegments)
                    + "'. $face_tss_namespace will not be injected.");
            return null;
        }
        String derived = String.join("::", tssSegments);
        LOG.fine("IdlDerivedVariables: '" + candidate
                + "' not found in spec; using mechanical derivation: " + derived);
        return derived;
    }

    private static boolean moduleExists(List<IdlDefinition> defs,
                                         Deque<String> nsStack,
                                         String targetPath) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                nsStack.addLast(m.name());
                if (String.join("::", nsStack).equals(targetPath)) {
                    nsStack.removeLast();
                    return true;
                }
                if (moduleExists(m.definitions(), nsStack, targetPath)) {
                    nsStack.removeLast();
                    return true;
                }
                nsStack.removeLast();
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Step 5: entity_payload_idl_enum
    // -----------------------------------------------------------------------

    /**
     * Walks the spec for an {@link EnumNode} where at least one value name
     * starts with {@code "ENTITY_TYPE_"}.  Returns the enum's simple name
     * (e.g. {@code "EntityTypeEnum"}).
     */
    private static String findEntityTypeEnum(IdlSpecification spec) {
        return walkForEntityTypeEnum(spec.definitions());
    }

    private static String walkForEntityTypeEnum(List<IdlDefinition> defs) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                String found = walkForEntityTypeEnum(m.definitions());
                if (found != null) return found;
            } else if (def instanceof EnumNode e) {
                for (EnumValueNode v : e.values()) {
                    if (v.name().startsWith(ENTITY_TYPE_PFX)) {
                        return e.name();
                    }
                }
            }
        }
        return null;
    }
}
