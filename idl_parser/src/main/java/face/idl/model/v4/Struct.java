package face.idl.model.v4;

public class Struct extends ScopedObjectBase{
    public Struct(String identifier) {
        super(identifier);
    }

    /**
     * @return
     */
    @Override
    public Kind getKind() {
        return Kind.Struct;
    }
}
