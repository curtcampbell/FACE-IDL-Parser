package com.warhex.er.generator.ast;

import java.util.List;

/** A single case branch inside an IDL {@code union}. */
public final class UnionCaseNode implements IdlNode {

    private final List<String> labels;
    private final boolean      isDefault;
    private final IdlType      type;
    private final String       name;

    public UnionCaseNode(List<String> labels, boolean isDefault, IdlType type, String name) {
        this.labels    = List.copyOf(labels);
        this.isDefault = isDefault;
        this.type      = type;
        this.name      = name;
    }

    public List<String> labels()      { return labels; }
    public boolean      isDefault()   { return isDefault; }
    public IdlType      type()        { return type; }
    public String       name()        { return name; }

    // JavaBean aliases for Velocity 2.x
    public List<String> getLabels()   { return labels; }
    public IdlType      getType()     { return type; }
    public String       getName()     { return name; }
}
