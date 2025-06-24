package face.idl.model;

public class TypeDefObject extends FaceObjectBase {

    protected TypeDefObject(String id, ModuleObject parentModule) {
        super(id, parentModule);
    }

    /**
     * @return 
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.TYPEDEF;
    }
}
