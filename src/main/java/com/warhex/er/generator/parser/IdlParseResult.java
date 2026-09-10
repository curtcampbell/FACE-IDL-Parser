package com.warhex.er.generator.parser;

import com.warhex.er.generator.ast.IdlSpecification;

import java.util.List;

/**
 * The product of {@link IdlDirectoryParser#parse(java.nio.file.Path)}.
 *
 * <p>Contains two complementary views of the same IDL input:
 *
 * <dl>
 *   <dt>{@link #mergedSpec()}</dt>
 *   <dd>A single, unified {@link IdlSpecification} that merges all root IDL files
 *       with their transitive FACE framework includes.  Required by
 *       {@link com.warhex.er.generator.binding.TemplateInstantiator} for template
 *       resolution — the instantiator needs both template <em>definitions</em>
 *       (from framework IDL) and <em>instantiations</em> (from user IDL) in one
 *       spec.</dd>
 *
 *   <dt>{@link #fileUnits()}</dt>
 *   <dd>Per-source-file groupings of the top-level definitions declared directly in
 *       each root IDL file (excludes definitions from transitively included files).
 *       Used by language mappers that produce one output file per IDL input file —
 *       the output file is named after the IDL file and contains exactly the
 *       definitions from that file.</dd>
 * </dl>
 *
 * <p>When the input was a YAML model (not an IDL directory), {@link #fileUnits()}
 * is empty and language mappers may fall back to a per-type output strategy.
 */
public final class IdlParseResult {

    private final IdlSpecification  mergedSpec;
    private final List<IdlFileUnit> fileUnits;

    /**
     * @param mergedSpec unified spec for type resolution; never {@code null}
     * @param fileUnits  per-source-file definition groups; empty if input was a
     *                   YAML model rather than an IDL directory
     */
    public IdlParseResult(IdlSpecification mergedSpec, List<IdlFileUnit> fileUnits) {
        this.mergedSpec = mergedSpec;
        this.fileUnits  = List.copyOf(fileUnits);
    }

    /**
     * Unified IDL specification for type resolution (e.g. template instantiation).
     * Never {@code null}.
     */
    public IdlSpecification mergedSpec() { return mergedSpec; }

    /**
     * Per-source-file definition groups, in the order the root IDL files were
     * discovered.  Empty when the source was a YAML model rather than an IDL
     * directory.
     */
    public List<IdlFileUnit> fileUnits() { return fileUnits; }
}
