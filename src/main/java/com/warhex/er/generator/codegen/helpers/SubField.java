package com.warhex.er.generator.codegen.helpers;

/**
 * A single leaf field within a composite (struct-typed) IDL field.
 *
 * <p>Produced by {@link EntityReactorHelper#topLevelFields} for each member
 * of a nested struct.  The {@code compositeIndex} maps directly to
 * {@code FieldDescriptor::composite_index} in the EntityReactor C++ layer.
 *
 * <h2>Example — GeoPosition sub-fields</h2>
 * <pre>
 *   compositeIndex=0  name="latitude"   qualifiedName="position.latitude"   AV_DOUBLE / EV_DOUBLE
 *   compositeIndex=1  name="longitude"  qualifiedName="position.longitude"  AV_DOUBLE / EV_DOUBLE
 *   compositeIndex=2  name="altitude_m" qualifiedName="position.altitude_m" AV_DOUBLE / EV_DOUBLE
 * </pre>
 */
public final class SubField {

    private final String name;
    private final int    compositeIndex;
    private final String qualifiedName;       // "position.latitude"
    private final String attributeValueType;  // "AV_DOUBLE"
    private final String entityValueKind;     // "EV_DOUBLE"

    public SubField(String parentName,
                    String name,
                    int    compositeIndex,
                    String attributeValueType,
                    String entityValueKind) {
        this.name              = name;
        this.compositeIndex    = compositeIndex;
        this.qualifiedName     = parentName + "." + name;
        this.attributeValueType = attributeValueType;
        this.entityValueKind   = entityValueKind;
    }

    // Accessors (also Velocity-accessible via $field.name, $field.compositeIndex, …)
    public String getName()               { return name; }
    public int    getCompositeIndex()     { return compositeIndex; }
    public String getQualifiedName()      { return qualifiedName; }
    public String getAttributeValueType() { return attributeValueType; }
    public String getEntityValueKind()    { return entityValueKind; }

    @Override
    public String toString() {
        return "SubField{" + qualifiedName + "[" + compositeIndex + "]"
                + " " + attributeValueType + "}";
    }
}
