package face.idl.model.v3;

public class Definition {
    private IDefinitionContent _definition;

    public IDefinitionContent getDefinition() {
        return _definition;
    }

    public <T> T getDefinitionAs(){
        return (T)_definition;
    }

    public void setDefinition(IDefinitionContent value) {
        _definition = value;
    }

}
