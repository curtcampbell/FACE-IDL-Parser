package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-neutral DTO representing the top-level contents of any IDL-generatable
 * source (YAML entity model, JSON, .face XMI entity path, .face XMI TSS/UoP path, etc.).
 *
 * <p>All fields map directly to the raw keys/values in the source file.
 * No semantic enrichment (sorting, index assignment, pattern expansion) is
 * applied here — that is {@link com.warhex.er.generator.reader.EntityModelMapper}'s job.
 */
public class IdlModelData {

    /** Human-readable model name, e.g. {@code "SampleModel"}. */
    private String modelName;

    /**
     * Fully-qualified IDL module for the data model layer,
     * e.g. {@code "FACE.DM.SampleModel"}.
     */
    private String idlModule;

    /**
     * Fully-qualified IDL module for the type-services layer,
     * e.g. {@code "FACE.TSS.SampleModel"}.
     */
    private String tssIdlModule;

    /** Target C++ namespace, e.g. {@code "FACE::DM::SampleModel"}. */
    private String cppNamespace;

    /**
     * Pattern used to derive the IDL struct name from an entity simple name.
     * Default is {@code "{name}Entity"}.
     */
    private String structNamePattern;

    /** Ordered list of entity/type definitions as read from the source file. */
    private List<EntityData> entities = new ArrayList<>();

    /**
     * Named struct types shared across multiple entities (e.g. GeoPosition).
     * Each entry is a {@link FieldData} with {@code nested=true},
     * {@code idlType} = struct name, and {@code nestedFields} = the struct's fields.
     * When an entity field references one of these by {@code idlType} and provides
     * no inline {@code nested_fields}, the mapper resolves the definition here.
     */
    private List<FieldData> supportingStructs = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getIdlModule() { return idlModule; }
    public void setIdlModule(String idlModule) { this.idlModule = idlModule; }

    public String getTssIdlModule() { return tssIdlModule; }
    public void setTssIdlModule(String tssIdlModule) { this.tssIdlModule = tssIdlModule; }

    public String getCppNamespace() { return cppNamespace; }
    public void setCppNamespace(String cppNamespace) { this.cppNamespace = cppNamespace; }

    public String getStructNamePattern() { return structNamePattern; }
    public void setStructNamePattern(String structNamePattern) {
        this.structNamePattern = structNamePattern;
    }

    public List<EntityData> getEntities() { return entities; }
    public void setEntities(List<EntityData> entities) {
        this.entities = entities != null ? entities : new ArrayList<>();
    }

    public List<FieldData> getSupportingStructs() { return supportingStructs; }
    public void setSupportingStructs(List<FieldData> supportingStructs) {
        this.supportingStructs = supportingStructs != null ? supportingStructs : new ArrayList<>();
    }
}
