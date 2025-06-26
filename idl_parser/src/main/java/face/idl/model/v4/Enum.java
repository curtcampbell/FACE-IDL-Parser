package face.idl.model.v4;

public class Enum extends ScopedObjectBase{
    public Enum(String identifier) {
        super(identifier);
    }

    /**
     * @return
     */
    @Override
    public Kind getKind() {
        return Kind.Enum;
    }

}
