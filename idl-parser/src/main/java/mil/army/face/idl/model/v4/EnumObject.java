package mil.army.face.idl.model.v4;

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
