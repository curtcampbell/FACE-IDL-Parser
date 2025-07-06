package face.idl.model.v4;

import java.lang.Module;
import java.util.Vector;

public class TemplateModule extends Module {
    public TemplateModule(String identifier) {
        super(identifier);
    }

    public enum FormalPrameterTypes {
        None,
        TypeName,
        Interface,
        ValueType,
        Struct,
        Union,
        Enum,
        Sequence,
        SequenceType,
        Exception,
        ConstType,
        Const
    }

    static public class FormalParameterType {

        public FormalParameterType(FormalPrameterTypes type, String identifier) {
            this.type = type;
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public FormalPrameterTypes getType() {
            return type;
        }

        public void setType(FormalPrameterTypes type) {
            this.type = type;
        }

        private String identifier;
        private FormalPrameterTypes type;
    }


    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.TemplateModule;
    }

    public void addFormalParameter(FormalPrameterTypes type, String identifier){
        var formalParameterType = new FormalParameterType(type, identifier);
        formalParameters.add(formalParameterType);
    }

    public Vector<FormalParameterType> getFormalParameters() {
        return formalParameters;
    }

    private final Vector<FormalParameterType> formalParameters = new Vector<>();
}
