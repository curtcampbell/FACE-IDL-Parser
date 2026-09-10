package com.warhex.er.generator.ast;

/** IDL {@code typedef} declaration. */
public final class TypedefNode extends IdlDefinition {

    private final IdlType underlyingType;

    public TypedefNode(String name, IdlType underlyingType) {
        super(name);
        this.underlyingType = underlyingType;
    }

    public IdlType underlyingType()    { return underlyingType; }
    public IdlType getUnderlyingType() { return underlyingType; }  // Velocity
}
