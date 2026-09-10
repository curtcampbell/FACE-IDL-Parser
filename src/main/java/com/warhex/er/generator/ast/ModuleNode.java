package com.warhex.er.generator.ast;

import java.util.List;

public final class ModuleNode extends IdlDefinition {

    private final List<IdlDefinition> definitions;

    public ModuleNode(String name, List<IdlDefinition> definitions) {
        super(name);
        this.definitions = List.copyOf(definitions);
    }

    public List<IdlDefinition> definitions()    { return definitions; }
    public List<IdlDefinition> getDefinitions() { return definitions; }  // Velocity
}
