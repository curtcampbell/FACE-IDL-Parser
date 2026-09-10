package com.warhex.er.generator.reader.yaml;

import com.warhex.er.generator.reader.ModelReader;
import com.warhex.er.generator.reader.dto.*;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ModelReader} implementation for YAML entity source files.
 *
 * <h2>Expected YAML structure</h2>
 * <pre>
 * model_name: SampleModel
 * idl_module: FACE.DM.SampleModel
 * tss_idl_module: FACE.TSS.SampleModel   # optional; derived from idl_module if absent
 * cpp_namespace: FACE::DM::SampleModel
 * struct_name_pattern: "{name}Entity"     # optional; default is "{name}Entity"
 *
 * # Shared value-type structs referenced by entity fields.
 * # An entity field with nested: true and no nested_fields resolves its
 * # sub-field list from here by matching idl_type to name.
 * supporting_structs:
 *   - name: GeoPosition
 *     comment: "WGS-84 geographic position"
 *     fields:
 *       - name: latitude
 *         idl_type: double
 *         attribute_value_type: AV_DOUBLE
 *         comment: "Degrees WGS-84, range -90.0 .. +90.0"
 *
 * entities:
 *   - simple_name: Track
 *     qualified_path: FACE.DM.SampleModel.Track   # optional
 *     description: "Tracked object with position and classification."
 *     includes:
 *       - GeoPosition.idl
 *     supporting_enums:
 *       - name: ThreatLevel
 *         comment: "Classification of perceived threat."
 *         values:
 *           - name: THREAT_UNKNOWN
 *             comment: "0 — Classification not available"
 *           - name: THREAT_NEUTRAL
 *             comment: "1 — Non-hostile entity"
 *     fields:
 *       - name: entityId
 *         idl_type: "::FACE::GUID_TYPE"
 *         attribute_value_type: AV_GUID
 *         comment: "Unique entity identifier."
 *       - name: position
 *         idl_type: GeoPosition
 *         nested: true
 *         nested_fields:
 *           - name: latitude
 *             idl_type: float
 *             attribute_value_type: AV_FLOAT
 *             comment: "Latitude in decimal degrees."
 * </pre>
 *
 * <p>All keys use {@code snake_case} to avoid SnakeYAML's camelCase mapping
 * conventions.  Values are read into raw {@code Map&lt;String, Object&gt;}
 * trees rather than bound POJOs.
 */
public class YamlModelReader implements ModelReader {

    @Override
    public IdlModelData read(Path source) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(source)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(in);
            return parseRoot(root);
        }
    }

    // -----------------------------------------------------------------------
    // Parse helpers
    // -----------------------------------------------------------------------

    private IdlModelData parseRoot(Map<String, Object> root) {
        IdlModelData data = new IdlModelData();

        data.setModelName(str(root, "model_name"));
        data.setIdlModule(str(root, "idl_module"));
        data.setTssIdlModule(str(root, "tss_idl_module"));
        data.setCppNamespace(str(root, "cpp_namespace"));
        data.setStructNamePattern(str(root, "struct_name_pattern"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structList =
                (List<Map<String, Object>>) root.get("supporting_structs");
        if (structList != null) {
            List<FieldData> structs = new ArrayList<>();
            for (Map<String, Object> sMap : structList) {
                structs.add(parseSupportingStruct(sMap));
            }
            data.setSupportingStructs(structs);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entityList =
                (List<Map<String, Object>>) root.get("entities");

        if (entityList != null) {
            List<EntityData> entities = new ArrayList<>();
            for (Map<String, Object> eMap : entityList) {
                entities.add(parseEntity(eMap));
            }
            data.setEntities(entities);
        }

        return data;
    }

    /**
     * Parses a {@code supporting_structs} entry.
     *
     * <pre>
     * - name: GeoPosition
     *   comment: "WGS-84 geographic position"
     *   fields:
     *     - name: latitude
     *       idl_type: double
     *       attribute_value_type: AV_DOUBLE
     *       comment: "Degrees WGS-84, range -90.0 .. +90.0"
     * </pre>
     *
     * <p>The result is a {@link FieldData} with {@code nested=true} and
     * {@code idlType} set to the struct name so that existing nested-struct
     * handling code treats it uniformly.
     */
    private FieldData parseSupportingStruct(Map<String, Object> sMap) {
        FieldData fd = new FieldData();
        fd.setIdlType(str(sMap, "name"));   // struct name → idlType (dedup key)
        fd.setComment(str(sMap, "comment"));
        fd.setNested(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldList =
                (List<Map<String, Object>>) sMap.get("fields");
        if (fieldList != null) {
            List<FieldData> fields = new ArrayList<>();
            for (Map<String, Object> fMap : fieldList) {
                fields.add(parseField(fMap));
            }
            fd.setNestedFields(fields);
        }

        return fd;
    }

    private EntityData parseEntity(Map<String, Object> eMap) {
        EntityData ed = new EntityData();

        ed.setSimpleName(str(eMap, "simple_name"));
        ed.setQualifiedPath(str(eMap, "qualified_path"));
        ed.setDescription(str(eMap, "description"));

        @SuppressWarnings("unchecked")
        List<String> includes = (List<String>) eMap.get("includes");
        ed.setIncludes(includes);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enumList =
                (List<Map<String, Object>>) eMap.get("supporting_enums");
        if (enumList != null) {
            List<EnumData> enums = new ArrayList<>();
            for (Map<String, Object> enumMap : enumList) {
                enums.add(parseEnum(enumMap));
            }
            ed.setSupportingEnums(enums);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldList =
                (List<Map<String, Object>>) eMap.get("fields");
        if (fieldList != null) {
            List<FieldData> fields = new ArrayList<>();
            for (Map<String, Object> fMap : fieldList) {
                fields.add(parseField(fMap));
            }
            ed.setFields(fields);
        }

        return ed;
    }

    private FieldData parseField(Map<String, Object> fMap) {
        FieldData fd = new FieldData();

        fd.setName(str(fMap, "name"));
        fd.setIdlType(str(fMap, "idl_type"));
        fd.setAttributeValueType(str(fMap, "attribute_value_type"));
        fd.setComment(str(fMap, "comment"));

        Object nestedObj = fMap.get("nested");
        boolean nested = nestedObj instanceof Boolean && (Boolean) nestedObj;
        fd.setNested(nested);

        if (nested) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nestedList =
                    (List<Map<String, Object>>) fMap.get("nested_fields");
            if (nestedList != null) {
                List<FieldData> nestedFields = new ArrayList<>();
                for (Map<String, Object> nfMap : nestedList) {
                    nestedFields.add(parseField(nfMap));
                }
                fd.setNestedFields(nestedFields);
            }
        }

        return fd;
    }

    private EnumData parseEnum(Map<String, Object> enumMap) {
        EnumData ed = new EnumData();

        ed.setName(str(enumMap, "name"));
        ed.setComment(str(enumMap, "comment"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valueList =
                (List<Map<String, Object>>) enumMap.get("values");
        if (valueList != null) {
            List<EnumValueData> values = new ArrayList<>();
            for (Map<String, Object> vMap : valueList) {
                EnumValueData vd = new EnumValueData();
                vd.setName(str(vMap, "name"));
                vd.setComment(str(vMap, "comment"));
                values.add(vd);
            }
            ed.setValues(values);
        }

        return ed;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /** Safe string extraction from a map; returns {@code null} when absent. */
    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
