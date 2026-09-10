package com.warhex.er.generator.binding;

import com.warhex.er.generator.ast.*;

import java.util.*;
import java.util.logging.Logger;

/**
 * Resolves and instantiates IDL template modules from an {@link IdlSpecification}.
 *
 * <h2>Process</h2>
 * <ol>
 *   <li>Walks the spec collecting {@link TemplateModuleNode} declarations into a
 *       registry keyed by fully-qualified name.  Multiple arities of the same name
 *       (e.g. {@code Typed<DATATYPE_TYPE>} vs {@code Typed<DATATYPE_TYPE,RESPONSE_DATATYPE>})
 *       are stored as separate entries and matched by arity at lookup time.</li>
 *   <li>Builds a typedef map from typedef names → underlying types so that
 *       actual parameters like {@code EntityCrudRequest_t} can be resolved to
 *       their canonical qualified names (e.g. {@code ::FACE::DM::SampleModel::EntityCrudRequest}).</li>
 *   <li>For a given {@link TemplateInstNode}, maps each actual parameter through the
 *       typedef chain, builds the formal→actual substitution map, and deep-copies
 *       the template body replacing every occurrence of a formal parameter name
 *       with its resolved concrete type.</li>
 * </ol>
 *
 * <h2>{@code inout} parameter conventions</h2>
 * Three distinct cases arise in FACE template bodies:
 * <ul>
 *   <li><b>Local interface</b> ({@code inout Read_Callback callback}) →
 *       {@code I**} (double pointer to abstract class)</li>
 *   <li><b>Interface-kind formal param</b> ({@code inout INTERFACE_TYPE ref}) →
 *       {@code T*&} (pointer reference; Injectable idiom)</li>
 *   <li><b>Typename formal param</b> ({@code inout RESPONSE_DATATYPE return_data}) →
 *       {@code T&} (standard out reference; struct type)</li>
 * </ul>
 */
public final class TemplateInstantiator {

    private static final Logger LOG =
            Logger.getLogger(TemplateInstantiator.class.getName());

    // -----------------------------------------------------------------------
    // InstantiationResult — carries all context the mapper needs
    // -----------------------------------------------------------------------

    /**
     * Result of a successful template instantiation.
     */
    public static final class InstantiationResult {
        /** Substituted definitions (interfaces / structs) from the template body. */
        public final List<IdlDefinition> definitions;

        /** Simple names of interfaces declared <em>inside</em> the template body. */
        public final Set<String> localInterfaceNames;

        /**
         * Fully-qualified names of actual parameters that came from
         * {@code interface}-kind formal parameters.  Used by
         * {@link com.warhex.er.generator.binding.cpp.CppTypeHelper#paramDeclFull}
         * to emit {@code T*&} rather than {@code T**} for {@code inout}.
         */
        public final Set<String> interfaceKindActuals;

        /**
         * Resolved actual type names in formal-parameter order.
         * Used to compute {@code #include} paths for the generated header.
         */
        public final List<IdlType> resolvedActuals;

        InstantiationResult(List<IdlDefinition> definitions,
                            Set<String> localInterfaceNames,
                            Set<String> interfaceKindActuals,
                            List<IdlType> resolvedActuals) {
            this.definitions         = List.copyOf(definitions);
            this.localInterfaceNames = Set.copyOf(localInterfaceNames);
            this.interfaceKindActuals = Set.copyOf(interfaceKindActuals);
            this.resolvedActuals     = List.copyOf(resolvedActuals);
        }

        // JavaBean getters — required by Velocity 2.x (public field access is
        // not guaranteed by the default UberspectImpl; getters are always safe).
        public List<IdlDefinition> getDefinitions()         { return definitions; }
        public Set<String>         getLocalInterfaceNames() { return localInterfaceNames; }
        public Set<String>         getInterfaceKindActuals(){ return interfaceKindActuals; }
        public List<IdlType>       getResolvedActuals()     { return resolvedActuals; }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** qualifiedName → list of template nodes (one per arity). */
    private final Map<String, List<TemplateModuleNode>> templateRegistry;

    /** typedef alias (with and without leading {@code ::}) → underlying IdlType. */
    private final Map<String, IdlType> typedefMap;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Builds the template registry and typedef map from {@code spec}.
     *
     * @param spec the unified IdlSpecification (all included files pre-merged)
     */
    public TemplateInstantiator(IdlSpecification spec) {
        this.templateRegistry = new LinkedHashMap<>();
        this.typedefMap       = new LinkedHashMap<>();
        walkForTemplates(spec.definitions(), List.of(), templateRegistry);
        walkForTypedefs(spec.definitions(), typedefMap);
        LOG.fine("Template registry: " + templateRegistry.keySet());
        LOG.fine("Typedef map size: " + typedefMap.size());
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns the typedef map for use by TypeResolver. */
    public Map<String, IdlType> typedefMap() { return typedefMap; }

    /**
     * Instantiates the template referenced by {@code inst}.
     *
     * @param inst the instantiation node from the spec
     * @return a populated {@link InstantiationResult}, or empty if the
     *         referenced template module was not found in the registry
     */
    public Optional<InstantiationResult> instantiate(TemplateInstNode inst) {
        TemplateModuleNode tmpl = findTemplate(
                inst.templateName(), inst.actualParameters().size());
        if (tmpl == null) {
            LOG.warning("Template not found in registry: " + inst.templateName()
                    + "/" + inst.actualParameters().size());
            return Optional.empty();
        }

        List<FormalParameter> formals = tmpl.formalParameters();
        List<String> actuals = inst.actualParameters();

        // --- Resolve each actual parameter through the typedef chain ---
        List<IdlType> resolvedActuals = new ArrayList<>();
        for (String actual : actuals) {
            resolvedActuals.add(resolveTypedef(actual));
        }

        // --- Build formal → resolved substitution map ---
        Map<String, IdlType> substitution = new LinkedHashMap<>();
        for (int i = 0; i < formals.size(); i++) {
            substitution.put(formals.get(i).name(), resolvedActuals.get(i));
        }

        // --- Identify interface-kind actuals ---
        Set<String> interfaceKindActuals = new LinkedHashSet<>();
        for (int i = 0; i < formals.size(); i++) {
            if ("interface".equals(formals.get(i).kind())) {
                IdlType resolved = resolvedActuals.get(i);
                if (resolved instanceof IdlType.Scoped s) {
                    interfaceKindActuals.add(s.qualifiedName());
                    // Also add without leading :: for lookup flexibility
                    String n = s.qualifiedName();
                    if (n.startsWith("::")) interfaceKindActuals.add(n.substring(2));
                }
            }
        }

        // --- Substitute through the template body ---
        List<IdlDefinition> substituted = substituteDefinitions(
                tmpl.definitions(), substitution);

        // --- Collect local interface names ---
        Set<String> localInterfaces = new LinkedHashSet<>();
        for (IdlDefinition def : substituted) {
            if (def instanceof InterfaceNode) {
                localInterfaces.add(def.name());
            }
        }

        LOG.fine("Instantiated " + inst.templateName() + "/" + inst.alias()
                + " → " + substituted.size() + " definitions");
        return Optional.of(new InstantiationResult(
                substituted, localInterfaces, interfaceKindActuals, resolvedActuals));
    }

    // -----------------------------------------------------------------------
    // Registry builders
    // -----------------------------------------------------------------------

    private void walkForTemplates(List<IdlDefinition> defs,
                                  List<String> ns,
                                  Map<String, List<TemplateModuleNode>> registry) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                List<String> newNs = new ArrayList<>(ns);
                newNs.add(m.name());
                walkForTemplates(m.definitions(), newNs, registry);
            } else if (def instanceof TemplateModuleNode t) {
                // Build fully-qualified name from enclosing namespace
                String qn = (ns.isEmpty() ? "" : "::" + String.join("::", ns)) + "::" + t.name();
                registry.computeIfAbsent(qn, k -> new ArrayList<>()).add(t);
                // Also register without leading :: for flexibility
                String bare = ns.isEmpty() ? t.name() : String.join("::", ns) + "::" + t.name();
                registry.computeIfAbsent(bare, k -> new ArrayList<>()).add(t);
                LOG.fine("Registered template: " + qn + " (arity=" + t.formalParameters().size() + ")");
            }
        }
    }

    private void walkForTypedefs(List<IdlDefinition> defs,
                                 Map<String, IdlType> map) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                walkForTypedefs(m.definitions(), map);
            } else if (def instanceof TypedefNode t) {
                // Store under bare name and fully-qualified variants
                map.put(t.name(), t.underlyingType());
                map.put("::" + t.name(), t.underlyingType());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Registry lookup
    // -----------------------------------------------------------------------

    /**
     * Finds the template module matching both qualified name and arity.
     * The IDL grammar allows the same template name to appear with different
     * parameter counts (e.g. {@code Typed<D>} in TypedTS.idl vs
     * {@code Typed<D,R>} in Extended.idl).
     */
    private TemplateModuleNode findTemplate(String templateName, int arity) {
        List<TemplateModuleNode> candidates = templateRegistry.get(templateName);
        if (candidates == null) return null;
        return candidates.stream()
                .filter(t -> t.formalParameters().size() == arity)
                .findFirst()
                .orElse(null);
    }

    // -----------------------------------------------------------------------
    // Typedef resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves {@code name} through the typedef chain to its canonical type.
     * For example {@code "EntityCrudRequest_t"} → {@code IdlType.Scoped("::FACE::DM::SampleModel::EntityCrudRequest")}.
     * Names that are not in the typedef map are returned as {@code IdlType.Scoped(name)}.
     */
    private IdlType resolveTypedef(String name) {
        Set<String> visited = new HashSet<>();
        String current = name;
        while (true) {
            if (visited.contains(current)) break;   // cycle guard
            visited.add(current);
            IdlType underlying = typedefMap.get(current);
            if (underlying == null) break;
            if (underlying instanceof IdlType.Scoped s) {
                current = s.qualifiedName();
            } else {
                // Non-scoped underlying type (primitive, sequence, etc.) — stop
                return underlying;
            }
        }
        return new IdlType.Scoped(current);
    }

    // -----------------------------------------------------------------------
    // Type substitution — deep-clone template body replacing formal params
    // -----------------------------------------------------------------------

    private List<IdlDefinition> substituteDefinitions(List<IdlDefinition> defs,
                                                       Map<String, IdlType> sub) {
        List<IdlDefinition> result = new ArrayList<>();
        for (IdlDefinition def : defs) {
            result.add(substituteDefinition(def, sub));
        }
        return result;
    }

    private IdlDefinition substituteDefinition(IdlDefinition def,
                                                Map<String, IdlType> sub) {
        if (def instanceof InterfaceNode iface) {
            List<OperationNode> ops = new ArrayList<>();
            for (OperationNode op : iface.operations()) {
                ops.add(substituteOperation(op, sub));
            }
            return new InterfaceNode(
                    iface.name(),
                    iface.isAbstract(),
                    iface.isLocal(),
                    iface.inheritedInterfaces(),
                    ops);

        } else if (def instanceof StructNode struct) {
            List<FieldNode> fields = new ArrayList<>();
            for (FieldNode f : struct.members()) {
                fields.add(new FieldNode(substituteType(f.type(), sub), f.name()));
            }
            return new StructNode(struct.name(), struct.baseType(), fields);
        }
        // Other definitions (enums, consts, nested modules) are returned as-is
        return def;
    }

    private OperationNode substituteOperation(OperationNode op,
                                              Map<String, IdlType> sub) {
        IdlType retType = substituteType(op.returnType(), sub);
        List<ParameterNode> params = new ArrayList<>();
        for (ParameterNode p : op.parameters()) {
            params.add(new ParameterNode(p.direction(), substituteType(p.type(), sub), p.name()));
        }
        return new OperationNode(op.name(), retType, params, op.isOneway());
    }

    /**
     * Replaces formal parameter names (e.g. {@code DATATYPE_TYPE}) with their
     * resolved concrete types when they appear as a {@link IdlType.Scoped}
     * reference.
     */
    private IdlType substituteType(IdlType t, Map<String, IdlType> sub) {
        if (t instanceof IdlType.Scoped s) {
            String name = s.qualifiedName();
            // Check direct name and name without leading ::
            IdlType replacement = sub.get(name);
            if (replacement == null && name.startsWith("::")) {
                replacement = sub.get(name.substring(2));
            }
            return replacement != null ? replacement : t;
        } else if (t instanceof IdlType.Sequence seq) {
            return new IdlType.Sequence(substituteType(seq.elementType(), sub), seq.bound());
        } else if (t instanceof IdlType.Array arr) {
            return new IdlType.Array(substituteType(arr.elementType(), sub), arr.dimensions());
        }
        return t;   // Primitive, Void, Str, WideStr — no substitution
    }
}
