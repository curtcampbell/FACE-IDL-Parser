package face.idl.model.v4;

import java.util.Vector;

public class InterfaceType extends ScopedObjectBase {
    public InterfaceType(String identifier) {
        super(identifier);
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

    private final Vector<String> interfaceList = new Vector<>();
}
