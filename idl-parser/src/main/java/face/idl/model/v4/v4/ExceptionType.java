package face.idl.model.v4.v4;

/**
 * Represents a parsed exception type in the scoped object model.
 * This is here only as a place holder to support parsing.  Exceptions are not
 * currently supported by FACE IDL.
 * This class extends {@code ScopedObjectBase} and serves as a specific kind of scoped object
 * categorized as a part of the IDL (Interface Definition Language) model.
 */
public class ExceptionType extends ScopedObjectBase {
    public ExceptionType(String identifier) {
        super(identifier);
    }

    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Exception;
    }
}
