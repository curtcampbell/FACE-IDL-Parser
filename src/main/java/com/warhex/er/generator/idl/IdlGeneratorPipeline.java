package com.warhex.er.generator.idl;

import com.warhex.er.generator.model.EntityDescriptor;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.model.FieldDescriptor;
import com.warhex.er.generator.reader.dto.ConnectionData;
import com.warhex.er.generator.reader.dto.TypedTsVariant;
import com.warhex.er.generator.reader.dto.UoPData;
import com.warhex.er.generator.reader.dto.UoPModelData;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.FileResourceLoader;

import java.io.FileWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Drives the IDL generation phase (Step 1) of the Entity Reactor build pipeline.
 *
 * <h2>Template discovery</h2>
 * The pipeline is given a <em>template root directory</em> — typically
 * {@code <install>/templates/entity-reactor-idl/} or
 * {@code <install>/templates/data-model-idl/} — and recursively scans it for
 * {@code .vtl} files using {@link TemplateScanner}.  For each template found
 * a {@link TemplateMetadata} record is parsed from the file's header comments.
 *
 * <h2>Rendering strategy</h2>
 * Two strategies are applied based on header directives:
 *
 * <dl>
 *   <dt>Per-model (default)</dt>
 *   <dd>The template is rendered once.  Output filename = template basename
 *       with {@code .vtl} replaced by {@code .idl}, unless overridden by
 *       {@code ## @filename <expr>}.  The Velocity context contains
 *       {@code $model} and module-nesting helpers.</dd>
 *
 *   <dt>Per-entity ({@code ## @foreach entity})</dt>
 *   <dd>The template is rendered once per {@link EntityDescriptor} in the
 *       model.  Each iteration binds {@code $entity}, {@code $structName},
 *       {@code $idlGuard}, {@code $idlSourcePath}, and
 *       {@code $idlQualifiedPath} in addition to the base context.  The
 *       output filename defaults to {@code ${structName}.idl} but may be
 *       overridden by {@code ## @filename <expr>}.</dd>
 * </dl>
 *
 * <h2>Output mirroring</h2>
 * The relative path from the template root to each {@code .vtl} file is
 * mirrored under the output root; only the file extension changes.
 *
 * <p>Example: template root {@code templates/entity-reactor-idl/}, output root
 * {@code out/IDL/}:
 * <pre>
 *   templates/entity-reactor-idl/EntityReactor/EntityCrudRequest.vtl
 *       -&gt; out/IDL/EntityReactor/EntityCrudRequest.idl
 *
 *   templates/entity-reactor-idl/Entities/entity.vtl  (with @foreach entity)
 *       -&gt; out/IDL/Entities/TrackEntity.idl
 *       -&gt; out/IDL/Entities/ThreatEntity.idl
 *       -&gt; ...
 * </pre>
 *
 * <h2>Velocity engine</h2>
 * A dedicated {@link VelocityEngine} is configured with a
 * {@link FileResourceLoader} rooted at the template root directory, so
 * template paths passed to {@link VelocityEngine#getTemplate(String)} are
 * relative to that root.
 */
public class IdlGeneratorPipeline {

    private static final Logger LOG = Logger.getLogger(IdlGeneratorPipeline.class.getName());

    private final Path templateRoot;

    /**
     * @param templateRoot root directory containing the IDL {@code .vtl} templates;
     *                     must exist and be a directory.
     */
    public IdlGeneratorPipeline(Path templateRoot) {
        this.templateRoot = templateRoot;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Generates all IDL artifacts for {@code model} under {@code outputRoot}.
     *
     * <p>Templates with {@code ## @foreach uop} are skipped (a warning is logged)
     * because no {@link UoPModelData} has been supplied.  Use
     * {@link #generate(EntityModel, UoPModelData, Path)} when UoP-aware generation
     * is required.
     *
     * @param model      the populated entity model
     * @param outputRoot root directory for IDL output (e.g., {@code out/IDL/})
     * @throws Exception on any I/O or template processing error
     */
    public void generate(EntityModel model, Path outputRoot) throws Exception {
        generateInternal(model, null, outputRoot);
    }

    /**
     * Generates all IDL artifacts for {@code model} and {@code uoPModel} under
     * {@code outputRoot}.
     *
     * <p>In addition to the standard {@code @foreach entity} and
     * {@code @foreach nested_struct} strategies, this overload also handles
     * {@code ## @foreach uop}: for each {@link UoPData} in {@code uoPModel} the
     * template is rendered once into a subdirectory named after the UoP
     * ({@code <outputRoot>/<relativeDir>/}) — duplicates across UoPs are skipped.
     *
     * @param model      the populated entity model (module context, struct names, …)
     * @param uoPModel   UoP model carrying the per-UoP connection data
     * @param outputRoot root directory for IDL output
     * @throws Exception on any I/O or template processing error
     */
    public void generate(EntityModel model, UoPModelData uoPModel, Path outputRoot)
            throws Exception {
        generateInternal(model, uoPModel, outputRoot);
    }

    // -----------------------------------------------------------------------
    // Internal dispatch
    // -----------------------------------------------------------------------

    private void generateInternal(EntityModel model,
                                  UoPModelData uoPModel,
                                  Path outputRoot) throws Exception {
        LOG.info("IDL generation — template root : " + templateRoot);
        LOG.info("IDL generation — output root   : " + outputRoot);

        VelocityEngine velocity = buildVelocityEngine();
        List<TemplateMetadata> templates = new TemplateScanner(templateRoot).scan();

        for (TemplateMetadata meta : templates) {
            if (meta.isForeachEntity()) {
                renderForEachEntity(meta, model, outputRoot, velocity);
            } else if (meta.isForeachNestedStruct()) {
                renderForEachNestedStruct(meta, model, outputRoot, velocity);
            } else if (meta.isForeachUoP()) {
                if (uoPModel != null) {
                    renderForEachUoP(meta, model, uoPModel, outputRoot, velocity);
                } else {
                    LOG.warning("Skipping @foreach uop template (no UoPModelData provided): "
                            + meta);
                }
            } else if (meta.isForeachUoPConnection()) {
                if (uoPModel != null) {
                    renderForEachUoPConnection(meta, model, uoPModel, outputRoot, velocity);
                } else {
                    LOG.warning("Skipping @foreach uop_connection template "
                            + "(no UoPModelData provided): " + meta);
                }
            } else {
                renderOnce(meta, model, outputRoot, velocity);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rendering — per-model
    // -----------------------------------------------------------------------

    private void renderOnce(TemplateMetadata meta,
                            EntityModel model,
                            Path outputRoot,
                            VelocityEngine velocity) throws Exception {

        VelocityContext ctx = baseContext(model);

        String outFilename = meta.getFilenameExpression()
                .map(expr -> evaluateFilenameExpression(expr, ctx))
                .orElse(meta.defaultOutputFilename());

        Path outDir = outputRoot.resolve(meta.relativeOutputDirectory());
        Files.createDirectories(outDir);
        renderTemplate(templateRelPath(meta), ctx, outDir.resolve(outFilename), velocity);
    }

    // -----------------------------------------------------------------------
    // Rendering — per-entity
    // -----------------------------------------------------------------------

    private void renderForEachEntity(TemplateMetadata meta,
                                     EntityModel model,
                                     Path outputRoot,
                                     VelocityEngine velocity) throws Exception {

        Path outDir = outputRoot.resolve(meta.relativeOutputDirectory());
        Files.createDirectories(outDir);

        for (EntityDescriptor entity : model.getEntities()) {
            VelocityContext ctx = entityContext(model, entity);

            String outFilename = meta.getFilenameExpression()
                    .map(expr -> evaluateFilenameExpression(expr, ctx))
                    .orElse(model.structNameFor(entity.getSimpleName()) + ".idl");

            Path outFile = outDir.resolve(outFilename);
            Files.createDirectories(outFile.getParent());
            renderTemplate(templateRelPath(meta), ctx, outFile, velocity);
        }
    }

    // -----------------------------------------------------------------------
    // Rendering — per-nested-struct
    // -----------------------------------------------------------------------

    private void renderForEachNestedStruct(TemplateMetadata meta,
                                           EntityModel model,
                                           Path outputRoot,
                                           VelocityEngine velocity) throws Exception {

        Path outDir = outputRoot.resolve(meta.relativeOutputDirectory());
        Files.createDirectories(outDir);

        for (FieldDescriptor nestedStruct : model.getNestedStructTypes()) {
            VelocityContext ctx = nestedStructContext(model, nestedStruct);

            String outFilename = meta.getFilenameExpression()
                    .map(expr -> evaluateFilenameExpression(expr, ctx))
                    .orElse(nestedStruct.getIdlType() + ".idl");

            Path outFile = outDir.resolve(outFilename);
            Files.createDirectories(outFile.getParent());
            renderTemplate(templateRelPath(meta), ctx, outFile, velocity);
        }
    }

    // -----------------------------------------------------------------------
    // Rendering — per-UoP
    // -----------------------------------------------------------------------

    /**
     * Renders {@code meta} once per {@link UoPData} in {@code uoPModel}.
     *
     * <p>Each UoP's output is placed in its own subdirectory:
     * <pre>
     *   outputRoot / &lt;relativeTemplateDir&gt; / &lt;UoPName&gt; / &lt;filename&gt;
     * </pre>
     *
     * <p>The Velocity context contains {@code $uop} ({@link UoPData}) and
     * {@code $uopName} (the UoP's simple name) in addition to the standard
     * base-context variables ({@code $model}, module openers/closers, etc.).
     *
     * <p>Default output filename: {@code <UoPName>_TypedTS.idl} unless overridden
     * by a {@code ## @filename} directive.
     */
    private void renderForEachUoP(TemplateMetadata meta,
                                  EntityModel model,
                                  UoPModelData uoPModel,
                                  Path outputRoot,
                                  VelocityEngine velocity) throws Exception {

        Path templateRelDir = meta.relativeOutputDirectory();
        // All UoPs share a common output directory; duplicates are skipped.
        Path outDir = outputRoot.resolve(templateRelDir);
        Files.createDirectories(outDir);

        for (UoPData uoP : uoPModel.getUoPs()) {
            VelocityContext ctx = uoPContext(model, uoP);

            String outFilename = meta.getFilenameExpression()
                    .map(expr -> evaluateFilenameExpression(expr, ctx))
                    .orElse(uoP.getName() + "_TypedTS.idl");

            Path outFile = outDir.resolve(outFilename);
            if (Files.exists(outFile)) {
                LOG.fine("  Skipping duplicate TSS IDL (already written): " + outFile);
                continue;
            }
            renderTemplate(templateRelPath(meta), ctx, outFile, velocity);
        }
    }

    // -----------------------------------------------------------------------
    // Rendering — per-(UoP, connection)
    // -----------------------------------------------------------------------

    /**
     * Renders {@code meta} once per {@link ConnectionData} in every
     * {@link UoPData} in {@code uoPModel}.
     *
     * <p>Output directory: {@code outputRoot / <relativeTemplateDir> / <UoPName> /}
     *
     * <p><strong>Blank-output skipping.</strong> The template body is rendered to a
     * string <em>before</em> the output filename is evaluated.  If the rendered
     * string is blank the file is silently discarded.  This lets templates guard on
     * the connection variant at the top with {@code #stop}:
     * <pre>
     *   #if ($conn.typedTsVariant.name() != "STANDARD")
     *   #stop
     *   #end
     *   ... real content ...
     * </pre>
     *
     * <p>The {@code @filename} expression is evaluated only for non-blank renders,
     * which is why expressions that reference {@code $conn.responseMessageType.name}
     * are safe even when that field is {@code null} for pub/sub connections.
     */
    private void renderForEachUoPConnection(TemplateMetadata meta,
                                            EntityModel model,
                                            UoPModelData uoPModel,
                                            Path outputRoot,
                                            VelocityEngine velocity) throws Exception {

        Path templateRelDir = meta.relativeOutputDirectory();
        // All UoPs share a common output directory; duplicates are skipped.
        Path outDir = outputRoot.resolve(templateRelDir);
        Files.createDirectories(outDir);

        for (UoPData uoP : uoPModel.getUoPs()) {
            for (ConnectionData conn : uoP.getConnections()) {
                VelocityContext ctx = uoPConnectionContext(model, uoP, conn);

                // Render to string first — template uses #stop for the wrong variant,
                // producing an empty string that we detect and skip here.
                StringWriter sw = new StringWriter();
                velocity.getTemplate(templateRelPath(meta), "UTF-8").merge(ctx, sw);
                String rendered = sw.toString();

                if (rendered.isBlank()) {
                    LOG.fine("  Skipping blank render: " + uoP.getName()
                             + "/" + conn.getName() + " <- " + meta);
                    continue;
                }

                // Evaluate @filename only after confirming non-blank output —
                // safe to reference $conn.responseMessageType.name here.
                String outFilename = meta.getFilenameExpression()
                        .map(expr -> evaluateFilenameExpression(expr, ctx))
                        .orElse(conn.getName() + ".idl");

                Path outFile = outDir.resolve(outFilename);
                Files.createDirectories(outFile.getParent());
                if (Files.exists(outFile)) {
                    LOG.fine("  Skipping duplicate TSS IDL (already written): " + outFile);
                    continue;
                }
                LOG.info("  " + templateRelPath(meta) + " -> " + outFile);
                try (Writer writer = new FileWriter(outFile.toFile())) {
                    writer.write(rendered);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Velocity context builders
    // -----------------------------------------------------------------------

    private VelocityContext baseContext(EntityModel model) {
        VelocityContext ctx = new VelocityContext();
        ctx.put("model", model);

        List<String> dmSegs = model.getIdlModuleSegments();
        ctx.put("moduleOpeners",  buildOpeners(dmSegs));
        ctx.put("moduleClosers",  buildClosers(dmSegs));
        ctx.put("contentIndent",  "  ".repeat(dmSegs.size()));

        List<String> tssSegs = model.getTssIdlModuleSegments();
        ctx.put("tssModuleOpeners", buildOpeners(tssSegs));
        ctx.put("tssModuleClosers", buildClosers(tssSegs));
        ctx.put("tssContentIndent", "  ".repeat(tssSegs.size()));

        return ctx;
    }

    private VelocityContext nestedStructContext(EntityModel model, FieldDescriptor nestedStruct) {
        VelocityContext ctx = baseContext(model);

        String typeName   = nestedStruct.getIdlType();
        String idlGuard   = model.getIdlModuleGuardPrefix()
                            + "_" + typeName.toUpperCase() + "_IDL";
        String sourcePath = model.sourcePathFor("Entities/" + typeName + ".idl");

        ctx.put("nestedStruct",      nestedStruct);
        ctx.put("idlGuard",          idlGuard);
        ctx.put("idlSourcePath",     sourcePath);
        ctx.put("nestedIdlFilePath", model.sourcePathFor(typeName + ".idl"));

        return ctx;
    }

    private VelocityContext entityContext(EntityModel model, EntityDescriptor entity) {
        VelocityContext ctx = baseContext(model);

        String structName = model.structNameFor(entity.getSimpleName());
        String idlGuard   = model.getIdlModuleGuardPrefix()
                            + "_" + structName.toUpperCase() + "_IDL";
        String sourcePath = model.sourcePathFor("Entities/" + structName + ".idl");
        String qualPath   = model.getIdlModule() + "." + entity.getSimpleName();

        ctx.put("entity",           entity);
        ctx.put("structName",       structName);
        ctx.put("idlGuard",         idlGuard);
        ctx.put("idlSourcePath",    sourcePath);
        ctx.put("idlFilePath",      model.sourcePathFor(structName + ".idl"));
        ctx.put("idlQualifiedPath", qualPath);

        return ctx;
    }

    private VelocityContext uoPContext(EntityModel model, UoPData uoP) {
        VelocityContext ctx = baseContext(model);
        ctx.put("uop",     uoP);
        ctx.put("uopName", uoP.getName());
        return ctx;
    }

    private VelocityContext uoPConnectionContext(EntityModel model,
                                                 UoPData uoP,
                                                 ConnectionData conn) {
        VelocityContext ctx = uoPContext(model, uoP);
        ctx.put("conn",     conn);
        ctx.put("connName", conn.getName());

        // FACE Technical Standard 3.2 §4.8.4.1 req 3/7: the created Typed module is
        //     FACE::TSS::<UOP_MODEL_NAME>::<DATATYPE_TYPE>::TypedTS
        // where UOP_MODEL_NAME is "the name of the root UoPModel in which
        // DATATYPE_TYPE is a member" — the model that DEFINES the type, not the
        // model whose UoP declares the connection.  So everything below is derived
        // from the message type's own idlModule, with $model used only as a fallback
        // for an unstamped type.
        //
        // The <DATATYPE_TYPE> level is produced by the template-instantiation
        // statement itself ("module ::FACE::TSS::Typed<Money_t> Money;"), so the
        // module openers stop at <UOP_MODEL_NAME>.  Opening a fourth module here
        // would yield FACE::TSS::<MODEL>::<TYPE>::<TYPE>::TypedTS.
        String dmModule  = typeIdlModule(conn.getMessageType(), model.getIdlModule());
        String tssModule = dmModule.contains(".DM.")
                ? dmModule.replace(".DM.", ".TSS.")
                : dmModule + ".TSS";

        List<String> tssSegs = Arrays.asList(tssModule.split("\\."));
        ctx.put("connTssModuleOpeners", buildOpeners(tssSegs));
        ctx.put("connTssModuleClosers", buildClosers(tssSegs));
        ctx.put("connTssContentIndent", "  ".repeat(tssSegs.size()));

        ctx.put("connTssModPath",     tssModule.replace('.', '/'));
        ctx.put("connTssGuardPrefix", tssModule.replace('.', '_').toUpperCase(Locale.ROOT));
        ctx.put("connDmModPath",      dmModule.replace('.', '/'));
        ctx.put("connDmPrefix",       "::" + dmModule.replace(".", "::") + "::");

        // EXTENDED (client/server): the response type may live in a different model,
        // so it gets its own include path and qualified prefix.
        if (conn.getTypedTsVariant() == TypedTsVariant.EXTENDED
                && conn.getResponseMessageType() != null) {
            String respDm = typeIdlModule(conn.getResponseMessageType(), dmModule);
            ctx.put("connRespDmModPath", respDm.replace('.', '/'));
            ctx.put("connRespDmPrefix",  "::" + respDm.replace(".", "::") + "::");
        }
        return ctx;
    }

    /**
     * Returns the IDL module that defines {@code type}, falling back to
     * {@code fallback} when the type is missing or was never stamped.
     */
    private static String typeIdlModule(
            com.warhex.er.generator.reader.dto.TssTypeData type, String fallback) {
        if (type != null) {
            String m = type.getIdlModule();
            if (m != null && !m.isBlank()) return m;
            LOG.warning("Type '" + type.getName() + "' has no defining UoPModel; "
                    + "falling back to '" + fallback + "'.");
        }
        return fallback;
    }

    // -----------------------------------------------------------------------
    // Filename expression evaluator
    // -----------------------------------------------------------------------

    /**
     * Evaluates a Velocity expression string (e.g., {@code "${structName}.idl"})
     * against the given context using {@link VelocityEngine#evaluate}.
     */
    private String evaluateFilenameExpression(String expression, VelocityContext ctx) {
        try {
            StringWriter sw = new StringWriter();
            // Reuse a minimal engine for inline evaluation.
            // VelocityEngine.evaluate() does not require the string to be a file.
            new VelocityEngine().evaluate(ctx, sw, "filename-expr", expression);
            return sw.toString().trim();
        } catch (Exception e) {
            LOG.warning("Could not evaluate @filename expression '" + expression
                        + "': " + e.getMessage() + " — using expression as literal.");
            return expression;
        }
    }

    // -----------------------------------------------------------------------
    // Module line builders
    // -----------------------------------------------------------------------

    private List<String> buildOpeners(List<String> segments) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            lines.add("  ".repeat(i) + "module " + segments.get(i) + " {");
        }
        return lines;
    }

    private List<String> buildClosers(List<String> segments) {
        List<String> lines = new ArrayList<>();
        for (int i = segments.size() - 1; i >= 0; i--) {
            lines.add("  ".repeat(i) + "}; // module " + segments.get(i));
        }
        return lines;
    }

    // -----------------------------------------------------------------------
    // Template rendering
    // -----------------------------------------------------------------------

    private String templateRelPath(TemplateMetadata meta) {
        // VelocityEngine with FileResourceLoader expects forward slashes.
        return meta.getRelativeTemplatePath().toString().replace('\\', '/');
    }

    private void renderTemplate(String templateRelPath,
                                VelocityContext ctx,
                                Path outFile,
                                VelocityEngine velocity) throws Exception {
        LOG.info("  " + templateRelPath + " -> " + outFile);
        Template template = velocity.getTemplate(templateRelPath, "UTF-8");
        try (Writer writer = new FileWriter(outFile.toFile())) {
            template.merge(ctx, writer);
        }
    }

    // -----------------------------------------------------------------------
    // Velocity engine factory
    // -----------------------------------------------------------------------

    private VelocityEngine buildVelocityEngine() {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "file");
        ve.setProperty("resource.loader.file.class",
                FileResourceLoader.class.getName());
        ve.setProperty("resource.loader.file.path",
                templateRoot.toAbsolutePath().toString());
        ve.setProperty("resource.loader.file.cache", false);
        ve.setProperty("space.gobbling", "lines");
        ve.init();
        return ve;
    }
}
