package com.warhex.er.generator.ast;

/** IDL {@code const} declaration. */
public final class ConstNode extends IdlDefinition {

    private final IdlType type;
    private final String  value;

    public ConstNode(String name, IdlType type, String value) {
        super(name);
        this.type  = type;
        this.value = value;
    }

    public IdlType type()     { return type; }
    public String  value()    { return value; }

    // JavaBean aliases for Velocity 2.x
    public IdlType getType()  { return type; }
    public String  getValue() { return value; }
}
