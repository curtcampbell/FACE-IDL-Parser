package face.idl.model.v4.v4;

public class Module extends ScopedObjectBase{
    private String identifier;

    public Module(String identifier) {
        super(identifier);
    }


    /**
     * @return Kind of scoped object
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Module;
    }

}
