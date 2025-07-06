package mil.army.face.idl.model.v4;

public class StructObject extends FaceObjectBase {
    public StructObject(String id, ModuleObject parentModule) {
        super(id, parentModule);
    }



    /**
     * @return 
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.STRUCT;
    }
}
