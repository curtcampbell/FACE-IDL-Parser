package face.idl.model;

public abstract class FaceObjectBase implements FaceObject {
    protected final String id;
    protected final ModuleObject parentModule;

    protected FaceObjectBase(String id, ModuleObject parentModule) {
        this.id = id;
        this.parentModule = parentModule;
    }

    public String getId() {
        return id;
    }

    public ModuleObject getParentModule() {
        return parentModule;
    }

}
