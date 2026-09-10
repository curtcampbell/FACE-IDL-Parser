package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-neutral DTO for a single Platform Entity definition as read from the
 * source file.
 *
 * <p>Fields correspond to the raw keys in the source file.  The mapper assigns
 * {@code typeEnumValue} and any other derived values.
 */
public class EntityData {

    /**
     * Short entity name without qualification, e.g. {@code "Track"}.
     * Used to derive the IDL struct name via the model's
     * {@code structNamePattern}.
     */
    private String simpleName;

    /**
     * Fully-qualified IDL path, e.g. {@code "FACE.DM.SampleModel.Track"}.
     * If absent from the source file the mapper synthesises it from
     * {@code idlModule + "." + simpleName}.
     */
    private String qualifiedPath;

    /** Human-readable description, used in generated file headers. */
    private String description;

    /**
     * List of IDL files this entity's IDL must {@code #include}, e.g.
     * {@code ["GeoPosition.idl"]}.
     */
    private List<String> includes = new ArrayList<>();

    /**
     * Supporting enum types declared alongside this entity's struct
     * (e.g., {@code ThreatLevel}).
     */
    private List<EnumData> supportingEnums = new ArrayList<>();

    /** Ordered list of struct fields as declared in the source file. */
    private List<FieldData> fields = new ArrayList<>();

    /**
     * When {@code true} the IDL type should be generated as a {@code union}
     * rather than a {@code struct}.  Corresponds to a FACE CompositeTemplate
     * with {@code isUnion=true}.  Defaults to {@code false}.
     */
    private boolean union;

    /**
     * When {@code true} this entity originated from a {@code uop:CompositeTemplate};
     * when {@code false} it is a plain {@code uop:Template} and must receive the
     * {@code module T_<name>} wrapper + typedef in generated IDL (IDL-1).
     */
    private boolean compositeTemplate;

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }

    public String getQualifiedPath() { return qualifiedPath; }
    public void setQualifiedPath(String qualifiedPath) { this.qualifiedPath = qualifiedPath; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getIncludes() { return includes; }
    public void setIncludes(List<String> includes) {
        this.includes = includes != null ? includes : new ArrayList<>();
    }

    public List<EnumData> getSupportingEnums() { return supportingEnums; }
    public void setSupportingEnums(List<EnumData> supportingEnums) {
        this.supportingEnums = supportingEnums != null ? supportingEnums : new ArrayList<>();
    }

    public List<FieldData> getFields() { return fields; }
    public void setFields(List<FieldData> fields) {
        this.fields = fields != null ? fields : new ArrayList<>();
    }

    public boolean isUnion() { return union; }
    public void setUnion(boolean union) { this.union = union; }

    public boolean isCompositeTemplate() { return compositeTemplate; }
    public void setCompositeTemplate(boolean compositeTemplate) { this.compositeTemplate = compositeTemplate; }
}
