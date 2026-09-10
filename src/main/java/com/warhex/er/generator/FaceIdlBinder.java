package com.warhex.er.generator;

import com.warhex.er.generator.ast.IdlSpecification;
import com.warhex.er.generator.binding.LanguageBindingPipeline;
import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.parser.IdlDirectoryParser;
import com.warhex.er.generator.parser.IdlParseResult;
import com.warhex.er.generator.reader.ModelToIdlAdapter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Entry point for the <strong>face-idl-binder</strong> tool.
 *
 * <p>Consumes IDL files and generates FACE language bindings (C++, Java, Python, C#).
 * This is Step 2 of the FACE toolchain pipeline; Step 1 is handled by {@link FaceIdlGen}.
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   face-idl-binder bind   IDL directory or YAML model → language bindings
 * </pre>
 *
 * <p>Run {@code face-idl-binder --help} or {@code face-idl-binder bind --help} for details.
 *
 * @see FaceIdlGen
 * @see FaceToolUtils
 */
@Command(
    name        = "face-idl-binder",
    description = "FACE IDL binder — generates language bindings from IDL.",
    mixinStandardHelpOptions = true,
    version     = "0.1.0-SNAPSHOT",
    subcommands = {
        FaceIdlBinder.BindCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class FaceIdlBinder implements Runnable {

    private static final Logger LOG = Logger.getLogger(FaceIdlBinder.class.getName());

    @Option(names = {"-v", "--verbose"},
            description = "Print full stack traces on errors.")
    boolean verbose;

    public static void main(String[] args) {
        FaceIdlBinder app = new FaceIdlBinder();
        CommandLine cmd = new CommandLine(app);

        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            CommandLine root = commandLine;
            while (root.getParent() != null) root = root.getParent();
            boolean v = ((FaceIdlBinder) root.getCommand()).verbose;
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
    // Subcommand: bind
    // -----------------------------------------------------------------------

    /**
     * Generates FACE language bindings from an IDL directory or a YAML entity model.
     *
     * <p>When {@code --idl-dir} is provided the IDL files are parsed faithfully — exactly
     * what was written on disk is bound, with no model-level enrichment.  When {@code --model}
     * is provided instead the model is adapted directly to an {@link IdlSpecification} without
     * touching the filesystem (no TypedTS bindings are produced in this mode).
     *
     * <p>{@code --idl-dir} takes precedence when both are given.
     */
    @Command(
        name        = "bind",
        description = "Generate FACE language bindings from an IDL directory or YAML model.",
        mixinStandardHelpOptions = true
    )
    static class BindCommand implements Callable<Integer> {

        @Option(names = {"--idl-dir", "-i"},
                description = "Directory containing generated IDL files. "
                            + "Parsed faithfully; takes precedence over --model.")
        private Path idlDir;

        @Option(names = {"--model", "-m"},
                description = "Path to the YAML entity model file (DM only; no TypedTS).")
        private Path modelFile;

        @Option(names = {"--face-idl-dir"},
                description = "FACE framework IDL root (face-idl/). "
                            + "Defaults to <install>/face-idl/.")
        private Path faceIdlDir;

        @Option(names = {"-I", "--include-path"},
                description = "Additional IDL include search directory (repeatable). "
                            + "Searched after the including file's own directory and --face-idl-dir.")
        private List<Path> includePaths = new ArrayList<>();

        @Option(names = {"--output-dir", "-o"}, required = true,
                description = "Root output directory. Created if absent.")
        private Path outputDir;

        @Option(names = {"--all-languages"}, description = "Generate C++, Java, Python, C#.")
        private boolean allLanguages;
        @Option(names = {"--all-face"},
                description = "Generate C++ and Java (FACE standard). Default when no flag given.")
        private boolean allFace;
        @Option(names = {"--cpp"},    description = "Generate C++ bindings.")    private boolean genCpp;
        @Option(names = {"--java"},   description = "Generate Java bindings.")   private boolean genJava;
        @Option(names = {"--python"}, description = "Generate Python bindings.") private boolean genPython;
        @Option(names = {"--csharp"},
                description = "Generate C# bindings (non-FACE; excluded from --all-face).")
        private boolean genCsharp;

        @Override
        public Integer call() throws Exception {
            if (modelFile == null && idlDir == null) {
                System.err.println("ERROR: one of --model or --idl-dir is required.");
                return 1;
            }

            IdlParseResult result;
            if (idlDir != null) {
                List<Path> searchDirs = FaceToolUtils.buildSearchDirs(faceIdlDir, includePaths);
                LOG.info("bind: idl-dir=" + idlDir + "  search-dirs=" + searchDirs);
                result = new IdlDirectoryParser(searchDirs).parse(idlDir);
            } else {
                LOG.info("bind: model=" + modelFile + "  (DM only — no TypedTS)");
                IdlSpecification spec =
                        new ModelToIdlAdapter().adapt(FaceToolUtils.loadModel(modelFile));
                result = new IdlParseResult(spec, List.of());
            }

            List<LanguageMapper> mappers =
                    FaceToolUtils.buildMappers(allLanguages, allFace, genCpp, genJava, genPython, genCsharp);
            LOG.info("Active mappers: " + mappers.stream().map(LanguageMapper::languageName).toList());
            new LanguageBindingPipeline(mappers).generate(result, outputDir);
            LOG.info("Language binding complete.");
            return 0;
        }
    }
}
