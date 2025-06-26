package face.idl.model.v3;

import java.util.Vector;

public class Specification implements IDefinitionParent {

    private final Vector<Definition> _definitions = new Vector<>();

    public void AddDefinition(Definition definition){
        _definitions.add(definition);
    }
    public Vector<Definition> GetDefinitions(){
        return _definitions;
    }

    public Definition getLastDefinition() {
        return _definitions.lastElement();
    }

}
