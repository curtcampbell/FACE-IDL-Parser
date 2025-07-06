package face.idl.model.v4.v4;

public class SequenceType implements ITypeSpec {

    public void setPositiveInt(String positiveInt) {
        this.positiveInt = positiveInt;
    }

    public String getPositiveInt() {
        return positiveInt;
    }

    public void setTypeSpec(ITypeSpec typeSpec) {
        this.typeSpec = typeSpec;
    }

    public ITypeSpec getTypeSpec() {
        return typeSpec;
    }

    private String positiveInt;
    private ITypeSpec typeSpec;
}
