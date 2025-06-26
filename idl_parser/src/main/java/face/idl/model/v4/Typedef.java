package face.idl.model.v4;

import java.util.HashSet;
import java.util.Set;

public class Typedef extends ScopedObjectBase{

    public enum DataTypes {
        // Simple types
        SignedShort,
        SignedLong,
        SignedLongLong,
        UnsignedShort,
        UnsignedLong,
        UnsignedLongLong,
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
    public Kind getKind() {
        return Kind.Typedef;
    }

    public String getDeclarator() {
        if(!getIdIsDeclarator()) {
            throw new RuntimeException("Unexpected error. Typedefs should always have a declarator.");
        }
        return getIdentifier();
    }
}
