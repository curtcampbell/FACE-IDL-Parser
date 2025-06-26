package face.idl.model.v3;

import java.util.Vector;

public class Module implements IDefinitionContent, IDefinitionParent {
    private String _identifier;
    private Vector<Definition> _definitions;

    public String getIdentifier() {
        return _identifier;
    }

    public void setIdentifier(String value) {
        _identifier = value;
    }

    public Vector<Definition> getDefinitions() {
        return _definitions;
    }

    public void setDefinitions(Vector<Definition> value) {
        _definitions = value;
    }

}
