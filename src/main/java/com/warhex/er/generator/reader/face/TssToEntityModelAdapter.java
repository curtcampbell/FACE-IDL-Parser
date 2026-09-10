package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.EntityData;
import com.warhex.er.generator.reader.dto.FieldData;
import com.warhex.er.generator.reader.dto.IdlModelData;
import com.warhex.er.generator.reader.dto.TssTypeData;
import com.warhex.er.generator.reader.dto.UoPModelData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapts a {@link UoPModelData} (produced by {@link FaceTssReader}) into an
 * {@link IdlModelData} so that the existing IDL generator pipeline can consume
 * it without modification.
 *
 * <h2>Mapping</h2>
 * <pre>
 *   UoPModelData.platformTypes  →  IdlModelData.entities
 *   TssTypeData.name            →  EntityData.simpleName
 *   TssTypeData.isUnion         →  EntityData.isUnion   (Phase 1)
 *   TssTypeData.fields          →  EntityData.fields    (FieldData is shared)
 * </pre>
 *
 * <p>Nested struct fields ({@link com.warhex.er.generator.reader.dto.FieldData#isNested()
 * nested=true}) are kept inline with their {@code nestedFields} list populated.
 * Downstream deduplication (struct emitted once per IDL file, sub-structs before their
 * parent) is handled by
 * {@link com.warhex.er.generator.reader.ModelToIdlAdapter#collectNestedStructsRecursive}
 * during AST assembly — no second-pass extraction is needed here.
 *
 * <h2>IDL module derivation</h2>
 * <ul>
 *   <li>{@code idlModule}    — taken directly from {@link UoPModelData#getIdlModule()}</li>
 *   <li>{@code tssIdlModule} — {@code idlModule} with {@code ".DM."} replaced by
 *       {@code ".TSS."}</li>
 *   <li>{@code cppNamespace} — {@code idlModule} with {@code "."} replaced by
 *       {@code "::"}</li>
 * </ul>
 *
 * <h2>Struct name pattern</h2>
 * Set to {@code "{name}"} (identity) because FACE template/composite-template names
 * are already the intended IDL struct names — no {@code "Entity"} suffix is appended.
 *
 * <h2>Pipeline reuse</h2>
 * The returned {@link IdlModelData} flows through the unmodified chain:
 * <pre>
 *   IdlModelData
 *     → EntityModelMapper.map()
 *     → EntityModel
 *     → IdlGeneratorPipeline  (Step 1 — data-model/ IDL files)
 *     → IdlDirectoryParser    (Step 2 — language bindings)
 * </pre>
 */
public class TssToEntityModelAdapter {

    /**
     * Converts {@code uoPModel} into an {@link IdlModelData} for the IDL
     * generator pipeline.
     *
     * @param uoPModel populated {@link UoPModelData}; must not be {@code null}
     * @return {@link IdlModelData} ready for {@link com.warhex.er.generator.reader.EntityModelMapper}
     */
    public IdlModelData adapt(UoPModelData uoPModel) {
        IdlModelData data = new IdlModelData();

        data.setModelName(uoPModel.getModelName());

        String idlModule = uoPModel.getIdlModule();
        data.setIdlModule(idlModule);
        data.setTssIdlModule(deriveTssModule(idlModule));
        data.setCppNamespace(idlModule != null ? idlModule.replace(".", "::") : null);

        // TSS template names are the struct names — no suffix pattern needed
        data.setStructNamePattern("{name}");

        // Convert each platform type to an EntityData entry
        List<EntityData> entities = new ArrayList<>();
        for (TssTypeData tsd : uoPModel.getPlatformTypes()) {
            entities.add(adaptType(tsd));
        }
        data.setEntities(entities);

        // IDL-4: populate cross-template #include directives.
        // Build a set of all known template / composite-template type names —
        // each of these has its own generated IDL file.
        Set<String> typeNames = new LinkedHashSet<>();
        for (TssTypeData tsd : uoPModel.getPlatformTypes()) {
            typeNames.add(tsd.getName());
        }
        // Second pass: for each entity, collect includes for fields whose
        // idlType resolves to another template type in this model.
        // Self-references and already-included types are deduplicated via
        // LinkedHashSet (preserves first-seen order).
        for (EntityData ed : entities) {
            Set<String> includes = new LinkedHashSet<>();
            if (ed.getFields() != null) {
                for (FieldData fd : ed.getFields()) {
                    String fieldType = fd.getIdlType();
                    if (fieldType != null
                            && typeNames.contains(fieldType)
                            && !fieldType.equals(ed.getSimpleName())) {
                        includes.add(idlModule.replace('.', '/') + "/" + fieldType + ".idl");
                    }
                    // IDL-8: named platform typedef → carry its precomputed include path
                    String platformInc = fd.getIdlIncludePath();
                    if (platformInc != null && !platformInc.isEmpty()) {
                        includes.add(platformInc);
                    }
                }
            }
            if (!includes.isEmpty()) {
                ed.setIncludes(new ArrayList<>(includes));
            }
        }

        // Supporting structs: none extracted here — nested struct deduplication
        // is handled downstream by ModelToIdlAdapter during AST building.
        data.setSupportingStructs(new ArrayList<>());

        return data;
    }

    // -------------------------------------------------------------------------
    // Package-private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts one {@link TssTypeData} to an {@link EntityData}.
     *
     * <p>Package-private so that {@link FaceTemplateEntityReader} (same package)
     * can reuse this conversion without duplication.
     *
     * <p>Fields are shared by reference — {@link com.warhex.er.generator.reader.dto.FieldData}
     * instances are not copied.  The pipeline only reads them, so aliasing is safe.
     */
    EntityData adaptType(TssTypeData tsd) {
        EntityData ed = new EntityData();
        ed.setSimpleName(tsd.getName());
        ed.setUnion(tsd.isUnion());
        // IDL-1: propagate CompositeTemplate flag so the generator can decide
        // whether to emit the T_ module wrapper and typedef.
        ed.setCompositeTemplate(tsd.isCompositeTemplate());
        // Fields are the same FieldData instances produced by FaceTssReader —
        // pass through directly (nested fields remain inline).
        ed.setFields(tsd.getFields() != null ? tsd.getFields() : new ArrayList<>());
        // No description, qualifiedPath, includes, or supportingEnums from TSS types
        return ed;
    }

    /**
     * Derives the TSS IDL module from the DM module by replacing {@code ".DM."}
     * with {@code ".TSS."}.  Falls back to appending {@code ".TSS"} if the
     * {@code ".DM."} marker is absent.
     */
    private static String deriveTssModule(String idlModule) {
        if (idlModule == null) return null;
        if (idlModule.contains(".DM.")) {
            return idlModule.replace(".DM.", ".TSS.");
        }
        return idlModule + ".TSS";
    }
}
