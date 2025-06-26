package face.idl.model.v3;

public class ConstructForwardDeclaration implements ITypeDeclaration {
    public enum Kind {
        STRUCT,
        UNION
    }

    private Kind _kind;

    public Kind getKind() {
        return _kind;
    }

    public void setKind(Kind value) {
        _kind = value;
    }

}
