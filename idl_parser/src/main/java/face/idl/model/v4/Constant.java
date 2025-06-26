package face.idl.model.v4;

public class Constant extends ScopedObjectBase{
    public Constant(String identifier) {
        super(identifier);
    }

    @Override
    public Kind getKind() {
        return Kind.Constant;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }

    public void setDataType(SimpleDataTypes dataType) {
        this.dataType = dataType;
    }

    public SimpleDataTypes getDataType() {
        return dataType;
    }

    private String expression;
    private SimpleDataTypes dataType;
}
