package com.warhex.er.generator.parser;

import com.warhex.er.generator.ast.IdlDefinition;

import java.nio.file.Path;
import java.util.List;

/**
 * Associates a root IDL source file with the top-level {@link IdlDefinition}s
 * parsed directly from that file's own content (excluding transitively included files).
 *
 * <p>Language mappers use this class to produce one output file per IDL source file,
 * named after the IDL file, containing all definitions from that file.
 *
 * <h2>Why exclude transitive includes?</h2>
 * A root IDL file like {@code TypedTS/EntityEvent.idl} may
 * {@code #include <FACE/TSS/TypedTS.idl>}.  The template definitions in that
 * framework file belong in the framework header, not in the user's generated header.
 * The IDL {@code #include} directive is sent to the hidden ANTLR channel during
 * single-file parsing, so framework definitions never appear in a per-file result.
 */
public final class IdlFileUnit {

    private final Path absolutePath;
    private final Path relativePath;      // relative to the IDL root directory
    private final List<IdlDefinition> definitions;

    /**
     * @param absolutePath  full filesystem path of the IDL file
     * @param relativePath  path relative to the IDL root directory, e.g.
     *                      {@code TypedTS/EntityEvent.idl}
     * @param definitions   top-level definitions directly in this file, in parse order
     */
    public IdlFileUnit(Path absolutePath,
                       Path relativePath,
                       List<IdlDefinition> definitions) {
        this.absolutePath = absolutePath;
        this.relativePath = relativePath;
        this.definitions  = List.copyOf(definitions);
    }

    /** Full filesystem path of the IDL file. */
    public Path absolutePath() { return absolutePath; }

    /**
     * Relative path from the IDL root directory.
     * Examples: {@code TypedTS/EntityEvent.idl}, {@code DM/SampleModel.idl}.
     */
    public Path relativePath() { return relativePath; }

    /**
     * Top-level definitions declared directly in this IDL file, in parse order.
     * Definitions pulled in from {@code #include}d files are excluded.
     */
    public List<IdlDefinition> definitions() { return definitions; }

    /**
     * File base name without extension.
     * For {@code TypedTS/EntityEvent.idl} this returns {@code "EntityEvent"}.
     */
    public String stemName() {
        String fn = absolutePath.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot >= 0 ? fn.substring(0, dot) : fn;
    }

    /**
     * Relative output path for C++ generation ({@code .idl} → {@code .hpp}),
     * preserving subdirectory structure.
     * For {@code TypedTS/EntityEvent.idl} this returns {@code TypedTS/EntityEvent.hpp}.
     */
    public Path relativeOutputPath() {
        Path parent = relativePath.getParent();
        String hpp = stemName() + ".hpp";
        return parent == null ? Path.of(hpp) : parent.resolve(hpp);
    }

    @Override
    public String toString() {
        return relativePath + " (" + definitions.size() + " top-level defs)";
    }
}
