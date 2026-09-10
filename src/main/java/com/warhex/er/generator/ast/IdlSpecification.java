package com.warhex.er.generator.ast;

import java.util.List;

/** Root node of the IDL AST. */
public final class IdlSpecification implements IdlNode {

    private final List<IdlDefinition> definitions;

    public IdlSpecification(List<IdlDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    public List<IdlDefinition> definitions()    { return definitions; }
    public List<IdlDefinition> getDefinitions() { return definitions; }  // Velocity
}
