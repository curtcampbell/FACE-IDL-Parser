package face.idl.model.v4.v4;

public class StringType implements ITypeSpec {

    public void setLength(String length) {
        this.length = length;
    }

    public String getLength() {
        return length;
    }

    private String length;
}
