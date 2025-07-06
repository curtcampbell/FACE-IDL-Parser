package mil.army.face.idl.model.v4;

public class BaseTypeSpec implements ITypeSpec {

    public BaseTypeSpec(BaseDataTypes dataType) {
        this.dataType = dataType;
    }

    public void setDataType(BaseDataTypes dataType) {
        this.dataType = dataType;
    }

    public BaseDataTypes getDataType() {
        return dataType;
    }

    private BaseDataTypes dataType;
}
