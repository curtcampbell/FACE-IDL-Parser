package com.warhex.er.generator.ast;

/** A single enumerator value inside an {@link EnumNode}. */
public final class EnumValueNode implements IdlNode {

    private final String name;

    public EnumValueNode(String name) {
        this.name = name;
    }

    public String name()    { return name; }
    public String getName() { return name; }   // JavaBean alias for Velocity
}
