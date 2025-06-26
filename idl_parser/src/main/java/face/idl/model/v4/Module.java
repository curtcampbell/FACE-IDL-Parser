package face.idl.model.v4;

import java.util.HashSet;
import java.util.Set;

public class Module extends ScopedObjectBase{
    private String identifier;

    public Module(String identifier) {
        super(identifier);
    }


    /**
     * @return Kind of scoped object
     */
    @Override
    public Kind getKind() {
        return Kind.Module;
    }

}
