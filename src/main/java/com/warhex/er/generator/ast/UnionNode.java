package com.warhex.er.generator.ast;

import java.util.List;

/** IDL {@code union} declaration. */
public final class UnionNode extends IdlDefinition {

    private final IdlType           switchType;
    private final List<UnionCaseNode> cases;

    public UnionNode(String name, IdlType switchType, List<UnionCaseNode> cases) {
        super(name);
        this.switchType = switchType;
        this.cases      = List.copyOf(cases);
    }

    public IdlType             switchType()    { return switchType; }
    public List<UnionCaseNode> cases()         { return cases; }

    // JavaBean aliases for Velocity 2.x
    public IdlType             getSwitchType() { return switchType; }
    public List<UnionCaseNode> getCases()      { return cases; }
}
