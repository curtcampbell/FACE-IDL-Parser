package com.warhex.er.generator.codegen.pipeline;

import com.warhex.er.generator.ast.ConstNode;
import com.warhex.er.generator.ast.EnumNode;
import com.warhex.er.generator.ast.TypedefNode;
import com.warhex.er.generator.ast.UnionNode;
import com.warhex.er.generator.codegen.context.ContextAssembler;
import com.warhex.er.generator.codegen.context.ContextAssembler.ScopedElement;
import com.warhex.er.generator.parser.IdlFileUnit;
import com.warhex.er.generator.codegen.defaults.CodegenDefaults;
import com.warhex.er.generator.codegen.defaults.CodegenDefaultsLoader;
import com.warhex.er.generator.codegen.directive.TemplateDirectiveParser;
import com.warhex.er.generator.codegen.directive.TemplateDirectives;
import com.warhex.er.generator.codegen.helpers.HelperRegistry;
import com.warhex.er.generator.codegen.manifest.CodeGenManifest;
import com.warhex.er.generator.codegen.manifest.ForEachScope;
import com.warhex.er.generator.codegen.manifest.GenerationEntry;
import com.warhex.er.generator.codegen.manifest.HelperEntry;
import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.reader.dto.ConnectionData;
import com.warhex.er.generator.reader.dto.IntegrationContextData;
import com.warhex.er.generator.reader.dto.UoPData;
import com.warhex.er.generator.reader.dto.UoPModelData;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.FileResourceLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drives the code generation run for a single {@link CodeGenManifest}.
 *
 * <h2>Run sequence</h2>
 * <ol>
 *   <li>Configure a {@link VelocityEngine} with a {@link FileResourceLoader}
 *       rooted at the user's {@code --template-dir}.</li>
 *   <li>Build a {@link ContextAssembler} from the parsed IDL and optional
 *       UoP model.</li>
 *   <li>For each {@link GenerationEntry} in the manifest, iterate over the
 *       elements defined by its {@link com.warhex.er.generator.codegen.manifest.ForEachScope},
 *       render the Velocity template, and write the result to disk.</li>
 * </ol>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>A {@link com.warhex.er.generator.ast.TemplateInstNode} that cannot
 *       be resolved by the instantiator is skipped with a WARNING; all other
 *       entries are still rendered.</li>
 *   <li>Any other rendering error (missing template file, Velocity parse
 *       error, etc.) propagates immediately as an {@link Exception}.</li>
 * </ul>
 */
public class CodeGenPipeline {

    private static final Logger LOG =
            Logger.getLogger(CodeGenPipeline.class.getName());

    /** Variable names that IdlDerivedVariables can supply; used for validation logging. */
    private static final Set<String> DERIVED_VARS = Set.of(
            "model_namespace", "project_namespace", "face_tss_namespace",
            "entity_payload_idl", "entity_payload_idl_enum");

    /**
     * Matches  {@code #set($outFile = "expr")}  in a driver template line.
     * Group 1 is the Velocity expression for the output path.
     */
    private static final Pattern DRIVER_OUTFILE =
            Pattern.compile("#set\\s*\\(\\s*\\$outFile\\s*=\\s*\"([^\"]+)\"\\s*\\)");

    /**
     * Matches  {@code #parse("leaf.vm")}  in a driver template line.
     * Group 1 is the leaf template resource name.
     */
    private static final Pattern DRIVER_PARSE =
            Pattern.compile("#parse\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    private final CodeGenManifest   manifest;
    private final CodegenDefaults   defaults;
    private final Path              templateDir;
    private final Path              outputDir;
    private final ContextAssembler  assembler;
    private final VelocityEngine    velocity;

    // counters for summary logging
    private int filesWritten = 0;
    private int skipped      = 0;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates a pipeline instance.  Call {@link #run()} to execute.
     *
     * @param manifest    validated manifest (from {@code codegen.yaml})
     * @param templateDir user template directory (contains the manifest and {@code .vm} files)
     * @param outputDir   root directory for generated files
     * @param assembler   pre-built context assembler
     */
    public CodeGenPipeline(CodeGenManifest manifest,
                            Path templateDir,
                            Path outputDir,
                            ContextAssembler assembler) {
        this.manifest    = manifest;
        this.defaults    = CodegenDefaultsLoader.load(templateDir);
        this.templateDir = templateDir;
        this.outputDir   = effectiveOutputDir(outputDir, manifest.output_subdirectory);
        this.assembler   = assembler;
        this.velocity    = buildVelocityEngine(templateDir);
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    /**
     * Executes all generation entries in the manifest.
     *
     * @throws Exception if any template fails to render (excluding unresolvable
     *                   {@code TEMPLATE_INST} entries, which are only warned)
     */
    public void run() throws Exception {
        Files.createDirectories(outputDir);
        LOG.info("Output directory: " + outputDir);
        List<GenerationEntry> manifestEntries =
                manifest.generations != null ? manifest.generations : List.of();
        LOG.info("Processing " + manifestEntries.size() + " manifest generation entries.");

        VelocityContext base = assembler.baseContext();
        injectDefaults(base);    // template-set constants (middle priority)
        injectVariables(base);   // per-project manifest variables (highest priority)
        injectHelpers(base);     // per-project manifest helpers

        // 1. Manifest-driven generation (existing behaviour; always takes precedence)
        Set<String> handledByManifest = new HashSet<>();
        for (GenerationEntry entry : manifestEntries) {
            LOG.info("Entry: " + entry);
            handledByManifest.add(entry.template);
            runEntry(entry, base);
        }

        // 2. Directive-driven generation for templates not covered by the manifest
        runDirectiveTemplates(base, handledByManifest);

        LOG.info("Code generation complete. Files written: " + filesWritten
                + ", skipped: " + skipped + ".");
    }

    // -----------------------------------------------------------------------
    // Per-entry dispatch
    // -----------------------------------------------------------------------

    private void runEntry(GenerationEntry entry, VelocityContext base) throws Exception {
        switch (entry.resolvedScope()) {

            case SPEC -> renderOne(entry, base, resolveVars(entry.output));

            case MODULE -> {
                for (ModuleNode module : assembler.topLevelModules()) {
                    if (!entry.matchesPattern(module.name())) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withModule(base, module, List.of(module.name()));
                    String          path = resolveVars(OutputPathResolver.forModule(entry.output, module));
                    renderOne(entry, ctx, path);
                }
            }

            case STRUCT -> {
                for (ScopedElement<StructNode> se : assembler.allStructs()) {
                    if (!entry.matchesPattern(se.node().name())) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withStruct(base, se.node(), se.enclosingModule(), se.namespaces());
                    String          path = resolveVars(OutputPathResolver.forStruct(entry.output, se.node(), se.enclosingModule(), se.namespaces()));
                    renderOne(entry, ctx, path);
                }
            }

            case INTERFACE -> {
                for (ScopedElement<InterfaceNode> se : assembler.allInterfaces()) {
                    if (!entry.matchesPattern(se.node().name())) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withInterface(base, se.node(), se.enclosingModule(), se.namespaces());
                    String          path = resolveVars(OutputPathResolver.forInterface(entry.output, se.node(), se.enclosingModule(), se.namespaces()));
                    renderOne(entry, ctx, path);
                }
            }

            case TEMPLATE_INST -> {
                for (ScopedElement<TemplateInstNode> se : assembler.allTemplateInsts()) {
                    if (!entry.matchesPattern(se.node().alias())) { skipped++; continue; }
                    Optional<VelocityContext> optCtx = assembler.withTemplateInst(
                            base, se.node(), se.enclosingModule(), se.namespaces());
                    if (optCtx.isEmpty()) {
                        LOG.warning("Skipping unresolvable template inst: "
                                + se.node().templateName() + "/" + se.node().alias());
                        skipped++;
                        continue;
                    }
                    String path = resolveVars(OutputPathResolver.forTemplateInst(
                            entry.output, se.node(), se.enclosingModule(), se.namespaces()));
                    renderOne(entry, optCtx.get(), path);
                }
            }

            case UOP -> {
                requireModel(entry);
                @SuppressWarnings("unchecked")
                List<UoPData> uopsForEntry = (List<UoPData>) base.get("uops");
                for (UoPData uop : uopsForEntry) {
                    if (!entry.matchesPattern(uop.getName())) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withUop(base, uop);
                    String          path = resolveVars(OutputPathResolver.forUop(entry.output, uop));
                    renderOne(entry, ctx, path);
                }
            }

            case CONNECTION -> {
                requireModel(entry);
                @SuppressWarnings("unchecked")
                List<UoPData> uops = (List<UoPData>) base.get("uops");
                for (UoPData uop : uops) {
                    for (ConnectionData conn : uop.getConnections()) {
                        if (!entry.matchesPattern(conn.getName())) { skipped++; continue; }
                        VelocityContext ctx  = assembler.withConnection(base, uop, conn);
                        String          path = resolveVars(OutputPathResolver.forConnection(entry.output, uop, conn));
                        renderOne(entry, ctx, path);
                    }
                }
            }

            case UOP_INTEGRATION_CONTEXT -> {
                requireModel(entry);
                @SuppressWarnings("unchecked")
                List<IntegrationContextData> ics =
                        (List<IntegrationContextData>) base.get("integrationContexts");
                for (IntegrationContextData ic : ics) {
                    if (!entry.matchesPattern(ic.getName())) { skipped++; continue; }
                    UoPData uop = findUopByName(ics, ic, base);
                    if (uop == null) {
                        LOG.warning("UOP_INTEGRATION_CONTEXT: no UoPData found for IC '"
                                + ic.getName() + "' (uopName='" + ic.getUopName() + "') — skipped.");
                        skipped++;
                        continue;
                    }
                    VelocityContext ctx  = assembler.withIntegrationContext(base, uop, ic);
                    String          path = resolveVars(
                            OutputPathResolver.forUopIntegrationContext(entry.output, uop, ic));
                    renderOne(entry, ctx, path);
                }
            }

            case MODEL_NAMESPACE -> {
                requireModel(entry);
                for (String ns : assembler.allModelNamespaces()) {
                    if (!entry.matchesPattern(ns)) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withModelNamespace(base, ns);
                    String          path = resolveVars(OutputPathResolver.forModelNamespace(entry.output, ns));
                    renderOne(entry, ctx, path);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core render-and-write
    // -----------------------------------------------------------------------

    /** Delegates to {@link #renderOne(String, VelocityContext, String)}. */
    private void renderOne(GenerationEntry entry,
                            VelocityContext ctx,
                            String relativePath) throws Exception {
        renderOne(entry.template, ctx, relativePath);
    }

    /**
     * Renders {@code templateName} into {@code ctx} and writes the result to
     * {@code relativePath} under the effective output directory.
     */
    private void renderOne(String templateName,
                            VelocityContext ctx,
                            String relativePath) throws Exception {
        if (OutputPathResolver.hasUnresolvedTokens(relativePath)) {
            LOG.warning("Unresolved tokens in output path '" + relativePath
                    + "' for template '" + templateName + "'. Skipping.");
            skipped++;
            return;
        }

        Path outFile = outputDir.resolve(relativePath).normalize();
        Files.createDirectories(outFile.getParent());

        Template template = velocity.getTemplate(templateName, StandardCharsets.UTF_8.name());

        try (Writer writer = new OutputStreamWriter(
                new BufferedOutputStream(Files.newOutputStream(outFile)),
                StandardCharsets.UTF_8)) {
            template.merge(ctx, writer);
        }

        LOG.info("  Written: " + outFile);
        filesWritten++;
    }

    /**
     * Renders a driver template by scanning its source for
     * {@code #set($outFile = "expr")} / {@code #parse("leaf.vm")} pairs and
     * writing each leaf template to the resolved output path.
     *
     * <h2>Driver template contract</h2>
     * <p>Lines that do not start with {@code ##} (comment) and are not blank
     * are scanned for the two recognised patterns.  All other lines are ignored.
     * Control-flow constructs ({@code #foreach}, {@code #if}) in the driver body
     * are not executed — iteration is handled externally by the pipeline's scope
     * loop.  The Velocity expression in {@code $outFile} is evaluated via
     * {@link VelocityEngine#evaluate} so that variables such as
     * {@code ${project_namespace}} and {@code ${inst.alias}} are resolved from
     * the current scope context.
     *
     * @param templateName relative path to the driver {@code .vm} file
     * @param ctx          scope context for the current iteration element
     */
    private void renderDriver(String templateName, VelocityContext ctx) throws Exception {
        Path driverFile = templateDir.resolve(templateName);
        List<String> lines = Files.readAllLines(driverFile, StandardCharsets.UTF_8);

        String pendingOutFile = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // Skip blank lines and Velocity / pipeline comment lines
            if (line.isEmpty() || line.startsWith("##")) continue;

            // ---- #set($outFile = "velocity-expression") ----
            Matcher setM = DRIVER_OUTFILE.matcher(line);
            if (setM.find()) {
                // Evaluate the expression in the current scope context so that
                // ${project_namespace}, ${inst.alias}, etc. are substituted.
                StringWriter sw = new StringWriter();
                velocity.evaluate(ctx, sw, templateName + ":outFile", setM.group(1));
                pendingOutFile = sw.toString().trim();
                continue;
            }

            // ---- #parse("leaf-template.vm") ----
            Matcher parseM = DRIVER_PARSE.matcher(line);
            if (parseM.find()) {
                if (pendingOutFile == null || pendingOutFile.isEmpty()) {
                    LOG.warning("Driver '" + templateName
                            + "': #parse encountered without a preceding"
                            + " #set($outFile = ...). Skipping.");
                    continue;
                }
                String leafName = parseM.group(1);
                Path target = outputDir.resolve(pendingOutFile).normalize();
                if (!target.startsWith(outputDir)) {
                    throw new SecurityException("$outFile path traversal detected: '"
                            + pendingOutFile + "' resolves outside output directory.");
                }
                Files.createDirectories(target.getParent());
                Template leaf = velocity.getTemplate(leafName, StandardCharsets.UTF_8.name());
                // Use a child context so leaf-local #set variables are isolated.
                VelocityContext leafCtx = new VelocityContext(ctx);
                try (Writer w = new OutputStreamWriter(
                        new BufferedOutputStream(Files.newOutputStream(target)),
                        StandardCharsets.UTF_8)) {
                    leaf.merge(leafCtx, w);
                }
                LOG.info("  Written (driver): " + target);
                filesWritten++;
                pendingOutFile = null;
                continue;
            }

            LOG.fine("Driver '" + templateName + "': unrecognised line (skipped): " + line);
        }
    }

    // -----------------------------------------------------------------------
    // Directive-driven template discovery and dispatch (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * Scans {@link #templateDir} for {@code *.vm} and {@code *.vtl} files,
     * parses their {@code ##!} directive headers, and runs any that are not
     * already covered by a manifest generation entry.
     *
     * <p>Manifest entries take precedence: a template whose filename appears
     * in {@code handledByManifest} is silently skipped even if it carries
     * directives.
     */
    private void runDirectiveTemplates(VelocityContext base,
                                        Set<String> handledByManifest) throws Exception {
        List<Path> vmFiles;
        try (Stream<Path> stream = Files.walk(templateDir)) {
            vmFiles = stream
                    .filter(p -> {
                        String n = p.toString();
                        return n.endsWith(".vm") || n.endsWith(".vtl");
                    })
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        }

        for (Path vmFile : vmFiles) {
            // Compute template name relative to templateDir, always using '/'
            String relName = templateDir.relativize(vmFile)
                    .toString().replace(File.separatorChar, '/');

            if (handledByManifest.contains(relName)) continue;  // manifest takes precedence

            TemplateDirectives d = TemplateDirectiveParser.parse(vmFile);
            if (!d.hasDirectives()) continue;

            LOG.info("Directive-driven template: " + relName);
            runDirectiveEntry(relName, d, base);
        }
    }

    /**
     * Dispatches a single directive-driven template according to its
     * {@link TemplateDirectives}.
     */
    private void runDirectiveEntry(String templateName,
                                    TemplateDirectives d,
                                    VelocityContext base) throws Exception {
        // A template must either have ##! output: (non-driver leaf) or ##! driver: true.
        if (d.outputPattern() == null && !d.driver()) {
            LOG.warning("Directive template '" + templateName
                    + "' has no ##! output: directive and is not a driver. Skipping.");
            skipped++;
            return;
        }

        ForEachScope scope = d.forEachScope() != null ? d.forEachScope() : ForEachScope.GLOBAL;

        switch (scope) {

            case GLOBAL, SPEC -> {
                if (d.driver()) {
                    renderDriver(templateName, base);
                } else {
                    renderOne(templateName, base, resolveVars(d.outputPattern()));
                }
            }

            case MODULE -> {
                for (ModuleNode module : assembler.topLevelModules()) {
                    if (!matchesFilter(d.filterPattern(), module.name())) { skipped++; continue; }
                    VelocityContext ctx  = assembler.withModule(base, module, List.of(module.name()));
                    String          path = resolveVars(OutputPathResolver.forModule(d.outputPattern(), module));
                    renderOne(templateName, ctx, path);
                }
            }

            case STRUCT -> {
                for (ScopedElement<StructNode> se : assembler.allStructs()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withStruct(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forStruct(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case INTERFACE -> {
                for (ScopedElement<InterfaceNode> se : assembler.allInterfaces()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withInterface(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forInterface(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case TEMPLATE_INST -> {
                for (ScopedElement<TemplateInstNode> se : assembler.allTemplateInsts()) {
                    if (!matchesFilter(d.filterPattern(), se.node().alias())) { skipped++; continue; }
                    Optional<VelocityContext> optCtx = assembler.withTemplateInst(
                            base, se.node(), se.enclosingModule(), se.namespaces());
                    if (optCtx.isEmpty()) {
                        LOG.warning("Skipping unresolvable template inst: "
                                + se.node().templateName() + "/" + se.node().alias());
                        skipped++;
                        continue;
                    }
                    if (d.driver()) {
                        renderDriver(templateName, optCtx.get());
                    } else {
                        String path = resolveVars(OutputPathResolver.forTemplateInst(
                                d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, optCtx.get(), path);
                    }
                }
            }

            case ENUM -> {
                for (ScopedElement<EnumNode> se : assembler.allEnums()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withEnum(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forEnum(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case TYPEDEF -> {
                for (ScopedElement<TypedefNode> se : assembler.allTypedefs()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withTypedef(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forTypedef(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case UNION -> {
                for (ScopedElement<UnionNode> se : assembler.allUnions()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withUnion(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forUnion(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case CONST -> {
                for (ScopedElement<ConstNode> se : assembler.allConsts()) {
                    if (!matchesFilter(d.filterPattern(), se.node().name())) { skipped++; continue; }
                    VelocityContext ctx = assembler.withConst(base, se.node(), se.enclosingModule(), se.namespaces());
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forConst(d.outputPattern(), se.node(), se.enclosingModule(), se.namespaces()));
                        renderOne(templateName, ctx, path);
                    }
                }
            }

            case UOP, CONNECTION, UOP_INTEGRATION_CONTEXT -> {
                LOG.warning("Directive-driven UOP/CONNECTION/UOP_INTEGRATION_CONTEXT scope in '" + templateName
                        + "' requires --face-file model. Use a manifest entry for model-driven templates. Skipping.");
                skipped++;
            }

            case FILE -> {
                for (IdlFileUnit file : assembler.fileUnits()) {
                    // Normalise to forward slashes for cross-platform filter matching
                    String relPath = file.relativePath().toString()
                                        .replace(File.separatorChar, '/');
                    if (!matchesFilter(d.filterPattern(), relPath)) { skipped++; continue; }
                    VelocityContext ctx = assembler.withFile(base, file);
                    if (d.driver()) {
                        renderDriver(templateName, ctx);
                    } else {
                        String path = resolveVars(OutputPathResolver.forFile(d.outputPattern(), file));
                        renderOne(templateName, ctx, path);
                    }
                }
            }
        }
    }

    /**
     * Returns {@code true} if {@code name} satisfies {@code filterPattern}.
     * Matches using {@link String#matches} (full-string Java regex).
     * A null or blank pattern always returns {@code true}.
     */
    private static boolean matchesFilter(String filterPattern, String name) {
        if (filterPattern == null || filterPattern.isBlank()) return true;
        return name != null && name.matches(filterPattern);
    }

    // -----------------------------------------------------------------------
    // Velocity engine setup
    // -----------------------------------------------------------------------

    private static VelocityEngine buildVelocityEngine(Path templateDir) {
        Properties props = new Properties();
        props.setProperty(RuntimeConstants.RESOURCE_LOADERS, "file");
        props.setProperty("resource.loader.file.class",
                FileResourceLoader.class.getName());
        props.setProperty("resource.loader.file.path",
                templateDir.toAbsolutePath().toString());
        props.setProperty("resource.loader.file.cache", "false");
        props.setProperty(RuntimeConstants.INPUT_ENCODING,
                StandardCharsets.UTF_8.name());
        // Silence Velocity's own logging — the pipeline uses JUL
        props.setProperty(RuntimeConstants.RUNTIME_LOG_NAME,
                "org.apache.velocity");

        VelocityEngine ve = new VelocityEngine();
        ve.init(props);
        return ve;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Path effectiveOutputDir(Path base, String subdir) {
        if (subdir != null && !subdir.isBlank()) {
            return base.resolve(subdir);
        }
        return base;
    }

    /**
     * Injects template-set-level variables and helpers from
     * {@code codegen-defaults.yaml} into the base context.
     *
     * <p>Defaults are applied after IDL-derived variables (from
     * {@link com.warhex.er.generator.codegen.context.IdlDerivedVariables}) but
     * before per-project manifest variables, so the manifest can still override
     * any default.
     */
    private void injectDefaults(VelocityContext base) {
        // Variables
        if (defaults.variables != null && !defaults.variables.isEmpty()) {
            defaults.variables.forEach((key, value) -> {
                base.put(key, value);
                LOG.fine("Defaults variable: $" + key + " = " + value);
            });
            LOG.info("Injected " + defaults.variables.size() + " defaults variable(s).");
        }
        // Helpers
        if (defaults.helpers != null && !defaults.helpers.isEmpty()) {
            for (HelperEntry h : defaults.helpers) {
                if (h.id == null || h.id.isBlank()) {
                    LOG.warning("Skipping defaults helper entry with null/blank id.");
                    continue;
                }
                Object helper  = HelperRegistry.get(h.id, assembler.spec(), assembler.instantiator());
                String varName = (h.as != null && !h.as.isBlank())
                        ? h.as
                        : HelperRegistry.defaultName(h.id);
                base.put(varName, helper);
                LOG.info("Defaults helper '" + h.id + "' registered as $" + varName);
            }
        }
    }

    /**
     * Places each entry from {@link CodeGenManifest#variables} directly into
     * the base Velocity context under its key name.
     *
     * <p>For the five IDL-derivable variable names, the manifest value is
     * compared against the value already in the context (placed by
     * {@link com.warhex.er.generator.codegen.context.IdlDerivedVariables}).
     * A MATCH is logged at INFO; a MISMATCH is logged at WARNING so it is
     * visible in the default JUL console output.  Either way the manifest
     * value wins (it overwrites the derived value), preserving Phase 1
     * behaviour.
     */
    private void injectVariables(VelocityContext base) {
        if (manifest.variables == null || manifest.variables.isEmpty()) return;
        manifest.variables.forEach((key, value) -> {
            if (DERIVED_VARS.contains(key)) {
                Object derived = base.get(key);
                if (derived == null) {
                    LOG.warning("Phase2 NODERIVED  $" + key
                            + ": manifest=\"" + value + "\"; IDL derivation produced nothing.");
                } else if (value.equals(derived.toString())) {
                    LOG.info("Phase2 MATCH      $" + key + " = \"" + value + "\"");
                } else {
                    LOG.warning("Phase2 MISMATCH   $" + key
                            + ": derived=\"" + derived + "\""
                            + "  manifest=\"" + value + "\"");
                }
            }
            base.put(key, value);
            LOG.fine("Context variable: $" + key + " = " + value);
        });
        LOG.info("Injected " + manifest.variables.size() + " manifest variable(s).");
    }

    /**
     * Constructs and places each helper listed in {@link CodeGenManifest#helpers}
     * into the base Velocity context.  Helpers are constructed once per pipeline
     * run and shared across all template renders.
     */
    private void injectHelpers(VelocityContext base) {
        if (manifest.helpers == null || manifest.helpers.isEmpty()) return;
        for (HelperEntry h : manifest.helpers) {
            if (h.id == null || h.id.isBlank()) {
                LOG.warning("Skipping helper entry with null/blank id.");
                continue;
            }
            Object helper  = HelperRegistry.get(h.id, assembler.spec(), assembler.instantiator());
            String varName = (h.as != null && !h.as.isBlank())
                    ? h.as
                    : HelperRegistry.defaultName(h.id);
            base.put(varName, helper);
            LOG.info("Helper '" + h.id + "' registered as $" + varName);
        }
    }

    private void requireModel(GenerationEntry entry) {
        if (!assembler.hasModel()) {
            throw new IllegalStateException(
                    "Generation entry '" + entry.template + "' has for_each="
                    + entry.for_each + " which requires --face-file, but no model was provided.");
        }
    }

    /**
     * Performs a second-pass substitution of manifest {@link CodeGenManifest#variables}
     * into an already-scope-resolved output path.  This allows paths like
     * {@code "{project_namespace}/{inst.alias}TS.hpp"} where
     * {@code project_namespace} is a manifest variable rather than an IDL token.
     *
     * <p>Tokens that are not present in {@code manifest.variables} are left
     * unchanged (they will be caught by
     * {@link OutputPathResolver#hasUnresolvedTokens(String)} in
     * {@link #renderOne}).
     *
     * @param path path string after scope-token substitution
     * @return path with any remaining manifest-variable tokens filled in
     */

    /**
     * Look up the {@link UoPData} whose name matches
     * {@link IntegrationContextData#getUopName()} for a given IC.
     * The UoP list is retrieved from the {@code "uops"} key of the base context.
     *
     * @param ics  the full list of IntegrationContextData (used only for type safety;
     *             the UoP list comes from the context)
     * @param ic   the IntegrationContext whose owning UoP we need
     * @param base the Velocity base context containing {@code "uops"}
     * @return the matching {@link UoPData}, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    private UoPData findUopByName(List<IntegrationContextData> ics,
                                   IntegrationContextData ic,
                                   VelocityContext base) {
        List<UoPData> uops = (List<UoPData>) base.get("uops");
        if (uops == null || ic.getUopName() == null) return null;
        return uops.stream()
                   .filter(u -> ic.getUopName().equals(u.getName()))
                   .findFirst()
                   .orElse(null);
    }

    private String resolveVars(String path) {
        if (manifest.variables == null || manifest.variables.isEmpty()) return path;
        return OutputPathResolver.apply(path, manifest.variables);
    }
}
