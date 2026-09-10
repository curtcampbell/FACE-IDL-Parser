package com.warhex.er.generator.codegen.context;

import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.binding.TemplateInstantiator;
import com.warhex.er.generator.binding.generic.LanguageDescriptor;
import com.warhex.er.generator.binding.generic.TypeResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import com.warhex.er.generator.parser.IdlFileUnit;
import com.warhex.er.generator.parser.IdlParseResult;
import com.warhex.er.generator.reader.dto.ConnectionData;
import com.warhex.er.generator.reader.dto.IntegrationContextData;
import com.warhex.er.generator.reader.dto.UoPData;
import com.warhex.er.generator.reader.dto.TssTypeData;
import com.warhex.er.generator.reader.dto.UoPModelData;
import org.apache.velocity.VelocityContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Assembles Apache Velocity contexts for the code generator pipeline.
 *
 * <h2>Base context (always present)</h2>
 * <pre>
 *   $spec          — {@link IdlSpecification}  (merged; all included files pre-merged)
 *   $instantiator  — {@link TemplateInstantiator}  (call .instantiate(inst) from templates)
 *   $model         — {@link UoPModelData}  or {@code null} if --face-file not provided
 *   $uops          — {@code List<UoPData>}  (empty if model absent)
 *   $types         — {@link TypeResolver}  or {@code null} if language_dir not set
 * </pre>
 *
 * <h2>Scope-enriched context</h2>
 * The pipeline calls the {@code withXxx()} methods below to add per-scope
 * variables before each template render:
 * <pre>
 *   MODULE        → $module, $namespaces
 *   STRUCT        → $struct, $module, $namespaces
 *   INTERFACE     → $iface,  $module, $namespaces
 *   TEMPLATE_INST → $inst,   $resolved, $module, $namespaces
 *   UOP           → $uop
 *   CONNECTION    → $conn,   $uop
 * </pre>
 *
 * <p>Each {@code withXxx()} method returns a <em>new</em> {@link VelocityContext}
 * that chains the base context as its parent, so base variables remain
 * accessible without copying.
 */
public final class ContextAssembler {

    private static final Logger LOG =
            Logger.getLogger(ContextAssembler.class.getName());

    private final IdlSpecification    spec;
    private final IdlParseResult      parseResult;
    private final TemplateInstantiator instantiator;
    private final UoPModelData        model;         // nullable
    private final TypeResolver        types;         // nullable

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private ContextAssembler(IdlSpecification spec,
                             IdlParseResult parseResult,
                             TemplateInstantiator instantiator,
                             UoPModelData model,
                             TypeResolver types) {
        this.spec         = spec;
        this.parseResult  = parseResult;
        this.instantiator = instantiator;
        this.model        = model;
        this.types        = types;
    }

    /**
     * Builds a {@link ContextAssembler} from the parsed IDL result and an
     * optional UoP model.
     *
     * @param parseResult   IDL parse result (always required)
     * @param model         UoP model from a {@code .face} file; may be {@code null}
     * @param languageDir   path to a {@code language.yaml} directory (binder-style);
     *                      may be {@code null} — {@code $types} is omitted when absent
     * @return assembled context builder
     * @throws IOException if {@code languageDir} is provided but {@code language.yaml}
     *                     cannot be read
     */
    public static ContextAssembler build(IdlParseResult parseResult,
                                         UoPModelData model,
                                         Path languageDir) throws IOException {
        IdlSpecification spec = parseResult.mergedSpec();
        TemplateInstantiator instantiator = new TemplateInstantiator(spec);

        TypeResolver types = null;
        if (languageDir != null) {
            LanguageDescriptor desc = loadDescriptor(languageDir);
            types = new TypeResolver(desc, instantiator.typedefMap());
            LOG.info("Language descriptor loaded from: " + languageDir);
        }

        if (model != null) {
            LOG.info("UoP model loaded: " + model.getModelName()
                    + " (" + model.getUoPs().size() + " UoPs)");
        }

        return new ContextAssembler(spec, parseResult, instantiator, model, types);
    }

    // -----------------------------------------------------------------------
    // Base context
    // -----------------------------------------------------------------------

    /**
     * Creates the base Velocity context populated with top-level variables.
     * Call once per pipeline run; use {@code withXxx()} methods to derive
     * per-scope child contexts.
     *
     * <h2>Context assembly order (lowest → highest priority)</h2>
     * <ol>
     *   <li>IDL-derived variables ({@link IdlDerivedVariables})</li>
     *   <li>Core spec/model references ({@code $spec}, {@code $model}, etc.)</li>
     * </ol>
     * The pipeline adds codegen-defaults.yaml values and manifest variables on
     * top of this context after it is returned, so those always win over
     * the derived values placed here.
     *
     * @return base context; never {@code null}
     */
    public VelocityContext baseContext() {
        VelocityContext ctx = new VelocityContext();

        // ── 1. IDL-derived variables (lowest priority; overridden by manifest) ─
        Map<String, String> derived = IdlDerivedVariables.derive(spec);
        derived.forEach(ctx::put);

        // ── 2. Core spec/model references ───────────────────────────────────────
        ctx.put("spec",         spec);
        ctx.put("instantiator", instantiator);
        ctx.put("model",        model);   // may be null — VelocityContext accepts null values
        ctx.put("uops",         model != null ? model.getUoPs() : List.of());
        ctx.put("integrationContexts",
                model != null ? model.getIntegrationContexts() : List.of());
        if (types != null) {
            ctx.put("types", types);
        }

        // ── 3. Convenience collections (flattened views of the spec) ─────────────
        // These are pre-built lists so templates can iterate without walking
        // the tree themselves.  They are populated once and shared read-only.
        ctx.put("structs",       collectNodes(spec.definitions(), StructNode.class));
        ctx.put("enums",         collectNodes(spec.definitions(), EnumNode.class));
        ctx.put("interfaces",    collectNodes(spec.definitions(), InterfaceNode.class));
        ctx.put("templateInsts", collectNodes(spec.definitions(), TemplateInstNode.class));
        ctx.put("modules",       topLevelModules());
        // $files (IdlFileUnit list) is available from parseResult.fileUnits()
        ctx.put("files",         parseResult.fileUnits());

        return ctx;
    }

    /**
     * Walks {@code defs} recursively and collects all instances of {@code type},
     * descending into every {@link ModuleNode} encountered.
     *
     * @param <T>  the node type to collect
     * @param defs root definition list
     * @param type class token for the desired node type
     * @return flattened list in declaration order; never {@code null}
     */
    private static <T extends IdlDefinition> List<T> collectNodes(
            List<IdlDefinition> defs, Class<T> type) {
        List<T> result = new ArrayList<>();
        collectNodes(defs, type, result);
        return List.copyOf(result);
    }

    private static <T extends IdlDefinition> void collectNodes(
            List<IdlDefinition> defs, Class<T> type, List<T> out) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                collectNodes(m.definitions(), type, out);
            } else if (type.isInstance(def)) {
                out.add(type.cast(def));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scope-enriched child contexts
    // -----------------------------------------------------------------------

    /**
     * Returns a child context for the {@code MODULE} scope.
     *
     * @param base       context returned by {@link #baseContext()}
     * @param module     the current module node
     * @param namespaces enclosing namespace segments (empty for top-level)
     * @return child context with {@code $module} and {@code $namespaces}
     */
    public VelocityContext withModule(VelocityContext base,
                                      ModuleNode module,
                                      List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code STRUCT} scope.
     *
     * @param base       base context
     * @param struct     the current struct node
     * @param module     nearest enclosing module (may be {@code null} for top-level structs)
     * @param namespaces full namespace path to the struct
     * @return child context with {@code $struct}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withStruct(VelocityContext base,
                                      StructNode struct,
                                      ModuleNode module,
                                      List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("struct",     struct);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code INTERFACE} scope.
     *
     * @param base       base context
     * @param iface      the current interface node
     * @param module     nearest enclosing module
     * @param namespaces full namespace path to the interface
     * @return child context with {@code $iface}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withInterface(VelocityContext base,
                                          InterfaceNode iface,
                                          ModuleNode module,
                                          List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("iface",      iface);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code TEMPLATE_INST} scope.
     *
     * <p>Calls {@link TemplateInstantiator#instantiate(TemplateInstNode)} and
     * places the result in {@code $resolved}.  Returns {@link Optional#empty()}
     * when the template cannot be resolved (e.g. the referenced template module
     * is not in the IDL search path), so the pipeline can skip unresolvable
     * entries gracefully.
     *
     * @param base       base context
     * @param inst       the template instantiation node
     * @param module     nearest enclosing module
     * @param namespaces namespace path to the instantiation
     * @return child context wrapped in {@link Optional}, or empty if unresolvable
     */
    public Optional<VelocityContext> withTemplateInst(VelocityContext base,
                                                       TemplateInstNode inst,
                                                       ModuleNode module,
                                                       List<String> namespaces) {
        return instantiator.instantiate(inst).map(resolved -> {
            VelocityContext ctx = child(base);
            ctx.put("inst",       inst);
            ctx.put("resolved",   resolved);
            ctx.put("module",     module);
            ctx.put("namespaces", namespaces);
            return ctx;
        });
    }

    /**
     * Returns a child context for the {@code UOP} scope.
     *
     * @param base base context
     * @param uop  the current UoP
     * @return child context with {@code $uop}
     */
    public VelocityContext withUop(VelocityContext base, UoPData uop) {
        VelocityContext ctx = child(base);
        ctx.put("uop", uop);
        return ctx;
    }

    /**
     * Returns a child context for the {@code CONNECTION} scope.
     *
     * @param base base context
     * @param uop  parent UoP
     * @param conn the current connection
     * @return child context with {@code $conn} and {@code $uop}
     */
    public VelocityContext withConnection(VelocityContext base,
                                           UoPData uop,
                                           ConnectionData conn) {
        VelocityContext ctx = child(base);
        ctx.put("uop",  uop);
        ctx.put("conn", conn);
        return ctx;
    }


    /**
     * Returns a child context for the {@code UOP_INTEGRATION_CONTEXT} scope.
     *
     * <p>Both {@code $uop} and {@code $integrationContext} are placed in the
     * context.  {@code $uop} is the same {@link UoPData} object that would be
     * provided by the {@code UOP} scope; {@code $integrationContext} carries
     * the scoped connection list, IC name, and transport channel name for this
     * particular (UoP, IntegrationContext) pair.
     *
     * @param base               base context
     * @param uop                the UoP type realized by the IC's UoPInstance
     * @param integrationContext the IntegrationContext DTO
     * @return child context with {@code $uop} and {@code $integrationContext}
     */
    public VelocityContext withIntegrationContext(VelocityContext base,
                                                   UoPData uop,
                                                   IntegrationContextData integrationContext) {
        VelocityContext ctx = child(base);
        ctx.put("uop",                uop);
        ctx.put("integrationContext", integrationContext);
        return ctx;
    }

    /**
     * Returns a child context for the {@code ENUM} scope (Phase 4).
     *
     * @param base       base context
     * @param enumNode   the current enum node
     * @param module     nearest enclosing module (may be {@code null})
     * @param namespaces full namespace path to the enum
     * @return child context with {@code $enum}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withEnum(VelocityContext base,
                                    EnumNode enumNode,
                                    ModuleNode module,
                                    List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("enum",       enumNode);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code TYPEDEF} scope (Phase 4).
     *
     * @param base       base context
     * @param typedef    the current typedef node
     * @param module     nearest enclosing module (may be {@code null})
     * @param namespaces full namespace path
     * @return child context with {@code $typedef}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withTypedef(VelocityContext base,
                                       TypedefNode typedef,
                                       ModuleNode module,
                                       List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("typedef",    typedef);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code UNION} scope (Phase 4).
     *
     * @param base       base context
     * @param union      the current union node
     * @param module     nearest enclosing module (may be {@code null})
     * @param namespaces full namespace path
     * @return child context with {@code $union}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withUnion(VelocityContext base,
                                     UnionNode union,
                                     ModuleNode module,
                                     List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("union",      union);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code CONST} scope (Phase 4).
     *
     * @param base       base context
     * @param constNode  the current const node
     * @param module     nearest enclosing module (may be {@code null})
     * @param namespaces full namespace path
     * @return child context with {@code $const}, {@code $module}, {@code $namespaces}
     */
    public VelocityContext withConst(VelocityContext base,
                                     ConstNode constNode,
                                     ModuleNode module,
                                     List<String> namespaces) {
        VelocityContext ctx = child(base);
        ctx.put("const",      constNode);
        ctx.put("module",     module);
        ctx.put("namespaces", namespaces);
        return ctx;
    }

    /**
     * Returns a child context for the {@code FILE} scope (Phase 8).
     *
     * <p>Provides {@code $file} ({@link IdlFileUnit}) so templates can access the
     * source-file path, stem name, and the subset of IDL definitions declared
     * directly in that file (excluding transitively included framework headers).
     *
     * @param base base context
     * @param file the current IDL file unit
     * @return child context with {@code $file}
     */
    public VelocityContext withFile(VelocityContext base, IdlFileUnit file) {
        VelocityContext ctx = child(base);
        ctx.put("file", file);
        return ctx;
    }

    // -----------------------------------------------------------------------
    // Spec walker — called by CodeGenPipeline to enumerate scope elements
    // -----------------------------------------------------------------------

    /**
     * Walks the spec and collects all {@link StructNode}s with their enclosing
     * module and namespace context.
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<StructNode>> allStructs() {
        List<ScopedElement<StructNode>> result = new ArrayList<>();
        walkForStructs(spec.definitions(), null, List.of(), result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link InterfaceNode}s.
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<InterfaceNode>> allInterfaces() {
        List<ScopedElement<InterfaceNode>> result = new ArrayList<>();
        walkForInterfaces(spec.definitions(), null, List.of(), result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link TemplateInstNode}s.
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<TemplateInstNode>> allTemplateInsts() {
        List<ScopedElement<TemplateInstNode>> result = new ArrayList<>();
        walkForTemplateInsts(spec.definitions(), null, List.of(), result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link EnumNode}s (Phase 4).
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<EnumNode>> allEnums() {
        List<ScopedElement<EnumNode>> result = new ArrayList<>();
        walkScoped(spec.definitions(), null, List.of(), EnumNode.class, result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link TypedefNode}s (Phase 4).
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<TypedefNode>> allTypedefs() {
        List<ScopedElement<TypedefNode>> result = new ArrayList<>();
        walkScoped(spec.definitions(), null, List.of(), TypedefNode.class, result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link UnionNode}s (Phase 4).
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<UnionNode>> allUnions() {
        List<ScopedElement<UnionNode>> result = new ArrayList<>();
        walkScoped(spec.definitions(), null, List.of(), UnionNode.class, result);
        return result;
    }

    /**
     * Walks the spec and collects all {@link ConstNode}s (Phase 4).
     *
     * @return ordered list of records; never {@code null}
     */
    public List<ScopedElement<ConstNode>> allConsts() {
        List<ScopedElement<ConstNode>> result = new ArrayList<>();
        walkScoped(spec.definitions(), null, List.of(), ConstNode.class, result);
        return result;
    }

    /**
     * Returns the top-level {@link ModuleNode}s from the spec (direct children only).
     *
     * @return list of top-level modules; never {@code null}
     */
    /**
     * Returns a sorted list of unique model-namespace tokens from
     * {@link UoPModelData#getPlatformTypes()}, e.g.
     * {@code ["CheckoutGateway_Templates", "CORE_Templates", ...]}.
     * Returns an empty list when no face model is loaded.
     */
    public List<String> allModelNamespaces() {
        if (model == null) return List.of();
        return model.getPlatformTypes().stream()
                .map(TssTypeData::getModelNamespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns a child context for the {@code MODEL_NAMESPACE} scope.
     *
     * <p>Adds:
     * <ul>
     *   <li>{@code $modelNamespace} — the simple namespace token (String)</li>
     *   <li>{@code $namespaceTypes} — {@code List<TssTypeData>} of all types
     *       whose {@code modelNamespace} equals the given value</li>
     * </ul>
     *
     * @param base            parent context
     * @param modelNamespace  simple namespace token, e.g. "CheckoutGateway_Templates"
     * @return child context with the variables above added
     */
    public VelocityContext withModelNamespace(VelocityContext base, String modelNamespace) {
        VelocityContext ctx = child(base);
        ctx.put("modelNamespace", modelNamespace);
        List<TssTypeData> types = (model == null) ? List.of()
                : model.getPlatformTypes().stream()
                        .filter(t -> modelNamespace.equals(t.getModelNamespace()))
                        .toList();
        ctx.put("namespaceTypes", types);
        return ctx;
    }

    public List<ModuleNode> topLevelModules() {
        List<ModuleNode> result = new ArrayList<>();
        for (IdlDefinition def : spec.definitions()) {
            if (def instanceof ModuleNode m) result.add(m);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Carrier record for scoped elements
    // -----------------------------------------------------------------------

    /**
     * Pairs an AST node with the context it was found in.
     *
     * @param <T> the node type ({@link StructNode}, {@link InterfaceNode}, etc.)
     */
    public record ScopedElement<T>(
            T node,
            ModuleNode enclosingModule,
            List<String> namespaces) {}

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Creates a new VelocityContext that chains {@code base} as its parent. */
    private static VelocityContext child(VelocityContext base) {
        return new VelocityContext(base);
    }

    private void walkForStructs(List<IdlDefinition> defs,
                                 ModuleNode enclosing,
                                 List<String> ns,
                                 List<ScopedElement<StructNode>> out) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                List<String> next = append(ns, m.name());
                walkForStructs(m.definitions(), m, next, out);
            } else if (def instanceof StructNode s) {
                out.add(new ScopedElement<>(s, enclosing, ns));
            }
        }
    }

    private void walkForInterfaces(List<IdlDefinition> defs,
                                    ModuleNode enclosing,
                                    List<String> ns,
                                    List<ScopedElement<InterfaceNode>> out) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                List<String> next = append(ns, m.name());
                walkForInterfaces(m.definitions(), m, next, out);
            } else if (def instanceof InterfaceNode i) {
                out.add(new ScopedElement<>(i, enclosing, ns));
            }
        }
    }

    private void walkForTemplateInsts(List<IdlDefinition> defs,
                                       ModuleNode enclosing,
                                       List<String> ns,
                                       List<ScopedElement<TemplateInstNode>> out) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                List<String> next = append(ns, m.name());
                walkForTemplateInsts(m.definitions(), m, next, out);
            } else if (def instanceof TemplateInstNode t) {
                out.add(new ScopedElement<>(t, enclosing, ns));
            }
        }
    }

    /**
     * Returns {@code true} if a {@link UoPModelData} was supplied at construction.
     * Used by the pipeline to validate UOP / CONNECTION scope entries before iterating.
     */
    public boolean hasModel() {
        return model != null;
    }

    /**
     * Exposes the merged {@link IdlSpecification} for downstream consumers
     * such as {@link com.warhex.er.generator.codegen.helpers.HelperRegistry}.
     */
    public IdlSpecification spec() { return spec; }

    /**
     * Exposes the {@link TemplateInstantiator} for downstream consumers
     * such as {@link com.warhex.er.generator.codegen.helpers.HelperRegistry}.
     */
    public TemplateInstantiator instantiator() { return instantiator; }

    /**
     * Exposes the full {@link IdlParseResult} for Phase 8 (FILE scope).
     * Consumers should prefer the per-file {@link #fileUnits()} list.
     */
    public IdlParseResult parseResult() { return parseResult; }

    /**
     * Convenience accessor for the per-source-file definition groups.
     * Used by the pipeline when iterating the {@code FILE} scope.
     */
    public List<IdlFileUnit> fileUnits() { return parseResult.fileUnits(); }

    /**
     * Loads a {@link LanguageDescriptor} from a {@code language.yaml} file in
     * {@code langDir}.  Mirrors the private {@code deserialise()} logic in
     * {@link com.warhex.er.generator.binding.generic.LanguageDescriptorLoader}.
     */
    private static LanguageDescriptor loadDescriptor(Path langDir) throws IOException {
        Path descriptorFile = langDir.resolve("language.yaml");
        if (!Files.isRegularFile(descriptorFile)) {
            throw new IOException("language.yaml not found in language_dir: " + langDir);
        }
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new Constructor(LanguageDescriptor.class, opts));
        try (InputStream in = Files.newInputStream(descriptorFile)) {
            return yaml.loadAs(in, LanguageDescriptor.class);
        }
    }

    /**
     * Generic walker that collects all {@link IdlDefinition} nodes of a given
     * type from {@code defs} into {@code out}, preserving enclosing-module and
     * namespace context (Phase 4).  Used by {@code allEnums()}, {@code allTypedefs()},
     * {@code allUnions()}, and {@code allConsts()}.
     */
    private <T extends IdlDefinition> void walkScoped(List<IdlDefinition> defs,
                                                       ModuleNode enclosing,
                                                       List<String> ns,
                                                       Class<T> type,
                                                       List<ScopedElement<T>> out) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                walkScoped(m.definitions(), m, append(ns, m.name()), type, out);
            } else if (type.isInstance(def)) {
                out.add(new ScopedElement<>(type.cast(def), enclosing, ns));
            }
        }
    }

    private static <T> List<T> append(List<T> list, T element) {
        List<T> next = new ArrayList<>(list);
        next.add(element);
        return List.copyOf(next);
    }
}
