package face.idl.model.v4;

import java.util.HashSet;
import java.util.Set;

public abstract class ScopedObjectBase implements IScopedObject{
    private final String identifier;
    private final boolean idIsDeclaritor;
    private final Set<String> ids = new HashSet<>();
    private final Set<String> declarators = new HashSet<>();
    private final Set<IScopedObject> scopedObjects = new HashSet<>();
    
    
    public ScopedObjectBase(String identifier) {
        this(identifier, false);
    }
    
    public ScopedObjectBase(String identifier, boolean idIsDeclaritor) {
        this.identifier = identifier;
        this.idIsDeclaritor = idIsDeclaritor;
    }


    /**
     * @return
     */
    @Override
    public int hashCode() {
        int result = identifier.hashCode();
        result = 31 * result + (idIsDeclaritor ? 1 : 0);
        return result;
    }

    /**
     * @param obj
     * @return
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if(obj instanceof ScopedObjectBase scopedObjectBase){
            return identifier.equals(scopedObjectBase.identifier) &&
                    idIsDeclaritor == scopedObjectBase.idIsDeclaritor;
        }
        return  false;
    }

    /**
     * @return
     */
    @Override
    public boolean getIdIsDeclarator() {
        return idIsDeclaritor;
    }

    /**
     * @return
     */
    @Override
    public String getIdentifier() {
        return identifier;
    }

    /**
     * @param id
     * @return
     */
    @Override
    public boolean containsId(String id) {
        return ids.contains(id);
    }

    /**
     * @param declarator the declarator to check for existence 
     * @return
     */
    @Override
    public boolean containsDeclarator(String declarator) {
        return declarators.contains(declarator);
    }
    
    /**
     * @param newObject
     */
    @Override
    public void addScopedObject(IScopedObject newObject) {
        if(scopedObjects.contains(newObject)) {
            throw new RuntimeException("Object already exists in scope.");
        }
        scopedObjects.add(newObject);

        if(newObject.getIdIsDeclarator()) {
            declarators.add(newObject.getIdentifier());
        } else {
            ids.add(newObject.getIdentifier());
        }
    }
}
