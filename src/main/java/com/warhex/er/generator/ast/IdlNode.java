package com.warhex.er.generator.ast;

/**
 * Marker interface for all nodes in the IDL abstract syntax tree.
 *
 * <p>Language mappers walk the tree using {@code instanceof} checks against
 * the concrete node types ({@link ModuleNode}, {@link StructNode}, etc.).
 * A formal visitor pattern is not used here — the mapper implementations are
 * the visitors, and they dispatch explicitly.
 */
public interface IdlNode {
    // marker only
}
