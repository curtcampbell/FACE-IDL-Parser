package com.warhex.er.generator.ast;

import java.nio.file.Path;

/**
 * Abstract base for IDL definitions that can appear at the top level of a
 * specification or inside a {@link ModuleNode}.
 *
 * <p>Concrete subtypes: {@link ModuleNode}, {@link StructNode},
 * {@link EnumNode}, {@link UnionNode}, {@link InterfaceNode},
 * {@link ConstNode}, {@link TypedefNode}, {@link TemplateModuleNode},
 * {@link TemplateInstNode}.
 *
 * <h2>sourceFile (Phase 8)</h2>
 * <p>{@link #sourceFile()} holds the absolute path of the IDL file that directly
 * contains this definition.  It is set by
 * {@link com.warhex.er.generator.parser.IdlDirectoryParser} during per-file
 * unit construction ({@code buildFileUnits}), so it is populated on nodes
 * obtained from {@link com.warhex.er.generator.parser.IdlFileUnit#definitions()}
 * but <em>not</em> on nodes in the merged {@link IdlSpecification} (which is
 * built from a combined parse of all translation units).
 */
public abstract class IdlDefinition implements IdlNode {

    private final String name;

    /**
     * Absolute path of the IDL source file that directly declares this
     * definition.  {@code null} for nodes in the merged spec or before
     * {@code IdlDirectoryParser.buildFileUnits()} has been called.
     */
    private Path sourceFile;

    protected IdlDefinition(String name) {
        this.name = name;
    }

    /** Simple (unqualified) name of this definition. */
    public String name() { return name; }

    /**
     * JavaBean alias for {@link #name()} — required by Velocity 2.x property
     * resolution, which only calls {@code getName()} (not {@code name()}) when
     * evaluating {@code $node.name} in a template.
     */
    public String getName() { return name; }

    /**
     * Absolute path of the IDL file that directly contains this definition.
     * Available on nodes from {@link com.warhex.er.generator.parser.IdlFileUnit#definitions()};
     * {@code null} on merged-spec nodes.
     */
    public Path sourceFile() { return sourceFile; }

    /** JavaBean alias for Velocity 2.x: {@code $def.sourceFile}. */
    public Path getSourceFile() { return sourceFile; }

    /**
     * Sets the source-file path.  Called by
     * {@link com.warhex.er.generator.parser.IdlDirectoryParser} during
     * per-file unit construction; not intended for general use.
     */
    public void setSourceFile(Path sourceFile) { this.sourceFile = sourceFile; }
}
