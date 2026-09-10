package com.warhex.er.generator.ast;

/** A formal type parameter in an IDL template module, e.g. {@code typename DATATYPE_TYPE}. */
public final class FormalParameter implements IdlNode {

    private final String kind;  // "typename" or "interface"
    private final String name;

    public FormalParameter(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    public String kind()    { return kind; }
    public String name()    { return name; }

    // JavaBean aliases for Velocity 2.x
    public String getKind() { return kind; }
    public String getName() { return name; }
}
