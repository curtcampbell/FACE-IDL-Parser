package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.ModelReader;
import com.warhex.er.generator.reader.dto.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * {@link ModelReader} implementation for {@code .face} XMI entity source files
 * produced by FACE-conformant modelling tools.
 *
 * <h2>Two-pass parsing strategy</h2>
 * <ol>
 *   <li><b>Pass 1</b> — performed by {@link FaceXmiDocument}: walks the entire
 *       document and builds a {@code Map<xmi:id, Element>} UUID map.</li>
 *   <li><b>Pass 2</b> — performed here: finds every {@code platform:Entity}
 *       element, resolves each composition's type UUID, and produces
 *       {@link EntityData} DTOs.</li>
 * </ol>
 *
 * <h2>What is mapped</h2>
 * <ul>
 *   <li>{@code platform:Entity} → one {@link EntityData} per entity</li>
 *   <li>{@code platform:Composition} → one {@link FieldData} per field</li>
 *   <li>{@code platform:Struct} → nested {@link FieldData} (recursive, via
 *       {@link FaceXmiDocument#resolveStructMembers})</li>
 *   <li>{@code platform:Enumeration} → {@link EnumData} via 5-hop logical
 *       chain (via {@link FaceXmiDocument#resolveOrCacheEnum})</li>
 *   <li>Primitive platform types → IDL type strings</li>
 *   <li>{@code platform:Fixed} → {@code "fixed<D,S>"} scoped string</li>
 *   <li>Entity-typed compositions → {@code "<name>Entity"} scoped reference</li>
 * </ul>
 *
 * <h2>No changes to downstream pipeline</h2>
 * The produced {@link IdlModelData} is consumed by
 * {@link com.warhex.er.generator.reader.EntityModelMapper}, which is
 * format-agnostic and requires no modifications.
 */
public class FaceXmiModelReader implements ModelReader {

    private static final Logger LOG = Logger.getLogger(FaceXmiModelReader.class.getName());

    // -------------------------------------------------------------------------
    // Per-parse state (reset on each call to read())
    // -------------------------------------------------------------------------

    /** Shared DOM parse result — UUID map, enum cache, and resolution helpers. */
    private FaceXmiDocument faceDoc;

    // -------------------------------------------------------------------------
    // ModelReader entry point
    // -------------------------------------------------------------------------

    @Override
    public IdlModelData read(Path source) throws Exception {
        faceDoc = new FaceXmiDocument(source);

        Element root = faceDoc.getRoot();
        // IDL-5: use datamodel:DataModel child element name, not root ArchitectureModel name
        String modelName = FaceXmiDocument.findModelName(root, "datamodel:DataModel", "platform:DataModel");
        if (modelName == null || modelName.isEmpty()) {
            modelName = root.getAttribute("name");
            LOG.warning("No datamodel:DataModel child found; using root name for model.");
        }
        if (modelName == null || modelName.isEmpty()) {
            modelName = "UnknownModel";
        }

        List<EntityData> entities = new ArrayList<>();
        collectEntities(root, entities);

        IdlModelData data = new IdlModelData();
        data.setModelName(modelName);
        data.setIdlModule("FACE.DM." + modelName);
        data.setTssIdlModule("FACE.TSS." + modelName);
        data.setCppNamespace("FACE::DM::" + modelName);
        data.setStructNamePattern("{name}Entity");
        data.setEntities(entities);
        return data;
    }

    // -------------------------------------------------------------------------
    // Pass 2: entity collection
    // -------------------------------------------------------------------------

    /**
     * Recursively walks {@code el}'s subtree and appends one {@link EntityData}
     * for each {@code platform:Entity} found.  Stops recursing into an entity
     * element itself since its children are compositions, not nested entities.
     */
    private void collectEntities(Element el, List<EntityData> entities) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        if ("platform:Entity".equals(xmiType)) {
            entities.add(buildEntity(el));
            return;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                collectEntities((Element) child, entities);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Entity builder
    // -------------------------------------------------------------------------

    private EntityData buildEntity(Element entityEl) {
        String name        = entityEl.getAttribute("name");
        String description = entityEl.getAttribute("description");

        List<EnumData>  enums  = new ArrayList<>();
        List<FieldData> fields = new ArrayList<>();

        NodeList children = entityEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;

            String childType = childEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
            if (!"platform:Composition".equals(childType)) continue;

            String rolename = childEl.getAttribute("rolename");
            String typeUuid = childEl.getAttribute("type");
            if (typeUuid == null || typeUuid.isEmpty()) continue;

            Element typeEl = faceDoc.getUuidMap().get(typeUuid);
            if (typeEl == null) continue;

            FieldData field = resolveCompositionType(rolename, typeUuid, typeEl, enums);
            if (field != null) {
                fields.add(field);
            }
        }

        EntityData entity = new EntityData();
        entity.setSimpleName(name);
        entity.setDescription(description);
        entity.setSupportingEnums(enums);
        entity.setFields(fields);
        return entity;
    }

    // -------------------------------------------------------------------------
    // Composition type resolver
    // -------------------------------------------------------------------------

    /**
     * Resolves the IDL type of a {@code platform:Composition} and returns a
     * populated {@link FieldData}, or {@code null} if the type is unknown /
     * unsupported (e.g., conceptual-layer references).
     */
    private FieldData resolveCompositionType(String rolename,
                                             String typeUuid,
                                             Element typeEl,
                                             List<EnumData> entityEnums) {
        String xmiType = typeEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        FieldData field = new FieldData();
        field.setName(rolename);

        switch (xmiType) {

            case "platform:Entity": {
                String entityName = typeEl.getAttribute("name");
                field.setNested(false);
                field.setIdlType(entityName + "Entity");
                return field;
            }

            case "platform:Struct": {
                String structName    = typeEl.getAttribute("name");
                List<FieldData> subs = faceDoc.resolveStructMembers(
                        typeEl, entityEnums, new HashSet<>());
                field.setNested(true);
                field.setIdlType(structName);
                field.setNestedFields(subs);
                return field;
            }

            case "platform:Enumeration": {
                EnumData enumData = faceDoc.resolveOrCacheEnum(typeUuid, typeEl);
                if (enumData == null) return null;
                FaceXmiDocument.addEnumIfAbsent(enumData, entityEnums);
                field.setNested(false);
                field.setIdlType(enumData.getName());
                return field;
            }

            default: {
                FaceXmiDocument.PlatformTypeResult ptr =
                        FaceXmiDocument.resolvePrimitiveIdlType(xmiType, typeEl);
                if (ptr == null) {
                    LOG.warning("Unsupported platform type '" + xmiType
                        + "' for field '" + rolename + "' — field omitted.");
                    return null;
                }
                field.setNested(false);
                field.setIdlType(ptr.idlType);
                field.setArrayDimension(ptr.arrayDimension);
                return field;
            }
        }
    }
}
