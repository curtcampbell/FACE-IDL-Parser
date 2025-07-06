package face.idl.model.v4.v4;

import java.util.Vector;

public class UnionType extends ScopedObjectBase implements ITypeSpec {
    public UnionType(String identifier) {
        super(identifier);
    }

    public UnionType(String identifier, boolean isForwardDeclaration) {
        super(identifier);
        this.isForwardDeclaration = isForwardDeclaration;
    }

        public static class CaseStatement {

            private String declarator;
            private ITypeSpec type;
            private String label;
        }
    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Union;
    }

    public void setSwitchType(ITypeSpec switchType) {
        this.switchType = switchType;
    }

    public ITypeSpec getSwitchType() {
        return switchType;
    }

    public void addCaseStatement(String[] labels, ITypeSpec type, String declarator ) {
        caseStatements.add(new CaseStatement());
    }

    public Vector<CaseStatement> getCaseStatements() {
        return caseStatements;
    }

    /**
     * @return
     */
    @Override
    public boolean isForwardDeclaration() {
        return isForwardDeclaration;
    }

    private ITypeSpec switchType;
    private final Vector<CaseStatement> caseStatements = new Vector<>();
    private boolean isForwardDeclaration = false;

}
