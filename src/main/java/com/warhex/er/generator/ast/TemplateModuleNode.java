package com.warhex.er.generator.ast;

import java.util.List;

/**
 * IDL template module declaration.
 * Example: {@code module Typed<typename DATATYPE_TYPE> { ... };}
 */
public final class TemplateModuleNode extends IdlDefinition {

    private final List<FormalParameter> formalParameters;
    private final List<IdlDefinition>   definitions;

    public TemplateModuleNode(String name,
                              List<FormalParameter> formalParameters,
                              List<IdlDefinition> definitions) {
        super(name);
        this.formalParameters = List.copyOf(formalParameters);
        this.definitions      = List.copyOf(definitions);
    }

    public List<FormalParameter> formalParameters()    { return formalParameters; }
    public List<IdlDefinition>   definitions()         { return definitions; }

    // JavaBean aliases for Velocity 2.x
    public List<FormalParameter> getFormalParameters() { return formalParameters; }
    public List<IdlDefinition>   getDefinitions()      { return definitions; }
}
