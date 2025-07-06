package face.idl.model.v4.v4;

public class Typedef extends ScopedObjectBase{


    public enum DataTypes {
        // Simple types
        SignedShort,
        SignedLong,
        SignedLongLong,
        SignedTiny,
        UnsignedShort,
        UnsignedLong,
        UnsignedLongLong,
        UnsignedTiny,
        Float,
        Double,
        LongDouble,
        Char,
        Boolean,
        Octet,

        //Structured Types
        Struct,
        Enum,
        Union,
        Sequence,
        Primitive
    }

    public Typedef(String declarator) {
        super(declarator, true);
    }

    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Typedef;
    }

//    public String getDeclarator() {
//        if(!getIdIsDeclarator()) {
//            throw new RuntimeException("Unexpected error. Typedefs should always have a declarator.");
//        }
//        return getIdentifier();
//    }

    public void setDeclarators(String[] declarators) {
        this.declarators = declarators;
    }

    public String[] getDeclarators() {
        return declarators;
    }

    public ITypeSpec getTypeSpec() {
        return typeSpec;
    }

    public void setTypeSpec(ITypeSpec typeSpec) {
        this.typeSpec = typeSpec;
    }


    private String[] declarators;
    private ITypeSpec typeSpec;
}
