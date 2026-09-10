package com.warhex.er.generator.ast;

import java.util.List;

/**
 * IDL template module instantiation.
 * Example: {@code module ::FACE::TSS::Typed<TrackData_t> TrackDataTypedTS;}
 */
public final class TemplateInstNode extends IdlDefinition {

    private final String       templateName;
    private final List<String> actualParameters;

    public TemplateInstNode(String alias, String templateName, List<String> actualParameters) {
        super(alias);
        this.templateName     = templateName;
        this.actualParameters = List.copyOf(actualParameters);
    }

    public String       templateName()     { return templateName; }
    public List<String> actualParameters() { return actualParameters; }
    /** The alias is the simple name stored in IdlDefinition. */
    public String       alias()            { return name(); }

    // JavaBean aliases for Velocity 2.x
    public String       getTemplateName()     { return templateName; }
    public List<String> getActualParameters() { return actualParameters; }
    public String       getAlias()            { return name(); }
}
