package face.idl.model.v4;

public class TemplateModule extends ScopedObjectBase{
    public TemplateModule(String identifier) {
        super(identifier);
    }

    @Override
    public Kind getKind() {
        return Kind.templateModule;
    }
}
