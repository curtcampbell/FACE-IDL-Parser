package com.warhex.er.generator.binding;

import com.warhex.er.generator.parser.IdlParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Stage 2 orchestrator: runs each registered {@link LanguageMapper} in order
 * against a shared {@link IdlParseResult}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   IdlParseResult result = new IdlDirectoryParser(searchDirs).parse(idlDir);
 *   LanguageBindingPipeline stage2 = new LanguageBindingPipeline(
 *       List.of(new CppLanguageMapper(velocity)));
 *   stage2.generate(result, outputDir);
 * </pre>
 *
 * <h2>Output structure under outputDir</h2>
 * Each mapper decides its own subdirectory.  The C++ mapper produces one
 * {@code .hpp} per IDL source file, mirroring the IDL directory tree:
 * <pre>
 *   cpp/
 *   ├── DM/
 *   │   └── SampleModel.hpp   ← all structs/enums from DM/SampleModel.idl
 *   └── TypedTS/
 *       └── EntityEvent.hpp   ← TypedTS instantiation from TypedTS/EntityEvent.idl
 * </pre>
 */
public class LanguageBindingPipeline {

    private static final Logger LOG =
            Logger.getLogger(LanguageBindingPipeline.class.getName());

    private final List<LanguageMapper> mappers;

    /**
     * @param mappers ordered list of language mappers to run; an immutable copy
     *                is taken at construction time
     */
    public LanguageBindingPipeline(List<LanguageMapper> mappers) {
        this.mappers = List.copyOf(mappers);
    }

    /**
     * Run all registered mappers against {@code result}, writing output under
     * {@code outputDir}.
     *
     * @param result    IDL parse result containing merged spec and per-file units
     * @param outputDir root directory for all generated files
     * @throws Exception if any mapper fails
     */
    public void generate(IdlParseResult result, Path outputDir) throws Exception {
        for (LanguageMapper mapper : mappers) {
            LOG.info("Language binding: " + mapper.languageName());
            try {
                mapper.map(result, outputDir);
                LOG.info(mapper.languageName() + " binding complete.");
            } catch (Exception e) {
                LOG.severe(mapper.languageName() + " binding FAILED: " + e.getMessage());
                LOG.log(java.util.logging.Level.SEVERE,
                        mapper.languageName() + " stack trace:", e);
                throw e;
            }
        }
    }
    /**
     * Generate language bindings writing to {@code outputDir/<lang>/<subdir>/}
     * for each active mapper.
     *
     * <p>Used for both data-model binding ({@code subdir = "data-model"}) and
     * per-UoP TypedTS binding ({@code subdir = uopName}), so that each output
     * tree can be compiled into a standalone library.
     *
     * @param result    IDL parse result to bind
     * @param outputDir root output directory (the same root passed to {@link #generate})
     * @param subdir    subdirectory name placed under each language directory
     *                  (e.g. {@code "data-model"} or a UoP name such as {@code "NavSystem"})
     * @throws Exception if any mapper fails
     */
    public void generateIntoSubdir(IdlParseResult result, Path outputDir, String subdir)
            throws Exception {
        for (LanguageMapper mapper : mappers) {
            Path langSubRoot = outputDir
                    .resolve(mapper.outputSubdirectory())
                    .resolve(subdir);
            LOG.info(mapper.languageName() + " binding [" + subdir + "]: " + langSubRoot);
            try {
                mapper.mapDirect(result, langSubRoot);
                LOG.info(mapper.languageName() + " binding [" + subdir + "] complete.");
            } catch (Exception e) {
                LOG.severe(mapper.languageName() + " binding [" + subdir
                        + "] FAILED: " + e.getMessage());
                throw e;
            }
        }
    }
}