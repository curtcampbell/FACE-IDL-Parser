package com.warhex.er.generator.ast;

/** A single parameter in an IDL operation. */
public final class ParameterNode implements IdlNode {

    private final ParamDirection direction;
    private final IdlType        type;
    private final String         name;

    public ParameterNode(ParamDirection direction, IdlType type, String name) {
        this.direction = direction;
        this.type      = type;
        this.name      = name;
    }

    public ParamDirection direction() { return direction; }
    public IdlType        type()      { return type; }
    public String         name()      { return name; }

    // JavaBean aliases for Velocity 2.x property resolution
    public ParamDirection getDirection() { return direction; }
    public IdlType        getType()      { return type; }
    public String         getName()      { return name; }
}
