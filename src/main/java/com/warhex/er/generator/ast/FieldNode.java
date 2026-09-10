package com.warhex.er.generator.ast;

/**
 * A single member field inside an IDL {@code struct} or the discriminant/case
 * fields inside a {@code union}.
 */
public final class FieldNode implements IdlNode {

    private final IdlType type;
    private final String  name;

    public FieldNode(IdlType type, String name) {
        this.type = type;
        this.name = name;
    }

    public IdlType type()  { return type; }
    public String  name()  { return name; }

    // JavaBean aliases for Velocity 2.x property resolution
    public IdlType getType() { return type; }
    public String  getName() { return name; }
}
