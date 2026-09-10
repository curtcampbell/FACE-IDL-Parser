package com.warhex.er.generator.reader;

import com.warhex.er.generator.model.*;
import com.warhex.er.generator.reader.dto.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a format-neutral {@link IdlModelData} DTO into the domain
 * {@link EntityModel} used by the Velocity pipeline.
 *
 * <h2>Enrichment applied here</h2>
 * <ul>
 *   <li>Entities are sorted alphabetically by {@code simpleName}.</li>
 *   <li>{@code typeEnumValue} is assigned 1-based in alphabetical order.</li>
 *   <li>Scalar fields are assigned monotonically-increasing {@code positionIndex}
 *       values (1-based) in declaration order.  Nested struct fields are not
 *       assigned a position index themselves; their leaf children are.</li>
 *   <li>{@code qualifiedPath} is synthesised when absent from the source file.</li>
 *   <li>{@code structNamePattern} defaults to {@code "{name}Entity"} when absent.</li>
 *   <li>{@code tssIdlModule} defaults to the DM module with {@code "DM"} replaced
 *       by {@code "TSS"} when absent.</li>
 * </ul>
 */
public class EntityModelMapper {

    /**
     * Converts {@code data} into a fully enriched {@link EntityModel}.
     *
     * @param data populated {@link IdlModelData} from a {@link ModelReader}; never {@code null}
     * @return domain model ready for the Velocity pipeline
     */
    public EntityModel map(IdlModelData data) {
        EntityModel model = new EntityModel();

        model.setModelName(data.getModelName());
        model.setIdlModule(data.getIdlModule());
        model.setCppNamespace(data.getCppNamespace());

        // Default struct name pattern
        String pattern = data.getStructNamePattern();
        model.setStructNamePattern(pattern != null ? pattern : "{name}Entity");

        // Default TSS module: replace first occurrence of ".DM." with ".TSS."
        String tssModule = data.getTssIdlModule();
        if (tssModule == null || tssModule.isEmpty()) {
            tssModule = data.getIdlModule().replace(".DM.", ".TSS.");
        }
        model.setTssIdlModule(tssModule);

        // Map explicit supporting structs first so the lookup map is available
        // when entity fields are resolved.
        List<FieldDescriptor> supportingStructs = new ArrayList<>();
        Map<String, FieldDescriptor> structLookup = new LinkedHashMap<>();
        for (FieldData ssData : data.getSupportingStructs()) {
            FieldDescriptor ss = mapSupportingStruct(ssData);
            supportingStructs.add(ss);
            structLookup.put(ss.getIdlType(), ss);
        }
        model.setSupportingStructs(supportingStructs);

        // Sort entities alphabetically, then assign 1-based typeEnumValue
        List<EntityData> sortedEntities = new ArrayList<>(data.getEntities());
        sortedEntities.sort(Comparator.comparing(EntityData::getSimpleName,
                String.CASE_INSENSITIVE_ORDER));

        List<EntityDescriptor> descriptors = new ArrayList<>();
        int enumCounter = 1;
        for (EntityData ed : sortedEntities) {
            EntityDescriptor desc = mapEntity(ed, data.getIdlModule(), enumCounter++,
                    structLookup);
            descriptors.add(desc);
        }
        model.setEntities(descriptors);

        return model;
    }

    /**
     * Maps a supporting-struct {@link FieldData} (with {@code nested=true} and
     * {@code idlType} = struct name) to a {@link FieldDescriptor} whose
     * {@code nestedFields} hold the struct's own fields.  No {@code positionIndex}
     * is assigned — supporting struct descriptors are not entity fields.
     */
    private FieldDescriptor mapSupportingStruct(FieldData fd) {
        FieldDescriptor ss = new FieldDescriptor();
        ss.setIdlType(fd.getIdlType());
        ss.setComment(fd.getComment());
        ss.setNested(true);

        List<FieldDescriptor> fields = new ArrayList<>();
        int[] pos = {1};
        for (FieldData child : fd.getNestedFields()) {
            fields.add(mapField(child, pos));
        }
        ss.setNestedFields(fields);
        return ss;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private EntityDescriptor mapEntity(EntityData ed, String idlModule, int enumValue,
                                       Map<String, FieldDescriptor> structLookup) {
        EntityDescriptor desc = new EntityDescriptor();

        desc.setSimpleName(ed.getSimpleName());
        desc.setDescription(ed.getDescription());
        desc.setTypeEnumValue(enumValue);
        desc.setUnion(ed.isUnion());
        // IDL-1: carry the CompositeTemplate flag to the descriptor
        desc.setCompositeTemplate(ed.isCompositeTemplate());

        // Synthesise qualified path if not provided
        String qp = ed.getQualifiedPath();
        if (qp == null || qp.isEmpty()) {
            qp = idlModule + "." + ed.getSimpleName();
        }
        desc.setQualifiedPath(qp);

        desc.setIncludes(ed.getIncludes() != null
                ? new ArrayList<>(ed.getIncludes())
                : new ArrayList<>());

        // Supporting enums
        List<EnumDescriptor> enums = new ArrayList<>();
        if (ed.getSupportingEnums() != null) {
            for (EnumData enumData : ed.getSupportingEnums()) {
                enums.add(mapEnum(enumData));
            }
        }
        desc.setSupportingEnums(enums);

        // Fields — assign positionIndex to scalar (non-nested) leaf fields
        int[] posCounter = {1};   // single-element array so lambda can mutate it
        List<FieldDescriptor> fields = new ArrayList<>();
        if (ed.getFields() != null) {
            for (FieldData fd : ed.getFields()) {
                FieldDescriptor mapped = mapField(fd, posCounter);
                // If a nested field omits inline nested_fields, resolve from the
                // supporting_structs lookup so downstream code (IDL generator,
                // ModelToIdlAdapter) has the struct's fields available.
                if (mapped.isNested() && mapped.getNestedFields().isEmpty()
                        && structLookup.containsKey(mapped.getIdlType())) {
                    mapped.setNestedFields(
                            structLookup.get(mapped.getIdlType()).getNestedFields());
                }
                fields.add(mapped);
            }
        }
        desc.setFields(fields);

        return desc;
    }

    /**
     * Maps a single {@link FieldData} to a {@link FieldDescriptor}.
     *
     * <p>For nested fields the parent is not assigned a position index; the
     * recursive call processes the leaf children and increments the counter.
     *
     * @param fd         raw field DTO
     * @param posCounter single-element array used as a mutable counter
     * @return populated descriptor
     */
    private FieldDescriptor mapField(FieldData fd, int[] posCounter) {
        FieldDescriptor field = new FieldDescriptor();
        field.setName(fd.getName());
        field.setIdlType(fd.getIdlType());
        field.setArrayDimension(fd.getArrayDimension());
        field.setOptional(fd.isOptional());           // IDL-7
        field.setAttributeValueType(fd.getAttributeValueType());
        field.setComment(fd.getComment());
        field.setNested(fd.isNested());

        if (fd.isNested()) {
            // Nested struct: recurse into children, no positionIndex on parent
            List<FieldDescriptor> nested = new ArrayList<>();
            if (fd.getNestedFields() != null) {
                for (FieldData child : fd.getNestedFields()) {
                    nested.add(mapField(child, posCounter));
                }
            }
            field.setNestedFields(nested);
        } else {
            // Scalar leaf: assign next position index
            field.setPositionIndex(posCounter[0]++);
        }

        return field;
    }

    private EnumDescriptor mapEnum(EnumData ed) {
        EnumDescriptor enumDesc = new EnumDescriptor();
        enumDesc.setName(ed.getName());
        enumDesc.setComment(ed.getComment());

        List<EnumValueDescriptor> values = new ArrayList<>();
        if (ed.getValues() != null) {
            for (EnumValueData vd : ed.getValues()) {
                EnumValueDescriptor v = new EnumValueDescriptor();
                v.setName(vd.getName());
                v.setComment(vd.getComment());
                values.add(v);
            }
        }
        enumDesc.setValues(values);

        return enumDesc;
    }
}
