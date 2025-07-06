package face.idl.model.v4;

public class InterfaceObject extends FaceObjectBase{

    protected InterfaceObject(String id, ModuleObject parentModule) {
        super(id, parentModule);
    }

    /**
     * @return 
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.INTERFACE;
    }
}
