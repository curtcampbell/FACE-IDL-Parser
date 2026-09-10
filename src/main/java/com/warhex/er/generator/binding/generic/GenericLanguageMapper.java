package com.warhex.er.generator.binding.generic;

import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.binding.TemplateInstantiator;
import com.warhex.er.generator.parser.IdlFileUnit;
import com.warhex.er.generator.parser.IdlParseResult;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generic language mapper driven entirely by a {@link LanguageDescriptor} and
 * Velocity templates.  Implements all four iteration strategies defined in the
 * descriptor schema (§2.4):
 *
 * <ul>
 *   <li>{@code per_construct} — one output file per IDL leaf construct</li>
 *   <li>{@code per_idl_file} — one output file per IDL source file</li>
 *   <li>{@code per_construct_multi} — one construct → multiple output files</li>
 * </ul>
 *
 * <p>No language-specific logic lives here.  All behaviour is data-driven from
 * the descriptor.
 *
 * <h2>Velocity context provided to every template</h2>
 * <table>
 *   <tr><td>{@code $types}</td><td>{@link TypeResolver} — primary type helper</td></tr>
 *   <tr><td>{@code $spec}</td><td>{@link IdlSpecification} — full merged spec</td></tr>
 *   <tr><td>{@code $moduleStack}</td><td>{@code List<String>} — enclosing module segments</td></tr>
 *   <tr><td>{@code $reservedWords}</td><td>{@code Set<String>} — from descriptor</td></tr>
 *   <tr><td>{@code $langName}</td><td>{@code String} — e.g. {@code "Java"}</td></tr>
 * </table>
 *
 * <p>Per-construct templates additionally receive:
 * <ul>
 *   <li>{@code $construct} — the IDL node</li>
 *   <li>{@code $instantiation} — {@link TemplateInstantiator.InstantiationResult} for
 *       {@code template_inst}</li>
 * </ul>
 *
 * <p>Per-IDL-file templates additionally receive:
 * <ul>
 *   <li>{@code $items} — {@code List<RenderItem.*>}</li>
 *   <li>{@code $guardBase} — uppercase path-without-ext (if {@code compute_guard: true})</li>
 *   <li>{@code $includes} — {@code List<String>}</li>
 * </ul>
 */
public class GenericLanguageMapper implements LanguageMapper {

    private static final Logger LOG =
            Logger.getLogger(GenericLanguageMapper.class.getName());

    private final LanguageDescriptor descriptor;
    private final VelocityEngine     velocity;

    /**
     * Lazily-instantiated legacy type helper (e.g. {@code JavaTypeHelper}).
     * Non-null only when {@link LanguageDescriptor#legacy_helper_class} is set.
     * Placed in the Velocity context as {@code $<legacy_helper_key>} so that
     * existing templates can call {@code $java.type(...)}, {@code $py.type(...)},
     * etc. without change during migration Phases 2–5.
     */
    private final Object legacyHelper;

    /**
     * @param descriptor  fully-populated language descriptor (never {@code null})
     * @param velocity    pre-initialised Velocity engine pointed at this language's
     *                    template directory (never {@code null})
     */
    public GenericLanguageMapper(LanguageDescriptor descriptor, VelocityEngine velocity) {
        this.descriptor  = descriptor;
        this.velocity    = velocity;
        this.legacyHelper = instantiateLegacyHelper(descriptor);
    }

    // =========================================================================
    // Legacy helper lifecycle
    // =========================================================================

    private static Object instantiateLegacyHelper(LanguageDescriptor d) {
        if (d.legacy_helper_class == null || d.legacy_helper_class.isBlank()) return null;
        try {
            return Class.forName(d.legacy_helper_class)
                        .getDeclaredConstructor()
                        .newInstance();
        } catch (Exception e) {
            Logger.getLogger(GenericLanguageMapper.class.getName())
                  .warning("Cannot instantiate legacy helper '" + d.legacy_helper_class
                          + "' for " + d.name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Puts the legacy helper in {@code ctx} under {@link LanguageDescriptor#legacy_helper_key}
     * and, if the helper exposes a {@code packageName(List)} method, also computes and
     * stores {@code $packageName} from {@code moduleStack}.
     *
     * <p>When {@link LanguageDescriptor#package_separator} is set, the engine computes
     * {@code $packageName} and {@code $namespaceName} directly — no legacy helper needed.
     */
    private void putLegacyHelper(VelocityContext ctx, List<String> moduleStack) {
        // Descriptor-driven package/namespace name computation (preferred over reflection).
        putPackageName(ctx, moduleStack);

        if (legacyHelper == null || descriptor.legacy_helper_key == null) return;
        ctx.put(descriptor.legacy_helper_key, legacyHelper);

        // Reflective fallback: only used when package_separator is not configured.
        if (descriptor.package_separator == null) {
            try {
                java.lang.reflect.Method m =
                        legacyHelper.getClass().getMethod("packageName", List.class);
                Object pkg = m.invoke(legacyHelper, moduleStack);
                if (pkg instanceof String s) ctx.put("packageName", s);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                LOG.fine(descriptor.name + ": packageName reflection failed: " + e.getMessage());
            }
            try {
                java.lang.reflect.Method m =
                        legacyHelper.getClass().getMethod("namespaceName", List.class);
                Object ns = m.invoke(legacyHelper, moduleStack);
                if (ns instanceof String s) ctx.put("namespaceName", s);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                LOG.fine(descriptor.name + ": namespaceName reflection failed: " + e.getMessage());
            }
        }
    }

    /**
     * Computes {@code $packageName} / {@code $namespaceName} from the module stack
     * using {@link LanguageDescriptor#package_separator} and
     * {@link LanguageDescriptor#package_case}.  No-op when {@code package_separator}
     * is {@code null}.
     */
    private void putPackageName(VelocityContext ctx, List<String> moduleStack) {
        if (descriptor.package_separator == null) return;
        boolean lower = "lower".equalsIgnoreCase(descriptor.package_case);
        String joined = moduleStack.stream()
                .map(seg -> lower ? seg.toLowerCase() : seg)
                .collect(java.util.stream.Collectors.joining(descriptor.package_separator));
        ctx.put("packageName",   joined);
        ctx.put("namespaceName", joined);
    }

    // =========================================================================
    // LanguageMapper contract
    // =========================================================================

    @Override
    public String languageName() { return descriptor.name; }

    @Override
    public String outputSubdirectory() { return descriptor.output_subdirectory; }

    @Override
    public void map(IdlParseResult result, Path outputDir) throws Exception {
        Path langRoot = outputDir.resolve(descriptor.output_subdirectory);
        Files.createDirectories(langRoot);
        internalMap(result, langRoot);
    }

    @Override
    public void mapDirect(IdlParseResult result, Path langRoot) throws Exception {
        Files.createDirectories(langRoot);
        internalMap(result, langRoot);
    }

    private void internalMap(IdlParseResult result, Path langRoot) throws Exception {
        LOG.info(descriptor.name + " output root: " + langRoot);

        // Build type resolver with typedef chain support
        TemplateInstantiator instantiator = new TemplateInstantiator(result.mergedSpec());
        TypeResolver types = new TypeResolver(descriptor, instantiator.typedefMap());

        IdlSpecification spec = result.mergedSpec();
        Set<String> reservedWords = descriptor.reserved_words != null
                ? new HashSet<>(descriptor.reserved_words) : Set.of();

        // Base context shared across all templates in this invocation
        VelocityContext baseCtx = new VelocityContext();
        baseCtx.put("types",        types);
        baseCtx.put("spec",         spec);
        baseCtx.put("reservedWords", reservedWords);
        baseCtx.put("langName",     descriptor.name);

        // 1. Emit static files (before AST walk)
        emitStaticFiles(langRoot, types, spec, reservedWords);

        // 2. Dispatch on iteration strategy
        // Per-directory accumulator for post_files
        LinkedHashMap<Path, List<String>> dirIndex = new LinkedHashMap<>();

        String strategy = descriptor.iteration != null ? descriptor.iteration.strategy : "per_construct";
        List<IdlFileUnit> units = result.fileUnits();

        if ("per_idl_file".equals(strategy)) {
            if (units.isEmpty()
                    && descriptor.iteration != null
                    && descriptor.iteration.fallback_to_per_construct) {
                LOG.info(descriptor.name + ": no file units — falling back to per_construct");
                walkPerConstruct(spec.definitions(), List.of(), langRoot,
                        types, spec, reservedWords, dirIndex, instantiator, false);
            } else {
                for (IdlFileUnit unit : units) {
                    renderFile(unit, langRoot, types, spec, reservedWords, instantiator);
                }
            }
        } else {
            boolean multi = "per_construct_multi".equals(strategy);
            if (!units.isEmpty()) {
                // Walk per file unit so that framework include types (Common.idl,
                // TSS/Common.idl, etc.) are never rendered — each file unit contains
                // only the constructs *directly* declared in that IDL source file.
                // The merged-spec instantiator is still used for template resolution.
                for (IdlFileUnit unit : units) {
                    walkPerConstruct(unit.definitions(), List.of(), langRoot,
                            types, spec, reservedWords, dirIndex, instantiator, multi);
                }
            } else {
                // No file units (YAML path or single-IDL path) — walk merged spec.
                walkPerConstruct(spec.definitions(), List.of(), langRoot,
                        types, spec, reservedWords, dirIndex, instantiator, multi);
            }
        }

        // 3. Emit post files (after AST walk)
        emitPostFiles(langRoot, dirIndex, types, spec, reservedWords);
    }

    // =========================================================================
    // per_construct / per_construct_multi walk
    // =========================================================================

    private void walkPerConstruct(List<IdlDefinition> defs,
                                  List<String> moduleStack,
                                  Path langRoot,
                                  TypeResolver types,
                                  IdlSpecification spec,
                                  Set<String> reservedWords,
                                  LinkedHashMap<Path, List<String>> dirIndex,
                                  TemplateInstantiator instantiator,
                                  boolean multi) throws Exception {
        for (IdlDefinition def : defs) {

            if (def instanceof ModuleNode m) {
                List<String> newStack = new ArrayList<>(moduleStack);
                newStack.add(m.name());
                walkPerConstruct(m.definitions(), List.copyOf(newStack), langRoot,
                        types, spec, reservedWords, dirIndex, instantiator, multi);

            } else if (def instanceof StructNode
                    || def instanceof EnumNode
                    || def instanceof InterfaceNode) {
                renderConstruct(def, moduleStack, langRoot, types, spec, reservedWords,
                        dirIndex, null);

            } else if (def instanceof TemplateInstNode inst) {
                Optional<TemplateInstantiator.InstantiationResult> resOpt =
                        instantiator.instantiate(inst);
                if (resOpt.isEmpty()) {
                    LOG.warning(descriptor.name + ": could not instantiate template "
                            + inst.templateName() + " alias=" + inst.alias());
                    continue;
                }
                renderConstruct(inst, moduleStack, langRoot, types, spec, reservedWords,
                        dirIndex, resOpt.get());

            } else if (def instanceof TemplateModuleNode) {
                LOG.fine("Skipping TemplateModuleNode: " + def.name());
            } else if (def instanceof UnionNode
                    || def instanceof TypedefNode
                    || def instanceof ConstNode) {
                renderConstruct(def, moduleStack, langRoot, types, spec, reservedWords,
                        dirIndex, null);
            }
        }
    }

    private void renderConstruct(IdlDefinition def,
                                 List<String> moduleStack,
                                 Path langRoot,
                                 TypeResolver types,
                                 IdlSpecification spec,
                                 Set<String> reservedWords,
                                 LinkedHashMap<Path, List<String>> dirIndex,
                                 TemplateInstantiator.InstantiationResult instantiation)
            throws Exception {

        String kind = constructKind(def);
        List<LanguageDescriptor.TemplateEntry> entries = descriptor.getTemplateEntries(kind);
        if (entries.isEmpty()) {
            LOG.fine(descriptor.name + ": no template for kind '" + kind + "' — skipping "
                    + def.name());
            return;
        }

        for (LanguageDescriptor.TemplateEntry entry : entries) {
            String constructName = constructOutputName(def);
            Path outPath = resolveConstructOutputPath(entry, moduleStack, constructName, langRoot);
            Files.createDirectories(outPath.getParent());

            VelocityContext ctx = new VelocityContext();
            ctx.put("types",         types);
            ctx.put("spec",          spec);
            ctx.put("moduleStack",   moduleStack);
            ctx.put("reservedWords", reservedWords);
            ctx.put("langName",      descriptor.name);
            ctx.put("construct",     def);
            if (instantiation != null) {
                ctx.put("instantiation", instantiation);
            }
            // Also put the construct under its legacy key for backward compatibility
            // with existing templates during migration (see Phase 2–5 notes).
            putLegacyConstructKeys(ctx, def, moduleStack, instantiation);
            // Put the legacy type helper ($java, $py, etc.) and $packageName.
            putLegacyHelper(ctx, moduleStack);
            // Compute derived context keys that the bespoke mappers pre-computed
            // (e.g. $localImports for Python structs, $resolvedImports for template_inst).
            putComputedLegacyKeys(ctx, def, instantiation);

            render(entry.template, ctx, outPath);
            LOG.info("  → " + outPath);

            // Track for post_files per_output_directory trigger
            dirIndex.computeIfAbsent(outPath.getParent(), k -> new ArrayList<>())
                    .add(constructOutputName(def));
        }
    }

    // =========================================================================
    // per_idl_file rendering
    // =========================================================================

    private void renderFile(IdlFileUnit unit,
                            Path langRoot,
                            TypeResolver types,
                            IdlSpecification spec,
                            Set<String> reservedWords,
                            TemplateInstantiator instantiator) throws Exception {

        List<LanguageDescriptor.TemplateEntry> entries = descriptor.getTemplateEntries("file");
        if (entries.isEmpty()) {
            LOG.warning(descriptor.name + ": per_idl_file strategy but no 'file' template entry");
            return;
        }
        LanguageDescriptor.TemplateEntry entry = entries.get(0);

        // Compute output path: either from namespace chain or IDL file path
        Path relOut;
        if (descriptor.iteration != null
                && descriptor.iteration.path_from_namespace
                && descriptor.iteration.path != null) {
            List<String> moduleStack = extractModuleStack(unit.definitions());
            if (moduleStack.isEmpty()) {
                // Umbrella include files (no direct definitions) have no module stack
                // and therefore no deterministic output path. Skip them.
                LOG.fine(descriptor.name + ": skipping umbrella file unit (no direct definitions): "
                        + unit.stemName());
                return;
            }
            relOut = Path.of(TypeResolver.expandPathPattern(
                    descriptor.iteration.path, moduleStack, unit.stemName()));
        } else {
            relOut = applyExtensionReplace(unit.relativePath());
        }
        Path outPath = langRoot.resolve(relOut);
        Files.createDirectories(outPath.getParent());

        // Compute guardBase if requested
        String guardBase = null;
        if (descriptor.iteration != null && descriptor.iteration.compute_guard) {
            String pathStr = relOut.toString().replace('\\', '/');
            guardBase = pathStr.substring(0, pathStr.lastIndexOf('.'))
                    .replace('/', '_').replace('-', '_').toUpperCase();
        }

        // Build the flat render-item list and collect includes
        LinkedHashSet<String> includeSet = new LinkedHashSet<>();
        if (descriptor.include_computation != null
                && descriptor.include_computation.always_include != null) {
            includeSet.addAll(descriptor.include_computation.always_include);
        }

        List<Object> items = new ArrayList<>();
        buildRenderItems(unit.definitions(), items, includeSet, instantiator);

        VelocityContext ctx = new VelocityContext();
        ctx.put("types",         types);
        ctx.put("spec",          spec);
        ctx.put("moduleStack",   List.of()); // file-level; individual items carry namespace info
        ctx.put("reservedWords", reservedWords);
        ctx.put("langName",      descriptor.name);
        ctx.put("items",         items);
        ctx.put("includes",      new ArrayList<>(includeSet));
        if (guardBase != null) {
            ctx.put("guardBase", guardBase);
            // Also put the full guard token for backward compatibility with C++ templates
            ctx.put("guard", guardBase + "_HPP");
        }
        // Legacy: put $cpp as the type helper for the existing C++ file template.
        // This will be revisited in Phase 5 when the C++ descriptor and macros.vm
        // are written — for now the engine exposes both $types and $cpp.
        putLegacyFileContextKeys(ctx, types);
        putLegacyHelper(ctx, List.of());

        render(entry.template, ctx, outPath);
        LOG.info("  → " + outPath);
    }

    /**
     * Recursively walks definitions from one IDL file unit, building the flat
     * render-item list and accumulating include paths.
     */
    private void buildRenderItems(List<IdlDefinition> defs,
                                  List<Object> items,
                                  Set<String> includes,
                                  TemplateInstantiator instantiator) throws Exception {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                items.add(new RenderItem.NsOpen(m.name()));
                buildRenderItems(m.definitions(), items, includes, instantiator);
                items.add(new RenderItem.NsClose(m.name()));

            } else if (def instanceof StructNode s) {
                collectIncludesForFields(s.members(), includes);
                items.add(new RenderItem.StructItem(s));

            } else if (def instanceof EnumNode e) {
                items.add(new RenderItem.EnumItem(e));

            } else if (def instanceof InterfaceNode i) {
                items.add(new RenderItem.InterfaceItem(i,
                        Collections.emptySet(), Collections.emptySet()));

            } else if (def instanceof TemplateInstNode inst) {
                Optional<TemplateInstantiator.InstantiationResult> resOpt =
                        instantiator.instantiate(inst);
                if (resOpt.isEmpty()) {
                    LOG.warning(descriptor.name + ": could not instantiate template "
                            + inst.templateName() + " alias=" + inst.alias());
                    continue;
                }
                TemplateInstantiator.InstantiationResult res = resOpt.get();

                // Always-includes for template_inst
                if (descriptor.include_computation != null
                        && descriptor.include_computation.template_inst_always_include != null) {
                    includes.addAll(descriptor.include_computation.template_inst_always_include);
                }
                // Includes for each resolved actual type (uses template_inst_skip_prefixes when set)
                if (descriptor.include_computation != null) {
                    for (IdlType actual : res.resolvedActuals) {
                        if (actual instanceof IdlType.Scoped s && !isTemplateInstSkipped(s.qualifiedName())) {
                            includes.add(includePathForScoped(s.qualifiedName()));
                        }
                    }
                }

                items.add(new RenderItem.NsOpen(inst.alias()));
                for (IdlDefinition subDef : res.definitions) {
                    if (subDef instanceof InterfaceNode iface) {
                        items.add(new RenderItem.InterfaceItem(
                                iface, res.localInterfaceNames, res.interfaceKindActuals));
                    } else if (subDef instanceof StructNode s) {
                        items.add(new RenderItem.StructItem(s));
                    } else if (subDef instanceof EnumNode e) {
                        items.add(new RenderItem.EnumItem(e));
                    }
                }
                items.add(new RenderItem.NsClose(inst.alias()));

            } else if (def instanceof TemplateModuleNode) {
                LOG.fine("Skipping TemplateModuleNode: " + def.name());
            } else if (def instanceof UnionNode || def instanceof TypedefNode
                    || def instanceof ConstNode) {
                LOG.fine("Skipping " + def.getClass().getSimpleName() + ": " + def.name());
            }
        }
    }

    // =========================================================================
    // Static files
    // =========================================================================

    private void emitStaticFiles(Path langRoot,
                                 TypeResolver types,
                                 IdlSpecification spec,
                                 Set<String> reservedWords) throws Exception {
        if (descriptor.static_files == null) return;
        for (LanguageDescriptor.StaticFileEntry sf : descriptor.static_files) {
            Path outPath = langRoot.resolve(sf.path);
            if (sf.skip_if_exists && Files.exists(outPath)) {
                LOG.fine(descriptor.name + ": static file exists, skipping: " + outPath);
                continue;
            }
            Files.createDirectories(outPath.getParent());

            VelocityContext ctx = new VelocityContext();
            ctx.put("types",         types);
            ctx.put("spec",          spec);
            ctx.put("reservedWords", reservedWords);
            ctx.put("langName",      descriptor.name);

            putLegacyHelper(ctx, List.of());
            render(sf.template, ctx, outPath);
            LOG.info("  → " + outPath + " (static)");
        }
    }

    // =========================================================================
    // Post files
    // =========================================================================

    private void emitPostFiles(Path langRoot,
                               LinkedHashMap<Path, List<String>> dirIndex,
                               TypeResolver types,
                               IdlSpecification spec,
                               Set<String> reservedWords) throws Exception {
        if (descriptor.post_files == null) return;
        for (LanguageDescriptor.PostFileEntry pf : descriptor.post_files) {
            if ("per_output_directory".equals(pf.trigger)) {
                for (Map.Entry<Path, List<String>> e : dirIndex.entrySet()) {
                    Path dir     = e.getKey();
                    List<String> names = e.getValue();
                    Path outPath = dir.resolve(pf.path);

                    VelocityContext ctx = new VelocityContext();
                    ctx.put("types",         types);
                    ctx.put("spec",          spec);
                    ctx.put("reservedWords", reservedWords);
                    ctx.put("langName",      descriptor.name);
                    if (pf.context_key != null) {
                        ctx.put(pf.context_key, names);
                    }

                    putLegacyHelper(ctx, List.of());
                    render(pf.template, ctx, outPath);
                    LOG.info("  → " + outPath + " (post)");
                }
            }
        }
    }

    // =========================================================================
    // Include helpers
    // =========================================================================

    private void collectIncludesForFields(List<FieldNode> fields, Set<String> acc) {
        if (descriptor.include_computation == null) return;
        boolean hasSequence = false;
        for (FieldNode f : fields) {
            collectIncludes(f.type(), acc);
            if (f.type() instanceof IdlType.Sequence) hasSequence = true;
        }
        if (hasSequence && descriptor.include_computation.sequence_include != null) {
            acc.add(descriptor.include_computation.sequence_include);
        }
    }

    private void collectIncludes(IdlType t, Set<String> acc) {
        if (descriptor.include_computation == null) return;
        if (t instanceof IdlType.Scoped s) {
            if (!isSkipped(s.qualifiedName())) {
                acc.add(includePathForScoped(s.qualifiedName()));
            }
        } else if (t instanceof IdlType.Sequence seq) {
            collectIncludes(seq.elementType(), acc);
            if (descriptor.include_computation.sequence_include != null) {
                acc.add(descriptor.include_computation.sequence_include);
            }
        } else if (t instanceof IdlType.Array arr) {
            collectIncludes(arr.elementType(), acc);
        }
    }

    private boolean isSkipped(String qualifiedName) {
        if (descriptor.include_computation == null
                || descriptor.include_computation.skip_prefixes == null) return false;
        for (String prefix : descriptor.include_computation.skip_prefixes) {
            if (qualifiedName.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Like {@link #isSkipped(String)} but uses {@code template_inst_skip_prefixes}
     * when present, falling back to {@code skip_prefixes}.  C++ uses a narrower
     * skip set for template-instantiation resolved actuals than for struct fields.
     */
    private boolean isTemplateInstSkipped(String qualifiedName) {
        if (descriptor.include_computation == null) return false;
        List<String> prefixes = descriptor.include_computation.template_inst_skip_prefixes != null
                ? descriptor.include_computation.template_inst_skip_prefixes
                : descriptor.include_computation.skip_prefixes;
        if (prefixes == null) return false;
        for (String prefix : prefixes) {
            if (qualifiedName.startsWith(prefix)) return true;
        }
        return false;
    }

    private String includePathForScoped(String qualifiedName) {
        if (descriptor.include_computation != null
                && descriptor.include_computation.include_path_pattern != null) {
            TypeResolver tmp = new TypeResolver(descriptor, Map.of());
            return tmp.includePathFor(qualifiedName);
        }
        String stripped = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        return stripped.replace("::", "/") + ".hpp";
    }

    // =========================================================================
    // Path helpers
    // =========================================================================

    private Path resolveConstructOutputPath(LanguageDescriptor.TemplateEntry entry,
                                            List<String> moduleStack,
                                            String constructName,
                                            Path langRoot) {
        String pattern = entry.path != null ? entry.path
                : (descriptor.iteration != null ? descriptor.iteration.path : "{name}");
        if (pattern == null) pattern = "{name}";
        String relPath = TypeResolver.expandPathPattern(pattern, moduleStack, constructName);
        return langRoot.resolve(relPath);
    }

    /**
     * Walks {@code defs} descending into the first {@link ModuleNode} at each
     * level to extract the module chain.  Stops at the first non-module definition.
     *
     * <p>Example: for an IDL file with top-level
     * {@code module FACE { module DM { module SampleModel { struct … } } }}
     * returns {@code ["FACE", "DM", "SampleModel"]}.
     */
    private static List<String> extractModuleStack(List<IdlDefinition> defs) {
        List<String> stack = new ArrayList<>();
        List<IdlDefinition> current = defs;
        while (!current.isEmpty() && current.get(0) instanceof ModuleNode m) {
            stack.add(m.name());
            current = m.definitions();
        }

        // A template instantiation — "module ::FACE::TSS::Typed<Money_t> Money;" — opens
        // one further namespace level in the generated code (the emitted header really does
        // declare "namespace Money"), but it is a TemplateInstNode rather than a ModuleNode,
        // so the loop above never sees it.  It must still contribute to a namespace-mirrored
        // output path.  FACE Technical Standard 3.2 §4.8.4.1: the declarations map "as if the
        // interface was declared in a directory tree and IDL file as
        // FACE/TSS/<UOP_MODEL_NAME>/<DATATYPE_TYPE>/TypedTS.idl".
        //
        // Without this every TypedTS.idl belonging to one UoPModel resolves to the same
        // header path (…/<MODEL>/TypedTS.hpp) and all but the last is silently overwritten.
        List<String> aliases = new ArrayList<>();
        for (IdlDefinition d : current) {
            if (d instanceof TemplateInstNode inst
                    && inst.alias() != null && !inst.alias().isBlank()) {
                aliases.add(inst.alias());
            }
        }
        if (aliases.size() == 1) {
            stack.add(aliases.get(0));
        } else if (aliases.size() > 1) {
            LOG.warning("IDL file unit declares " + aliases.size() + " template instantiations "
                    + aliases + " in one module scope; the namespace-mirrored output path "
                    + "cannot be disambiguated, so no alias level was added. "
                    + "FACE 3.2 §4.8.4.1 expects one TypedTS instantiation per file.");
        }
        return List.copyOf(stack);
    }

    private Path applyExtensionReplace(Path relativePath) {
        if (descriptor.iteration == null
                || descriptor.iteration.extension_replace == null
                || descriptor.iteration.extension_replace.isEmpty()) {
            return relativePath;
        }
        String pathStr = relativePath.toString().replace('\\', '/');
        for (Map.Entry<String, String> e : descriptor.iteration.extension_replace.entrySet()) {
            if (pathStr.endsWith(e.getKey())) {
                pathStr = pathStr.substring(0, pathStr.length() - e.getKey().length())
                        + e.getValue();
                break;
            }
        }
        return Path.of(pathStr);
    }

    // =========================================================================
    // Construct classification
    // =========================================================================

    private String constructKind(IdlDefinition def) {
        if (def instanceof StructNode)       return "struct";
        if (def instanceof EnumNode)         return "enum";
        if (def instanceof InterfaceNode)    return "interface";
        if (def instanceof TemplateInstNode) return "template_inst";
        if (def instanceof TypedefNode)      return "typedef";
        if (def instanceof ConstNode)        return "const";
        if (def instanceof UnionNode)        return "union";
        return def.getClass().getSimpleName().toLowerCase();
    }

    /** Returns the output file base name (without extension) for a construct. */
    private String constructOutputName(IdlDefinition def) {
        if (def instanceof TemplateInstNode inst) return inst.alias();
        return def.name();
    }

    // =========================================================================
    // Backward-compatibility: legacy context keys
    // =========================================================================

    /**
     * Puts the construct under the legacy Velocity context keys expected by the
     * existing (pre-generic-engine) per-type templates.  This allows the generic
     * engine to produce identical output from unchanged templates during migration
     * Phases 2–5.
     *
     * <ul>
     *   <li>Struct → {@code $struct} (Java, Python, C++, C# templates)</li>
     *   <li>Enum → {@code $enumNode} (all per-type templates)</li>
     *   <li>Interface → {@code $iface} (all per-type templates)</li>
     *   <li>TemplateInst → {@code $alias}, {@code $definitions},
     *       {@code $localInterfaces}, {@code $interfaceKindActuals},
     *       {@code $resolvedIncludes} / {@code $resolvedImports} (language-dependent)</li>
     *   <li>{@code $moduleStack} is already in the base context</li>
     * </ul>
     *
     * <p>The language-specific helper ({@code $java}, {@code $py}, {@code $cs},
     * {@code $cpp}) is NOT placed here because it requires instantiating the old
     * per-language helper class — doing so in the generic engine would defeat the
     * purpose of the refactor.  During Phase 2 (Java migration) we will create
     * {@code java/macros.vm} which defines the required Velocity macros ({@code paramDecl},
     * {@code getterName}, etc.) that replace the {@code $java.*} call sites in templates.
     * Templates will then be updated to call macros instead of object methods.
     */
    private void putLegacyConstructKeys(VelocityContext ctx,
                                        IdlDefinition def,
                                        List<String> moduleStack,
                                        TemplateInstantiator.InstantiationResult instantiation) {
        // Construct-kind-specific legacy keys
        if (def instanceof StructNode s) {
            ctx.put("struct", s);
        } else if (def instanceof EnumNode e) {
            ctx.put("enumNode", e);
        } else if (def instanceof InterfaceNode i) {
            ctx.put("iface", i);
        } else if (def instanceof TemplateInstNode inst) {
            ctx.put("alias", inst.alias());
            if (instantiation != null) {
                ctx.put("definitions",          instantiation.definitions);
                ctx.put("localInterfaces",      instantiation.localInterfaceNames);
                ctx.put("interfaceKindActuals", instantiation.interfaceKindActuals);
            }
        } else if (def instanceof UnionNode u) {
            ctx.put("union", u);
        } else if (def instanceof ConstNode c) {
            ctx.put("constNode", c);
        } else if (def instanceof TypedefNode t) {
            ctx.put("typedef", t);
        }
        // $namespaces is an alias for $moduleStack used by C++ templates.
        // Putting it universally is harmless — other languages simply don't use it.
        ctx.put("namespaces", moduleStack);
    }

    /**
     * Computes derived context keys that bespoke mappers pre-computed before
     * calling Velocity.  These are language-specific values that cannot be expressed
     * purely in the descriptor schema, so the generic engine delegates to the legacy
     * helper via reflection when it is present.
     *
     * <ul>
     *   <li>{@code $localImports} — for struct templates: list of simple class names
     *       that are locally-defined field types and require a relative import.
     *       Requires the helper to expose {@code isLocalClass(IdlType)} and
     *       {@code localClassName(IdlType)} (Python pattern).</li>
     *   <li>{@code $resolvedImports} — for template_inst templates: list of
     *       {@code "from pkg.Cls import Cls"} strings for resolved actual types.
     *       Requires the helper to expose {@code resolvedImports(List<IdlType>)}
     *       (Python pattern).</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void putComputedLegacyKeys(VelocityContext ctx,
                                       IdlDefinition def,
                                       TemplateInstantiator.InstantiationResult instantiation) {
        // ── Descriptor-driven include computation (independent of legacy helper) ────────
        // $includes — local #include paths for C++ struct templates
        if (def instanceof StructNode s && descriptor.include_computation != null) {
            LinkedHashSet<String> inc = new LinkedHashSet<>();
            collectIncludesForFields(s.members(), inc);
            ctx.put("includes", new ArrayList<>(inc));
        }

        // $resolvedIncludes — #include paths for C++ template_inst resolved actual types
        if (def instanceof TemplateInstNode && instantiation != null
                && descriptor.include_computation != null) {
            List<String> resolvedIncludes = new ArrayList<>();
            for (IdlType actual : instantiation.resolvedActuals) {
                if (actual instanceof IdlType.Scoped s && !isTemplateInstSkipped(s.qualifiedName())) {
                    resolvedIncludes.add(includePathForScoped(s.qualifiedName()));
                }
            }
            ctx.put("resolvedIncludes", resolvedIncludes);
        }

        // $resolvedImports — "from pkg.mod import Cls" lines for Python template_inst templates.
        // Computed descriptor-driven when immutable_types is configured (Python-style language).
        // Only runs when the legacy helper hasn't already set it (legacy takes precedence).
        if (def instanceof TemplateInstNode && instantiation != null
                && descriptor.immutable_types != null
                && !descriptor.immutable_types.isEmpty()
                && ctx.get("resolvedImports") == null) {
            List<String> riList = new ArrayList<>();
            for (IdlType actual : instantiation.resolvedActuals) {
                if (actual instanceof IdlType.Scoped s) {
                    String qn = s.qualifiedName().startsWith("::")
                            ? s.qualifiedName().substring(2) : s.qualifiedName();
                    int lastColon = qn.lastIndexOf(':');
                    if (lastColon > 0) {
                        String pkg = qn.substring(0, lastColon - 1).replace("::", ".");
                        String cls = qn.substring(lastColon + 1);
                        riList.add("from " + pkg + "." + cls + " import " + cls);
                    }
                }
            }
            ctx.put("resolvedImports", riList);
        }

        // $localImports — relative import list for Python-style struct templates.
        // A field type is "local" when it is a Scoped type that resolves to a class
        // name (i.e. NOT in immutable_types, which lists Python scalars like int/float).
        // Only computed when immutable_types is configured in the language descriptor.
        if (def instanceof StructNode s
                && descriptor.immutable_types != null
                && !descriptor.immutable_types.isEmpty()) {
            TypeResolver resolver = (TypeResolver) ctx.get("types");
            if (resolver != null) {
                LinkedHashSet<String> localCls = new LinkedHashSet<>();
                for (FieldNode f : s.members()) {
                    collectLocalClassNames(f.type(), resolver, localCls);
                }
                ctx.put("localImports", new ArrayList<>(localCls));
            }
        }

        if (legacyHelper == null) return;

        // $localImports — list of locally-defined type names for relative imports (Python structs)
        if (def instanceof StructNode s) {
            try {
                java.lang.reflect.Method isLocal =
                        legacyHelper.getClass().getMethod("isLocalClass", IdlType.class);
                java.lang.reflect.Method localCls =
                        legacyHelper.getClass().getMethod("localClassName", IdlType.class);
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (FieldNode f : s.members()) {
                    gatherLocalTypes(f.type(), isLocal, localCls, seen);
                }
                ctx.put("localImports", new ArrayList<>(seen));
            } catch (NoSuchMethodException ignored) {
                // Helper doesn't support isLocalClass — leave $localImports unset.
            }
        }

        // $resolvedImports — import lines for template_inst resolved actual types (Python)
        if (def instanceof TemplateInstNode && instantiation != null) {
            try {
                java.lang.reflect.Method m =
                        legacyHelper.getClass().getMethod("resolvedImports", List.class);
                ctx.put("resolvedImports",
                        (List<String>) m.invoke(legacyHelper, instantiation.resolvedActuals));
            } catch (NoSuchMethodException ignored) {
                // Helper doesn't support resolvedImports — put an empty list as fallback.
                ctx.put("resolvedImports", List.of());
            } catch (Exception e) {
                LOG.fine(descriptor.name + ": resolvedImports reflection failed: " + e.getMessage());
                ctx.put("resolvedImports", List.of());
            }
        }

    }

    /**
     * Descriptor-driven equivalent of the legacy {@code isLocalClass} / {@code localClassName}
     * pair.  Adds the resolved type name to {@code acc} when {@code t} is a Scoped type
     * that resolves to a class name (i.e. NOT in {@link LanguageDescriptor#immutable_types}).
     *
     * <p>Recursively descends into Sequence and Array element types so that
     * {@code List[GeoPosition]} fields also trigger an import for {@code GeoPosition}.
     *
     * @param t        IDL type of a struct field
     * @param resolver TypeResolver for the current language
     * @param acc      accumulator for unique local class names
     */
    private void collectLocalClassNames(IdlType t, TypeResolver resolver, Set<String> acc) {
        if (t instanceof IdlType.Scoped) {
            String typeName = resolver.type(t);
            if (descriptor.immutable_types == null || !descriptor.immutable_types.contains(typeName)) {
                acc.add(typeName);
            }
        } else if (t instanceof IdlType.Sequence seq) {
            collectLocalClassNames(seq.elementType(), resolver, acc);
        } else if (t instanceof IdlType.Array arr) {
            collectLocalClassNames(arr.elementType(), resolver, acc);
        }
    }

    /**
     * Recursively collects locally-defined type names from a field type by calling
     * the legacy helper's {@code isLocalClass} and {@code localClassName} methods.
     */
    private void gatherLocalTypes(IdlType t,
                                  java.lang.reflect.Method isLocal,
                                  java.lang.reflect.Method localCls,
                                  Set<String> acc) {
        try {
            if ((Boolean) isLocal.invoke(legacyHelper, t)) {
                acc.add((String) localCls.invoke(legacyHelper, t));
                return;
            }
        } catch (Exception ignored) {}
        if (t instanceof IdlType.Sequence seq) {
            gatherLocalTypes(seq.elementType(), isLocal, localCls, acc);
        } else if (t instanceof IdlType.Array arr) {
            gatherLocalTypes(arr.elementType(), isLocal, localCls, acc);
        }
    }

    /**
     * Puts legacy context keys for per-IDL-file templates.
     * The existing C++ {@code file.hpp.vm} uses {@code $cpp} directly; that will
     * be addressed in Phase 5 when the C++ language.yaml and macros.vm are authored.
     */
    private void putLegacyFileContextKeys(VelocityContext ctx, TypeResolver types) {
        // No-op in Phase 1.  In Phase 5, when the C++ descriptor is written,
        // macros.vm will define the macro equivalents of $cpp.* methods so that
        // file.hpp.vm can be updated to use $types / macros instead of $cpp.
    }

    // =========================================================================
    // Velocity rendering
    // =========================================================================

    private void render(String templateName, VelocityContext ctx, Path out)
            throws IOException {
        Template tmpl = velocity.getTemplate(templateName, "UTF-8");
        try (Writer w = Files.newBufferedWriter(out)) {
            tmpl.merge(ctx, w);
        }
    }
}
