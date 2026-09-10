package com.warhex.er.generator.ast;

import java.util.List;

public final class EnumNode extends IdlDefinition {

    private final List<EnumValueNode> values;

    public EnumNode(String name, List<EnumValueNode> values) {
        super(name);
        this.values = List.copyOf(values);
    }

    public List<EnumValueNode> values()    { return values; }
    public List<EnumValueNode> getValues() { return values; }  // Velocity
}
