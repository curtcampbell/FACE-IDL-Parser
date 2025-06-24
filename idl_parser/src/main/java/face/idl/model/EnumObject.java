package face.idl.model;

public class EnumObject extends FaceObjectBase{


    public EnumObject(String id, ModuleObject parentModule) {
        super(id, parentModule);
    }

    /**
     * 
     * @return
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.ENUM;
    }
}
