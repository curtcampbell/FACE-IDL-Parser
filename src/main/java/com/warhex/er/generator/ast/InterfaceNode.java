package com.warhex.er.generator.ast;

import java.util.List;

public final class InterfaceNode extends IdlDefinition {

    private final boolean             isAbstract;
    private final boolean             isLocal;
    private final List<String>        inheritedInterfaces;
    private final List<OperationNode> operations;

    public InterfaceNode(String name, boolean isAbstract, boolean isLocal,
                         List<String> inheritedInterfaces,
                         List<OperationNode> operations) {
        super(name);
        this.isAbstract          = isAbstract;
        this.isLocal             = isLocal;
        this.inheritedInterfaces = List.copyOf(inheritedInterfaces);
        this.operations          = List.copyOf(operations);
    }

    public boolean             isAbstract()           { return isAbstract; }
    public boolean             isLocal()               { return isLocal; }
    public List<String>        inheritedInterfaces()   { return inheritedInterfaces; }
    public List<OperationNode> operations()            { return operations; }

    // JavaBean aliases for Velocity 2.x
    public List<String>        getInheritedInterfaces() { return inheritedInterfaces; }
    public List<OperationNode> getOperations()           { return operations; }
}
