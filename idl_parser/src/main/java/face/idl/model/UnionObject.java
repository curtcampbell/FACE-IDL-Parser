package face.idl.model;

public class UnionObject extends FaceObjectBase {


    public UnionObject(String id, ModuleObject parentModule) {
        super(id, parentModule);
    }

    /**
     * @return 
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.UNION;
    }
}
