package face.idl.model;

public class TypeDefObject extends FaceObjectBase {

    protected TypeDefObject(String id, ModuleObject parentModule) {
        /**
         * Constructs a new TypeDefObject with the given ID and parent module.
         * @param id The ID of the typedef.
         * @param parentModule The parent module of the typedef.
         */
        super(id, parentModule);
    }

    public enum DefinitionType {
        UNSIGNED_SHORT,
        UNSIGNED_LONG,
        UNSIGNED_LONG_LONG,
        SHORT,
        LONG,
        LONG_LONG,
        FLOAT,
        DOUBLE,
        LONG_DOUBLE,
        BOOLEAN,
        OCTET,
        STRING,
        FIXED,
        OBJECT,
        SEQUENCE,
        ARRAY,
        ANY
    }

    /**
     * Sets the definition type of this typedef.
     * @param type The definition type to set.
     */
    public void setType(DefinitionType type) {
        _type = type;
    }

    /**
     * Returns the definition type of this typedef.
     * @return The definition type.
     */
    public DefinitionType getType() {
        return _type;
    }

    /**
     * Sets the ID of this typedef.
     * @param id The ID to set.
     */
    public void setId(String id) {
        _id = id;
    }

    /**
     * Returns the ID of this typedef.
     * @return The ID.
     */
    public String getId() {
        return _id;
    }

    /**
     * Returns the FaceObjectType of this object, which is TYPEDEF.
     * @return The FaceObjectType.
     */
    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.TYPEDEF;
    }

    private DefinitionType _type;
    private String _id = null;
}
