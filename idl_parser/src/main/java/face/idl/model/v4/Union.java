package face.idl.model.v4;

public class Union extends ScopedObjectBase{
    public Union(String identifier) {
        super(identifier);
    }

    /**
     * @return
     */
    @Override
    public Kind getKind() {
        return Kind.Union;
    }
}
