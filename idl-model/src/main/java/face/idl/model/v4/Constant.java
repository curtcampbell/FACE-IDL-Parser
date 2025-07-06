package face.idl.model.v4;

public class Constant extends ScopedObjectBase implements IActualParameter {
    public Constant(String identifier) {
        super(identifier);
    }

    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Constant;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }

    public void setDataType(ITypeSpec dataType) {
        //TODO: Limit this to only the types allows by constant declarations.
        this.dataType = dataType;
    }

    public ITypeSpec getDataType() {
        return dataType;
    }

    private String expression;
    private ITypeSpec dataType;
}
