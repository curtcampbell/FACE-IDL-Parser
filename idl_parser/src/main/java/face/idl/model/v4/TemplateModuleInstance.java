package face.idl.model.v4;

import java.util.Vector;

public class TemplateModuleInstance extends ScopedObjectBase{

    public TemplateModuleInstance(String identifier) {
        super(identifier);
    }

    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.TemplateModuleInstance;
    }

    public void addActualParameter(IActualParameter actualParameter) {
        actualParameters.add(actualParameter);
    }

    public Vector<IActualParameter> getActualParameters() {
        return actualParameters;
    }

    public void setScopedName(String scopedName) {
        this.scopedName = scopedName;
    }

    public String getScopedName() {
        return scopedName;
    }

    private String scopedName;
    private final Vector<IActualParameter> actualParameters = new Vector<>();
}
