package face.idl.model.v4;

import java.util.Vector;

public class InterfaceType extends ScopedObjectBase {
    public InterfaceType(String identifier) {
        super(identifier);
    }
    public InterfaceType(String identifier, boolean isForwardDeclaration) {
        super(identifier);
        this.isForwardDeclaration = isForwardDeclaration;
    }

    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return IScopedObject.ScopedObjectKind.Interface;
    }

    public void addInheritedInterface(String inheritanceSpec) {
        this.interfaceList.add(inheritanceSpec);
    }

    public Vector<String> getInheritanceInterfaces() {
        return interfaceList;
    }

    public boolean isForwardDeclaration() {
        return isForwardDeclaration;
    }

    private final Vector<String> interfaceList = new Vector<>();
    private boolean isForwardDeclaration = false;
}
