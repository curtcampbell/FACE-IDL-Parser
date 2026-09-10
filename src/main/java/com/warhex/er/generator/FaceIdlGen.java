package com.warhex.er.generator;

import com.warhex.er.generator.binding.LanguageBindingPipeline;
import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.idl.IdlGeneratorPipeline;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.parser.IdlDirectoryParser;
import com.warhex.er.generator.parser.IdlParseResult;
import com.warhex.er.generator.reader.dto.IdlModelData;
import com.warhex.er.generator.reader.dto.UoPModelData;
import com.warhex.er.generator.reader.face.FaceTemplateEntityReader;
import com.warhex.er.generator.reader.face.FaceTssReader;
import com.warhex.er.generator.reader.face.TssToEntityModelAdapter;
import com.warhex.er.generator.reader.face.FaceXmiDocument;
import com.warhex.er.generator.reader.face.PlatformDataModelIdlGenerator;
import com.warhex.er.generator.reader.EntityModelMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Entry point for the <strong>face-idl-gen</strong> tool.
 *
 * <p>Reads entity models (YAML, JSON, or {@code .face} XMI) and generates IDL artifacts.
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   face-idl-gen generate-entity-idl  YAML / .face → entity-reactor IDL
 *   face-idl-gen generate-tss-idl  .face → TSS data-model IDL + UoP TypedTS IDL
 *   face-idl-gen generate          End-to-end: IDL generation then language binding
 * </pre>
 *
 * <p>Run {@code face-idl-gen --help} or {@code face-idl-gen <subcommand> --help} for details.
 *
 * @see FaceIdlBinder
 * @see FaceToolUtils
 */
@Command(
    name        = "face-idl-gen",
    description = "FACE IDL generator — converts entity models to IDL artifacts.",
    mixinStandardHelpOptions = true,
    version     = "0.1.0-SNAPSHOT",
    subcommands = {
        FaceIdlGen.GenerateIdlCommand.class,
        FaceIdlGen.GenerateTssIdlCommand.class,
        FaceIdlGen.GenerateCommand.class,
        FaceIdlGen.ParseTssIdlCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class FaceIdlGen implements Runnable {

    private static final Logger LOG = Logger.getLogger(FaceIdlGen.class.getName());

    @Option(names = {"-v", "--verbose"},
            description = "Print full stack traces on errors.")
    boolean verbose;

    public static void main(String[] args) {
        FaceIdlGen app = new FaceIdlGen();
        CommandLine cmd = new CommandLine(app);

        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            CommandLine root = commandLine;
            while (root.getParent() != null) root = root.getParent();
            boolean v = ((FaceIdlGen) root.getCommand()).verbose;
            System.err.println(ex.getMessage());
            if (v) { System.err.println(); ex.printStackTrace(System.err); }
            return 1;
        });

        System.exit(cmd.execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    // -----------------------------------------------------------------------
    // Subcommand: generate-entity-idl
    // -----------------------------------------------------------------------

    @Command(
        name        = "generate-entity-idl",
        description = "Generate entity-reactor IDL from a YAML or .face model.",
        mixinStandardHelpOptions = true
    )
    static class GenerateIdlCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "MODEL",
                    description = "Path to the YAML, JSON, or .face model file.")
        private Path modelFile;

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root output directory. Created if absent.")
        private Path outputDir;

        @Option(names = {"--templates-dir", "-t"},
                description = "IDL template root directory. "
                            + "Defaults to <install>/templates/entity-reactor-idl/.")
        private Path templatesDir;

        @Option(names = {"--entities"},
                description = "Use uop:Template / uop:CompositeTemplate elements as the "
                            + "entity source for IDL generation. Reads from the '"
                            + FaceTemplateEntityReader.DEFAULT_GROUP_NAME
                            + "' group in the .face model. "
                            + "Only valid when MODEL is a .face file.")
        private boolean useTemplates;

        @Option(names = {"--entity-source"},
                paramLabel = "GROUP",
                description = "Named um:UoPModel group to use as template entity source. "
                            + "Implies --entities. "
                            + "Only valid when MODEL is a .face file.")
        private String entitySourceGroup;

        @Override
        public Integer call() throws Exception {
            Path tmplRoot = FaceToolUtils.resolveTemplateRoot(templatesDir, "entity-reactor-idl");
            LOG.info("generate-entity-idl: model=" + modelFile
                     + "  output=" + outputDir + "  templates=" + tmplRoot);

            EntityModel model = FaceToolUtils.resolveEntityModel(
                    modelFile, useTemplates, entitySourceGroup);

            new IdlGeneratorPipeline(tmplRoot)
                    .generate(model, outputDir.resolve("IDL/entity-reactor"));

            LOG.info("IDL generation complete.");
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: generate-tss-idl
    // -----------------------------------------------------------------------

    /**
     * Reads a {@code .face} XMI file and generates:
     * <ul>
     *   <li>{@code <output-dir>/data-model/} — one IDL struct per Template / CompositeTemplate</li>
     *   <li>{@code <output-dir>/data-model/FACE/TSS/} — TypedTS IDL (§4.8.4.1, one file per type)</li>
     * </ul>
     * Optionally compiles the data-model IDL into language bindings (Step 2).
     */
    @Command(
        name        = "generate-tss-idl",
        description = "Generate FACE TSS data-model IDL and UoP TypedTS IDL from a .face XMI file.",
        mixinStandardHelpOptions = true
    )
    static class GenerateTssIdlCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "FACE_FILE",
                    description = "Path to the .face XMI model file.")
        private Path faceFile;

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root output directory. IDL is emitted to "
                            + "<output-dir>/data-model/ (DM and TypedTS IDL merged per §4.8.4.1).")
        private Path outputDir;

        @Option(names = {"--templates-dir", "-t"},
                description = "TSS IDL template root. "
                            + "Defaults to <install>/templates/data-model-idl/.")
        private Path templatesDir;

        @Option(names = {"--face-idl-dir"},
                description = "FACE framework IDL root (face-idl/). "
                            + "Defaults to <install>/face-idl/.")
        private Path faceIdlDir;

        @Option(names = {"-I", "--include-path"},
                description = "Additional IDL include search directory (repeatable).")
        private List<Path> includePaths = new ArrayList<>();

        @Option(names = {"--idl-only"},
                description = "Generate IDL only; skip language binding generation.")
        private boolean idlOnly;

        @Option(names = {"--all-languages"}, description = "Generate C++, Java, Python, C#.")
        private boolean allLanguages;
        @Option(names = {"--all-face"},      description = "Generate C++ and Java (default).")
        private boolean allFace;
        @Option(names = {"--cpp"},    description = "Generate C++ bindings.")    private boolean genCpp;
        @Option(names = {"--java"},   description = "Generate Java bindings.")   private boolean genJava;
        @Option(names = {"--python"}, description = "Generate Python bindings.") private boolean genPython;
        @Option(names = {"--csharp"},
                description = "Generate C# bindings (non-FACE; excluded from --all-face).")
        private boolean genCsharp;

        @Override
        public Integer call() throws Exception {
            LOG.info("generate-tss-idl: face-file=" + faceFile + "  output=" + outputDir);

            // Read ALL uop:UoPModel elements — a single .face file may contain many.
            List<UoPModelData> allModels = new FaceTssReader().readAll(faceFile);
            LOG.info("UoPModel count: " + allModels.size());

            Path tmplRoot = FaceToolUtils.resolveTemplateRoot(templatesDir, "data-model-idl");
            Files.createDirectories(outputDir);

            for (UoPModelData uoPModel : allModels) {
                // Each um contributes whichever half of the output it owns:
                //   template ums  -> data-model IDL for the types they DEFINE (§J.8)
                //   the UoPs um   -> TypedTS IDL for the connections its UoPs declare
                //                    (§4.8.4.1), each keyed on the type's defining model
                // A um with neither is nothing to generate.
                boolean hasTypes = uoPModel.getPlatformTypes() != null
                        && !uoPModel.getPlatformTypes().isEmpty();
                boolean hasUoPs  = uoPModel.getUoPs() != null
                        && !uoPModel.getUoPs().isEmpty();
                if (!hasTypes && !hasUoPs) {
                    LOG.info("Skipping model '" + uoPModel.getModelName()
                            + "' — defines no templates and owns no UoPs.");
                    continue;
                }
                IdlModelData idlData     = new TssToEntityModelAdapter().adapt(uoPModel);
                EntityModel  entityModel = new EntityModelMapper().map(idlData);
                new IdlGeneratorPipeline(tmplRoot).generate(entityModel, uoPModel,
                        outputDir.resolve("idl"));
                LOG.info("TSS IDL generated for model: " + uoPModel.getModelName());
            }
            LOG.info("TSS IDL generation complete: " + outputDir);

            // Generate platform typedef / enum IDL for all dm:DataModel elements
            FaceXmiDocument faceDocForPlatform = new FaceXmiDocument(faceFile);
            new PlatformDataModelIdlGenerator(faceDocForPlatform).generate(outputDir.resolve("idl/data-model"));
            LOG.info("Platform data-model typedef/enum IDL generation complete.");

            // Language binding is opt-in: only run when at least one language flag
            // (--cpp, --java, --all-face, --all-languages, --csharp, --python) is set.
            // Passing --idl-only also suppresses it.
            boolean anyLanguage = allLanguages || allFace || genCpp || genJava || genPython || genCsharp;
            if (idlOnly || !anyLanguage) {
                LOG.info("Skipping language binding (no language flags set or --idl-only).");
                return 0;
            }

            Path dataModelIdlDir = outputDir.resolve("idl/data-model");
            List<Path> searchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
            searchDirs.add(outputDir.resolve("idl/data-model")); // so #include <FACE/DM/...> resolves
            LOG.info("Binding data-model IDL from " + dataModelIdlDir);
            IdlParseResult result = new IdlDirectoryParser(searchDirs).parse(dataModelIdlDir);
            List<LanguageMapper> mappers =
                    FaceToolUtils.buildMappers(allLanguages, allFace, genCpp, genJava, genPython, genCsharp);
            LOG.info("Active mappers: " + mappers.stream().map(LanguageMapper::languageName).toList());
            new LanguageBindingPipeline(mappers).generateIntoSubdir(result, outputDir, "face-model");
            LOG.info("face-model data-model language binding complete.");

            // TypedTS IDL is now emitted into idl/data-model/FACE/TSS/... alongside DM IDL,
            // so the single IdlDirectoryParser pass above picks it up automatically.
            LOG.info("TSS language binding complete (merged into data-model pass).");
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: generate  (Steps 1 + 2 combined)
    // -----------------------------------------------------------------------

    /**
     * Convenience command that runs entity-reactor IDL generation then language binding
     * end-to-end.  When {@code --face-file} is also supplied, the TSS pipeline runs
     * afterwards, writing output into {@code <output-dir>/TSS/}.
     *
     * <p>For finer control run {@code generate-entity-idl} / {@code generate-tss-idl} and
     * {@code face-idl-binder bind} separately.
     */
    @Command(
        name        = "generate",
        description = "Run IDL generation then language binding end-to-end. "
                    + "Add --face-file to also run the TSS pipeline into <output-dir>/TSS/.",
        mixinStandardHelpOptions = true
    )
    static class GenerateCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "MODEL",
                    description = "Path to the YAML, JSON, or .face model file.")
        private Path modelFile;

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root output directory. Created if absent.")
        private Path outputDir;

        @Option(names = {"--templates-dir", "-t"},
                description = "Entity-reactor IDL template root. "
                            + "Defaults to <install>/templates/entity-reactor-idl/.")
        private Path templatesDir;

        @Option(names = {"--face-file"},
                description = "Path to the .face XMI model file. When supplied, also generates "
                            + "TSS data-model IDL and UoP TypedTS IDL into <output-dir>/TSS/.")
        private Path faceFile;

        @Option(names = {"--face-idl-dir"},
                description = "FACE framework IDL root. Defaults to <install>/face-idl/.")
        private Path faceIdlDir;

        @Option(names = {"-I", "--include-path"},
                description = "Additional IDL include search directory (repeatable).")
        private List<Path> includePaths = new ArrayList<>();

        @Option(names = {"--entities"},
                description = "Use uop:Template / uop:CompositeTemplate elements as the "
                            + "entity source for IDL generation. Reads from the '"
                            + FaceTemplateEntityReader.DEFAULT_GROUP_NAME
                            + "' group in the .face model. "
                            + "Only valid when MODEL is a .face file.")
        private boolean useTemplates;

        @Option(names = {"--entity-source"},
                paramLabel = "GROUP",
                description = "Named um:UoPModel group to use as template entity source. "
                            + "Implies --entities. "
                            + "Only valid when MODEL is a .face file.")
        private String entitySourceGroup;

        @Option(names = {"--all-languages"}, description = "Generate C++, Java, Python, C#.")
        private boolean allLanguages;
        @Option(names = {"--all-face"},      description = "Generate C++ and Java (default).")
        private boolean allFace;
        @Option(names = {"--cpp"},    description = "Generate C++ bindings.")    private boolean genCpp;
        @Option(names = {"--java"},   description = "Generate Java bindings.")   private boolean genJava;
        @Option(names = {"--python"}, description = "Generate Python bindings.") private boolean genPython;
        @Option(names = {"--csharp"},
                description = "Generate C# bindings (non-FACE; excluded from --all-face).")
        private boolean genCsharp;

        @Override
        public Integer call() throws Exception {
            EntityModel model = FaceToolUtils.resolveEntityModel(
                    modelFile, useTemplates, entitySourceGroup);

            // ── Entity-reactor pipeline ──────────────────────────────────────
            Path tmplRoot  = FaceToolUtils.resolveTemplateRoot(templatesDir, "entity-reactor-idl");
            Path idlOutDir = outputDir.resolve("IDL/entity-reactor");
            new IdlGeneratorPipeline(tmplRoot).generate(model, idlOutDir);
            LOG.info("Step 1 (IDL generation) complete: " + idlOutDir);

            List<Path> searchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
            IdlParseResult result = new IdlDirectoryParser(searchDirs).parse(idlOutDir);
            List<LanguageMapper> mappers =
                    FaceToolUtils.buildMappers(allLanguages, allFace, genCpp, genJava, genPython, genCsharp);
            LOG.info("Active mappers: " + mappers.stream().map(LanguageMapper::languageName).toList());
            for (LanguageMapper mapper : mappers) {
                mapper.mapDirect(result, outputDir.resolve(mapper.outputSubdirectory()).resolve("entity-reactor"));
            }
            LOG.info("Step 2 (language binding) complete.");

            // ── TSS pipeline (optional) ──────────────────────────────────────
            if (faceFile != null) {
                LOG.info("TSS pipeline: face-file=" + faceFile);

                List<UoPModelData> allTssModels = new FaceTssReader().readAll(faceFile);
                LOG.info("UoPModel count: " + allTssModels.size());

                Path tssTmplRoot = FaceToolUtils.resolveTemplateRoot(null, "data-model-idl");
                Path tssOutDir   = outputDir.resolve("TSS");
                Files.createDirectories(tssOutDir);

                for (UoPModelData uoPModel : allTssModels) {
                    if (uoPModel.getPlatformTypes() == null || uoPModel.getPlatformTypes().isEmpty()) {
                        LOG.info("Skipping model '" + uoPModel.getModelName()
                                + "' — no template types (not a template model).");
                        continue;
                    }
                    IdlModelData tssIdlData = new TssToEntityModelAdapter().adapt(uoPModel);
                    EntityModel  tssModel   = new EntityModelMapper().map(tssIdlData);
                    new IdlGeneratorPipeline(tssTmplRoot).generate(tssModel, uoPModel,
                            tssOutDir.resolve("idl"));
                    LOG.info("TSS Step 1 complete for model: " + uoPModel.getModelName());
                }
                LOG.info("TSS Step 1 complete: " + tssOutDir);

                List<Path> tssSearchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
                IdlParseResult tssResult =
                        new IdlDirectoryParser(tssSearchDirs).parse(tssOutDir.resolve("idl/data-model"));
                List<LanguageMapper> tssMappers =
                        FaceToolUtils.buildMappers(allLanguages, allFace, genCpp, genJava, genPython, genCsharp);
                new LanguageBindingPipeline(tssMappers).generateIntoSubdir(tssResult, tssOutDir, "face-model");
                LOG.info("TSS Step 2 (data-model binding) complete.");

                // TypedTS IDL is now emitted into idl/data-model/FACE/TSS/... alongside DM IDL;
                // the IdlDirectoryParser pass above picks it up automatically.
                LOG.info("TSS Step 2 complete (TypedTS binding merged into data-model pass).");
            }

            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: parse-tss-idl
    // -----------------------------------------------------------------------

    /**
     * Accepts an IDL directory and language-binding flags, parses all {@code .idl}
     * files in that directory (recursively), and runs the built-in
     * {@link LanguageBindingPipeline} to produce language-binding output directly
     * under {@code <output-dir>/<lang>/}.
     *
     * <p>Unlike {@code generate-tss-idl}, this subcommand does <em>not</em> require
     * a {@code .face} XMI model file and does <em>not</em> require a template
     * directory — it works purely from IDL files already on disk.
     */
    @Command(
        name        = "parse-tss-idl",
        description = "Parse IDL files from a directory and generate language bindings "
                    + "without a .face model file or template directory.",
        mixinStandardHelpOptions = true
    )
    static class ParseTssIdlCommand implements Callable<Integer> {

        @Option(names = {"--idl-dir", "-i"}, required = true,
                description = "Directory of IDL files to parse (recursive). "
                            + "Replaces the role of the .face model file.")
        private Path idlDir;

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root output directory. Created if absent. "
                            + "Language output lands under <output-dir>/<lang>/.")
        private Path outputDir;

        @Option(names = {"--face-idl-dir"},
                description = "FACE framework IDL root (face-idl/). "
                            + "Defaults to <install>/face-idl/.")
        private Path faceIdlDir;

        @Option(names = {"-I", "--include-path"},
                description = "Additional IDL include search directory (repeatable).")
        private List<Path> includePaths = new ArrayList<>();

        @Option(names = {"--all-languages"}, description = "Generate C++, Java, Python, C#.")
        private boolean allLanguages;
        @Option(names = {"--all-face"},      description = "Generate C++ and Java (default).")
        private boolean allFace;
        @Option(names = {"--cpp"},    description = "Generate C++ bindings.")    private boolean genCpp;
        @Option(names = {"--java"},   description = "Generate Java bindings.")   private boolean genJava;
        @Option(names = {"--python"}, description = "Generate Python bindings.") private boolean genPython;
        @Option(names = {"--csharp"},
                description = "Generate C# bindings (non-FACE; excluded from --all-face).")
        private boolean genCsharp;

        @Override
        public Integer call() throws Exception {
            LOG.info("parse-tss-idl: idl-dir=" + idlDir + "  output=" + outputDir);

            List<Path> searchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
            LOG.info("Parsing IDL from " + idlDir);
            IdlParseResult result = new IdlDirectoryParser(searchDirs).parse(idlDir);

            Files.createDirectories(outputDir);
            List<LanguageMapper> mappers =
                    FaceToolUtils.buildMappers(allLanguages, allFace, genCpp, genJava, genPython, genCsharp);
            LOG.info("Active mappers: " + mappers.stream().map(LanguageMapper::languageName).toList());
            new LanguageBindingPipeline(mappers).generate(result, outputDir);
            LOG.info("parse-tss-idl binding complete.");
            return 0;
        }
    }
}
