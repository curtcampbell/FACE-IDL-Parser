package com.warhex.er.generator.ast;

import java.util.List;
import java.util.Optional;

public final class StructNode extends IdlDefinition {

    private final Optional<String> baseType;
    private final List<FieldNode>  members;

    public StructNode(String name, Optional<String> baseType, List<FieldNode> members) {
        super(name);
        this.baseType = baseType;
        this.members  = List.copyOf(members);
    }

    public StructNode(String name, List<FieldNode> members) {
        this(name, Optional.empty(), members);
    }

    public Optional<String> baseType() { return baseType; }
    public List<FieldNode>  members()  { return members; }

    // JavaBean aliases for Velocity 2.x
    public Optional<String> getBaseType() { return baseType; }
    public List<FieldNode>  getMembers()  { return members; }
}
