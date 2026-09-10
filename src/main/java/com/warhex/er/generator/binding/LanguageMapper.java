package com.warhex.er.generator.binding;

import com.warhex.er.generator.parser.IdlParseResult;

import java.nio.file.Path;

/**
 * Contract for a language-specific binding generator.
 *
 * <p>Implementations receive an {@link IdlParseResult} that contains both the
 * merged {@link com.warhex.er.generator.ast.IdlSpecification} (for type resolution
 * via {@link TemplateInstantiator}) and per-file {@link com.warhex.er.generator.parser.IdlFileUnit}
 * groupings (for producing one output file per IDL source file).
 *
 * <p>When the input was a YAML model rather than an IDL directory,
 * {@code result.fileUnits()} is empty; mappers should fall back to a per-type
 * output strategy in that case.
 *
 * <p>Each mapper is responsible for creating any output subdirectory it needs.
 * The directory structure beneath {@code outputDir} is mapper-defined (e.g.
 * {@code cpp/TypedTS/EntityEvent.hpp} for the C++ mapper).
 */
public interface LanguageMapper {

    /**
     * Short display name used in logs (e.g. {@code "C++"}).
     */
    String languageName();

    /**
     * Generate all language-specific binding files from {@code result}.
     *
     * @param result    parse result containing the merged spec and per-file units
     * @param outputDir root output directory; the mapper writes beneath it
     * @throws Exception on any I/O or template rendering failure
     */
    void map(IdlParseResult result, Path outputDir) throws Exception;

    /**
     * Filesystem directory name used as the output subdirectory (e.g. {@code "cpp"},
     * {@code "java"}).  Used when the caller needs to pre-compose the full output path.
     */
    String outputSubdirectory();

    /**
     * Generate all language-specific binding files from {@code result}, writing
     * directly into {@code langRoot} without appending the language subdirectory.
     * Use this when the caller has already resolved the full target path
     * (e.g. {@code <output>/<lang>/<uopName>/} for per-UoP TypedTS bindings).
     *
     * @param result   parse result containing the merged spec and per-file units
     * @param langRoot the exact directory to write into; created if absent
     * @throws Exception on any I/O or template rendering failure
     */
    void mapDirect(IdlParseResult result, Path langRoot) throws Exception;
}
