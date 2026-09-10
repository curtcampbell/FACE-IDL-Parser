package com.warhex.er.generator;

import com.warhex.er.generator.codegen.context.ContextAssembler;
import com.warhex.er.generator.codegen.manifest.CodeGenManifest;
import com.warhex.er.generator.codegen.manifest.CodeGenManifestLoader;
import com.warhex.er.generator.codegen.pipeline.CodeGenPipeline;
import com.warhex.er.generator.parser.IdlDirectoryParser;
import com.warhex.er.generator.parser.IdlParseResult;
import com.warhex.er.generator.reader.dto.UoPModelData;
import com.warhex.er.generator.reader.face.FaceTssReader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Entry point for the <strong>face-codegen</strong> tool.
 *
 * <p>Consumes IDL files (and optionally a {@code .face} model) plus a
 * user-supplied template directory and generates concrete implementation
 * code — TSS transport services, UoP skeletons, or any other project-specific
 * artifact.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   IDL directory ──► IdlDirectoryParser ──► IdlParseResult
 *   .face file    ──► FaceTssReader      ──► UoPModelData   (optional)
 *   template-dir  ──► codegen.yaml       ──► CodeGenManifest
 *                 └──► *.vm templates
 *                                  ↓
 *                          CodeGenPipeline
 *                                  ↓
 *                          generated files on disk
 * </pre>
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   face-codegen generate   IDL [+ .face model] + templates → implementation code
 * </pre>
 *
 * <p>Run {@code face-codegen --help} or {@code face-codegen generate --help}
 * for details.
 *
 * @see FaceIdlGen
 * @see FaceIdlBinder
 */
@Command(
    name        = "face-codegen",
    description = "FACE code generator — generates implementation code from IDL and templates.",
    mixinStandardHelpOptions = true,
    version     = "0.1.0-SNAPSHOT",
    subcommands = {
        FaceCodeGen.GenerateCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class FaceCodeGen implements Runnable {

    private static final Logger LOG = Logger.getLogger(FaceCodeGen.class.getName());

    @Option(names = {"-v", "--verbose"},
            description = "Print full stack traces on errors.")
    boolean verbose;

    public static void main(String[] args) {
        FaceCodeGen app = new FaceCodeGen();
        CommandLine cmd = new CommandLine(app);

        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            CommandLine root = commandLine;
            while (root.getParent() != null) root = root.getParent();
            boolean v = ((FaceCodeGen) root.getCommand()).verbose;
            System.err.println("ERROR: " + ex.getMessage());
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
    // Subcommand: generate
    // -----------------------------------------------------------------------

    /**
     * Generates implementation code from IDL + optional {@code .face} model
     * using user-supplied Velocity templates.
     *
     * <p>The {@code --template-dir} must contain the {@code .vm} template files.
     * A {@code codegen.yaml} manifest is optional: when omitted (or absent from
     * the template directory), all generation is driven by {@code ##!} directives
     * embedded in the template files themselves.
     *
     * <p>When {@code --face-file} is provided, the UoP model is available in
     * templates as {@code $model} and {@code $uops}.  It is required for
     * {@code for_each: UOP} and {@code for_each: CONNECTION} manifest entries.
     */
    @Command(
        name        = "generate",
        description = "Generate implementation code from IDL and user-provided templates.",
        mixinStandardHelpOptions = true
    )
    static class GenerateCommand implements Callable<Integer> {

        @Option(names = {"--idl-dir", "-i"}, required = true,
                description = "Directory containing generated IDL files (output of face-idl-gen).")
        private Path idlDir;

        @Option(names = {"--face-file", "-f"},
                description = "Optional .face XMI model file. "
                            + "When provided, enables $model and $uops in templates "
                            + "and is required for UOP / CONNECTION scope entries.")
        private Path faceFile;

        @Option(names = {"--face-idl-dir"},
                description = "FACE framework IDL root (face-idl/). "
                            + "Defaults to <install>/face-idl/.")
        private Path faceIdlDir;

        @Option(names = {"-I", "--include-path"},
                description = "Additional IDL include search directory (repeatable).")
        private List<Path> includePaths = new ArrayList<>();

        @Option(names = {"--template-dir", "-t"}, required = true,
                description = "Directory containing *.vm template files.")
        private Path templateDir;

        @Option(names = {"-m", "--manifest"},
                description = "Path to codegen.yaml manifest. "
                            + "When omitted, looks for codegen.yaml inside --template-dir; "
                            + "if that file is also absent, runs in directive-only mode "
                            + "(all generation driven by ##! template directives).")
        private Path manifestFile;

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root directory for generated output. Created if absent.")
        private Path outputDir;

        @Override
        public Integer call() throws Exception {
            // ── 1. Parse IDL ────────────────────────────────────────────────
            List<Path> searchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
            LOG.info("generate: idl-dir=" + idlDir + "  search-dirs=" + searchDirs);
            IdlParseResult parseResult = new IdlDirectoryParser(searchDirs).parse(idlDir);
            LOG.info("IDL parsed: " + parseResult.mergedSpec().definitions().size()
                    + " top-level definitions.");

            // ── 2. Load optional .face model ────────────────────────────────
            UoPModelData model = null;
            if (faceFile != null) {
                LOG.info("generate: face-file=" + faceFile);
                model = new FaceTssReader().read(faceFile);
                LOG.info("UoP model loaded: " + model.getModelName()
                        + " (" + model.getUoPs().size() + " UoPs)");
            } else {
                LOG.info("No --face-file provided; $model and $uops will be null/empty.");
            }

            // ── 3. Load manifest (optional) ─────────────────────────────────
            // Explicit --manifest: load and fail loudly if not found.
            // No --manifest: probe templateDir/codegen.yaml; if absent, run in
            // directive-only mode (empty manifest, all generation via ##! headers).
            CodeGenManifest manifest;
            if (manifestFile != null) {
                LOG.info("Loading manifest (explicit): " + manifestFile);
                manifest = CodeGenManifestLoader.load(manifestFile);
            } else {
                Path defaultManifest = templateDir.resolve(CodeGenManifestLoader.MANIFEST_FILENAME);
                LOG.info("Manifest probe (default location): " + defaultManifest);
                manifest = CodeGenManifestLoader.loadOrEmpty(defaultManifest);
            }
            LOG.info("Manifest: " + manifest);

            // ── 4. Resolve optional language directory for $types ───────────
            Path languageDir = null;
            if (manifest.language_dir != null && !manifest.language_dir.isBlank()) {
                Path ld = Path.of(manifest.language_dir);
                languageDir = ld.isAbsolute() ? ld : templateDir.resolve(ld);
                LOG.info("Language dir: " + languageDir);
            }

            // ── 5. Build context assembler ──────────────────────────────────
            ContextAssembler assembler = ContextAssembler.build(parseResult, model, languageDir);

            // ── 6. Run pipeline ─────────────────────────────────────────────
            new CodeGenPipeline(manifest, templateDir, outputDir, assembler).run();

            LOG.info("face-codegen complete.");
            return 0;
        }
    }
}
