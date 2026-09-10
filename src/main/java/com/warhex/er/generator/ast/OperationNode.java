package com.warhex.er.generator.ast;

import java.util.List;

/** An operation (method) inside an IDL {@code interface}. */
public final class OperationNode implements IdlNode {

    private final String              name;
    private final IdlType             returnType;
    private final List<ParameterNode> parameters;
    private final boolean             isOneway;

    public OperationNode(String name, IdlType returnType,
                         List<ParameterNode> parameters, boolean isOneway) {
        this.name       = name;
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.isOneway   = isOneway;
    }

    public String              name()       { return name; }
    public IdlType             returnType() { return returnType; }
    public List<ParameterNode> parameters() { return parameters; }
    public boolean             isOneway()   { return isOneway; }

    // JavaBean aliases for Velocity 2.x property resolution
    public String              getName()       { return name; }
    public IdlType             getReturnType() { return returnType; }
    public List<ParameterNode> getParameters() { return parameters; }
}
