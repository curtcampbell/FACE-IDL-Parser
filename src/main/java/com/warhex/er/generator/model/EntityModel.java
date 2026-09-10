package com.warhex.er.generator.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Root descriptor for one Entity Reactor data model instance.
 *
 * <p>This class is the top-level data object passed to every Velocity template.
 * It is populated by an {@link com.warhex.er.generator.idl.ModelLoader} and
 * made available to templates as {@code $model}.
 *
 * <p>Fields mirror the generator configuration parameters defined in
 * {@code sample_datamodel.md} (Section "Generator Configuration").
 */
public class EntityModel {

    // -----------------------------------------------------------------------
    // Configuration parameters (ERS Section 7.5)
    // -----------------------------------------------------------------------

    /**
     * Human-readable name for this model instance (e.g., {@code "SampleModel"}).
     * Used in IDL module names, include guards, and output directory names.
     */
    private String modelName;

    /**
     * IDL module path for generated Entity Reactor types
     * (e.g., {@code "FACE.DM.SampleModel"}).
     * Dot-separated; each segment becomes a nested {@code module} declaration.
     */
    private String idlModule;

    /**
     * C++ namespace for Stage 2 generated output
     * (e.g., {@code "WARHEX::EntityReactor"}).
     */
    private String cppNamespace;

    /**
     * Pattern used to derive the IDL struct name from a Platform Entity
     * simple name (e.g., {@code "{name}Entity"} → {@code "TrackEntity"}).
     */
    private String structNamePattern = "{name}Entity";

    /**
     * IDL module path for the TSS layer
     * (e.g., {@code "FACE.TSS.SampleModel"}).
     * Used by TypeTS templates for module declarations and qualified-name prefixes.
     */
    private String tssIdlModule;

    /**
     * The ordered list of Platform Entities in this model.
     * Ordered alphabetically by simple name per [ER-071].
     */
    private List<EntityDescriptor> entities = new ArrayList<>();

    /**
     * Named struct types shared across multiple entities (e.g. GeoPosition).
     * Each entry is a {@link FieldDescriptor} with {@code nested=true},
     * {@code idlType} = struct name, and {@code nestedFields} = the struct's fields.
     *
     * <p>Populated from the {@code supporting_structs:} block in the source YAML.
     * The mapper also copies each struct's field list into any entity field that
     * references it by type name with no inline {@code nested_fields}.
     */
    private List<FieldDescriptor> supportingStructs = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getIdlModule() { return idlModule; }
    public void setIdlModule(String idlModule) { this.idlModule = idlModule; }

    public String getCppNamespace() { return cppNamespace; }
    public void setCppNamespace(String cppNamespace) { this.cppNamespace = cppNamespace; }

    public String getStructNamePattern() { return structNamePattern; }
    public void setStructNamePattern(String structNamePattern) {
        this.structNamePattern = structNamePattern;
    }

    public String getTssIdlModule() { return tssIdlModule; }
    public void setTssIdlModule(String tssIdlModule) { this.tssIdlModule = tssIdlModule; }

    public List<EntityDescriptor> getEntities() { return entities; }
    public void setEntities(List<EntityDescriptor> entities) { this.entities = entities; }

    public List<FieldDescriptor> getSupportingStructs() { return supportingStructs; }
    public void setSupportingStructs(List<FieldDescriptor> supportingStructs) {
        this.supportingStructs = supportingStructs != null ? supportingStructs : new ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // Derived helpers (called from Velocity templates)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // DM module helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the IDL module path split into individual segments.
     *
     * <p>Example: {@code "FACE.DM.SampleModel"} → {@code ["FACE", "DM", "SampleModel"]}
     */
    public List<String> getIdlModuleSegments() {
        return Arrays.asList(idlModule.split("\\."));
    }

    /**
     * Returns the IDL include-guard prefix derived from the DM module path.
     *
     * <p>Example: {@code "FACE.DM.SampleModel"} → {@code "FACE_DM_SAMPLEMODEL"}
     */
    public String getIdlModuleGuardPrefix() {
        return idlModule.replace('.', '_').toUpperCase();
    }

    /**
     * Returns the C++ double-colon qualified prefix for the DM module,
     * suitable for use in IDL qualified-name references.
     *
     * <p>Example: {@code "FACE.DM.SampleModel"} → {@code "::FACE::DM::SampleModel::"}
     */
    public String getIdlQualifiedPrefix() {
        return "::" + idlModule.replace(".", "::") + "::";
    }

    /**
     * Returns the source-file comment path for a DM artifact.
     *
     * <p>Example: {@code sourcePathFor("TrackEntity.idl")} →
     * {@code "FACE/DM/SampleModel/TrackEntity.idl"}
     */
    public String sourcePathFor(String fileName) {
        return idlModule.replace('.', '/') + "/" + fileName;
    }

    // -----------------------------------------------------------------------
    // TSS module helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the TSS IDL module path split into individual segments.
     *
     * <p>Example: {@code "FACE.TSS.SampleModel"} → {@code ["FACE", "TSS", "SampleModel"]}
     */
    public List<String> getTssIdlModuleSegments() {
        return Arrays.asList(tssIdlModule.split("\\."));
    }

    /**
     * Returns the IDL include-guard prefix derived from the TSS module path.
     *
     * <p>Example: {@code "FACE.TSS.SampleModel"} → {@code "FACE_TSS_SAMPLEMODEL"}
     */
    public String getTssIdlModuleGuardPrefix() {
        return tssIdlModule.replace('.', '_').toUpperCase();
    }

    /**
     * Returns the C++ double-colon qualified prefix for the TSS module.
     *
     * <p>Example: {@code "FACE.TSS.SampleModel"} → {@code "::FACE::TSS::SampleModel::"}
     */
    public String getTssIdlQualifiedPrefix() {
        return "::" + tssIdlModule.replace(".", "::") + "::";
    }

    /**
     * Returns the source-file comment path for a TSS artifact.
     *
     * <p>Example: {@code tssSourcePathFor("EntityEvent.idl")} →
     * {@code "FACE/TSS/SampleModel/EntityEvent.idl"}
     */
    public String tssSourcePathFor(String fileName) {
        return tssIdlModule.replace('.', '/') + "/" + fileName;
    }

    // -----------------------------------------------------------------------
    // Entity helpers
    // -----------------------------------------------------------------------

    /**
     * Derives the IDL struct name for a Platform Entity using the configured
     * {@link #structNamePattern}.
     *
     * @param entitySimpleName the simple name of the Platform Entity (e.g., {@code "Track"})
     * @return the IDL struct name (e.g., {@code "TrackEntity"})
     */
    public String structNameFor(String entitySimpleName) {
        return structNamePattern.replace("{name}", entitySimpleName);
    }

    /**
     * Returns a deduplicated list of nested struct types in generation order.
     *
     * <p>Explicit {@link #supportingStructs} entries appear first (in declaration
     * order), followed by any additional nested-struct types discovered from entity
     * fields that were not already listed there.  This ensures that explicitly
     * declared structs take precedence and that the IDL generator does not emit
     * duplicate definitions.
     *
     * <p>Each returned {@link FieldDescriptor} represents one unique nested struct:
     * {@link FieldDescriptor#getIdlType()} is the struct name (e.g., {@code "GeoPosition"})
     * and {@link FieldDescriptor#getNestedFields()} holds its sub-fields.
     *
     * @return ordered list of unique nested struct field descriptors; never {@code null}
     */
    public List<FieldDescriptor> getNestedStructTypes() {
        LinkedHashMap<String, FieldDescriptor> seen = new LinkedHashMap<>();
        // Explicit supporting structs first — canonical, single source of truth
        for (FieldDescriptor ss : supportingStructs) {
            seen.putIfAbsent(ss.getIdlType(), ss);
        }
        // Then entity-derived nested struct references (handles inline nested_fields
        // and any structs not listed in supporting_structs)
        for (EntityDescriptor entity : entities) {
            for (FieldDescriptor fd : entity.getFields()) {
                if (fd.isNested() && !seen.containsKey(fd.getIdlType())) {
                    seen.put(fd.getIdlType(), fd);
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    @Override
    public String toString() {
        return "EntityModel{modelName='" + modelName + "', idlModule='" + idlModule
                + "', entities=" + entities.size() + "}";
    }
}
