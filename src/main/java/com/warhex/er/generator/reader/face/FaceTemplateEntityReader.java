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
 * Reads {@code uop:Template} and {@code uop:CompositeTemplate} elements from a
 * named {@code um:UoPModel} group in a {@code .face} XMI file and produces an
 * {@link IdlModelData} for the entity-reactor IDL pipeline.
 *
 * <h2>Purpose</h2>
 * <p>Allows modelers to designate a specific {@code um:UoPModel} group (default name:
 * {@value #DEFAULT_GROUP_NAME}) as the entity source for entity-reactor IDL generation,
 * in place of {@code platform:Entity} elements.
 *
 * <h2>Struct name pattern</h2>
 * <p>Sets {@code structNamePattern = "{name}Entity"}, matching the existing
 * entity-reactor path so the downstream Velocity templates produce the same output.
 *
 * <h2>Two-pass resolution</h2>
 * <ol>
 *   <li>Pass 1 — registers every direct {@code <element>} child of the named group
 *       whose {@code xmi:type} is {@code uop:Template} or {@code uop:CompositeTemplate}.</li>
 *   <li>Pass 2 — resolves fields: Templates via {@code boundQuery} spec parsing;
 *       CompositeTemplates via their {@code uop:TemplateComposition} children.
 *       {@code boundQuery} resolution uses the full document UUID map, so cross-group
 *       references resolve correctly.</li>
 * </ol>
 *
 * <h2>Key difference from {@link FaceTssReader}</h2>
 * <table border="1">
 *   <tr><th>Concern</th><th>FaceTssReader</th><th>FaceTemplateEntityReader</th></tr>
 *   <tr><td>Scope</td><td>All templates in entire &lt;um&gt; subtree</td>
 *       <td>Only templates in named group</td></tr>
 *   <tr><td>UoP collection</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Output type</td><td>UoPModelData</td><td>IdlModelData directly</td></tr>
 *   <tr><td>Struct name pattern</td><td>{name} (set by adapter)</td>
 *       <td>{name}Entity</td></tr>
 *   <tr><td>Purpose</td><td>TSS pipeline</td><td>Entity reactor pipeline</td></tr>
 * </table>
 *
 * <p>{@link FaceTssReader} is <strong>not modified</strong>. It continues to serve
 * the TSS pipeline unchanged.
 */
public class FaceTemplateEntityReader implements ModelReader {

    /** Default group name used when no explicit group is specified via CLI. */
    public static final String DEFAULT_GROUP_NAME = "EntityReactorTemplates";

    private static final Logger LOG =
            Logger.getLogger(FaceTemplateEntityReader.class.getName());

    private final String groupName;

    // -------------------------------------------------------------------------
    // Per-read state (reset on each call to read())
    // -------------------------------------------------------------------------

    private FaceXmiDocument faceDoc;

    /**
     * Registry of Templates and CompositeTemplates in the named group,
     * keyed by {@code xmi:id}.  Populated in Pass 1; fields filled in Pass 2.
     */
    private final Map<String, TssTypeData> typeById = new LinkedHashMap<>();

    /**
     * Name→Element cache for platform-layer entities and associations.
     * Used to resolve column references in platform:Query spec strings.
     * Built lazily on first use.
     */
    private Map<String, Element> platformEntityByName;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a reader that looks for the given group name in the {@code .face} file.
     *
     * @param groupName the {@code name} attribute of the {@code um:UoPModel} group
     *                  to search; must not be {@code null} or empty
     */
    public FaceTemplateEntityReader(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            throw new IllegalArgumentException("groupName must not be null or empty");
        }
        this.groupName = groupName;
    }

    /**
     * Creates a reader using the default group name {@value #DEFAULT_GROUP_NAME}.
     */
    public FaceTemplateEntityReader() {
        this(DEFAULT_GROUP_NAME);
    }

    // -------------------------------------------------------------------------
    // ModelReader implementation
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code .face} file at {@code source}, locates the named
     * {@code um:UoPModel} group, registers all template elements inside it,
     * resolves their fields, and returns an {@link IdlModelData} for the
     * entity-reactor IDL pipeline.
     *
     * @param source path to the {@code .face} XMI file
     * @return populated {@link IdlModelData}; never {@code null}
     * @throws IllegalArgumentException if the named group is not found in the file
     * @throws Exception if the file cannot be parsed
     */
    @Override
    public IdlModelData read(Path source) throws Exception {
        faceDoc = new FaceXmiDocument(source);
        typeById.clear();
        platformEntityByName = null;

        Element root = faceDoc.getRoot();
        String modelName = root.getAttribute("name");
        if (modelName == null || modelName.isEmpty()) modelName = "UnknownModel";

        // Locate the named um:UoPModel group
        Element groupEl = findGroupElement(root);
        if (groupEl == null) {
            throw new IllegalArgumentException(
                    "No um:UoPModel group named '" + groupName
                    + "' found in " + source.getFileName());
        }

        // Pass 1: register template elements that are direct children of the group
        registerTemplatesInGroup(groupEl);

        // Pass 2: resolve fields for each registered type
        resolveAllFields(groupEl);

        // Build IdlModelData
        IdlModelData data = new IdlModelData();
        data.setModelName(modelName);
        data.setIdlModule("FACE.DM." + modelName);
        data.setTssIdlModule("FACE.TSS." + modelName);
        data.setCppNamespace("FACE::DM::" + modelName);
        data.setStructNamePattern("{name}Entity");

        TssToEntityModelAdapter adapter = new TssToEntityModelAdapter();
        List<EntityData> entities = new ArrayList<>();
        for (TssTypeData tsd : typeById.values()) {
            entities.add(adapter.adaptType(tsd));
        }
        data.setEntities(entities);
        data.setSupportingStructs(new ArrayList<>());

        if (entities.isEmpty()) {
            LOG.warning("Template group '" + groupName
                    + "' was found but contains no uop:Template or"
                    + " uop:CompositeTemplate children.");
        }

        return data;
    }

    // =========================================================================
    // Group discovery
    // =========================================================================

    /**
     * Walks the entire document tree recursively to find the first
     * {@code uop:UoPModel} element whose {@code name} attribute equals
     * {@link #groupName}.  Returns {@code null} if not found.
     */
    private Element findGroupElement(Element root) {
        return findGroupRecursive(root);
    }

    private Element findGroupRecursive(Element el) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        if ("uop:UoPModel".equals(xmiType)) {
            if (groupName.equals(el.getAttribute("name"))) {
                return el;
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findGroupRecursive((Element) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // =========================================================================
    // Pass 1: register Templates and CompositeTemplates
    // =========================================================================

    /**
     * Iterates the direct children of {@code groupEl} and registers every
     * {@code <element>} child whose {@code xmi:type} is {@code uop:Template}
     * or {@code uop:CompositeTemplate}.
     */
    private void registerTemplatesInGroup(Element groupEl) {
        NodeList children = groupEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;

            String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
            if (!"uop:Template".equals(xmiType) && !"uop:CompositeTemplate".equals(xmiType)) {
                continue;
            }

            String id   = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            String name = el.getAttribute("name");

            TssTypeData tsd = new TssTypeData();
            tsd.setName(name);
            tsd.setUuid(id);

            if ("uop:CompositeTemplate".equals(xmiType)) {
                String isUnionAttr = el.getAttribute("isUnion");
                tsd.setUnion("true".equalsIgnoreCase(isUnionAttr));
            }

            typeById.put(id, tsd);
        }
    }

    // =========================================================================
    // Pass 2: resolve fields
    // =========================================================================

    /**
     * Iterates the direct children of {@code groupEl} and resolves fields for
     * every registered {@link TssTypeData}.
     */
    private void resolveAllFields(Element groupEl) {
        NodeList children = groupEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;

            String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
            String id      = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            TssTypeData tsd = typeById.get(id);
            if (tsd == null) continue;

            if ("uop:Template".equals(xmiType)) {
                tsd.setFields(resolveTemplateFields(el));
            } else if ("uop:CompositeTemplate".equals(xmiType)) {
                tsd.setFields(resolveCompositeTemplateFields(el));
            }
        }
    }

    // =========================================================================
    // Template field resolution via boundQuery spec parsing
    // =========================================================================

    /**
     * Resolves the IDL fields for a {@code uop:Template}.
     *
     * <p>Primary path: parses the {@code uop:Template.specification} DSL (field aliases).
     * Fallback: parses the {@code platform:Query.specification} SELECT clause.
     *
     * <p>Uses the full document UUID map so cross-group query references resolve
     * correctly.
     */
    private List<FieldData> resolveTemplateFields(Element templateEl) {
        String boundQueryUuid = templateEl.getAttribute("boundQuery");
        if (boundQueryUuid == null || boundQueryUuid.isEmpty()) {
            boundQueryUuid = templateEl.getAttribute("effectiveQuery");
        }

        // Build entity map from the platform:Query FROM clause (used by both paths)
        Map<String, Element> entityMap = new LinkedHashMap<>();
        Element queryEl = boundQueryUuid != null && !boundQueryUuid.isEmpty()
                ? faceDoc.getUuidMap().get(boundQueryUuid) : null;
        if (queryEl != null) {
            String querySpec  = queryEl.getAttribute("specification");
            String lowerQuery = querySpec != null ? querySpec.toLowerCase() : "";
            int fromIdx = lowerQuery.indexOf(" from ");
            if (fromIdx >= 0) {
                entityMap = buildEntityMapFromFromClause(querySpec.substring(fromIdx + 6).trim());
            }
        }

        // Primary path: parse uop:Template.specification
        String templateSpec = templateEl.getAttribute("specification");
        if (templateSpec != null && !templateSpec.isEmpty()) {
            List<FieldData> fields = parseTemplateSpecFields(templateSpec, entityMap);
            if (!fields.isEmpty()) return fields;
        }

        // Fallback: parse platform:Query.specification SELECT clause
        if (queryEl != null) return resolveQueryFields(queryEl);
        return new ArrayList<>();
    }

    /**
     * Parses the {@code uop:Template.specification} DSL and returns one
     * {@link FieldData} per field declared in the {@code main(...){}} block.
     *
     * <h3>Syntax handled</h3>
     * <pre>
     *   [using OtherTemplate;]*
     *   main(RoleAlias) {
     *     rolename as alias;
     *     @optional rolename as alias;
     *     OtherTemplateName(RoleAlias) alias;
     *   }
     * </pre>
     */
    private List<FieldData> parseTemplateSpecFields(String templateSpec,
                                                     Map<String, Element> entityMap) {
        List<FieldData> fields = new ArrayList<>();
        List<EnumData>  enums  = new ArrayList<>();

        int mainIdx = templateSpec.indexOf("main(");
        if (mainIdx < 0) return fields;

        int openBrace = templateSpec.indexOf('{', mainIdx);
        if (openBrace < 0) return fields;

        int depth = 1;
        int pos   = openBrace + 1;
        while (pos < templateSpec.length() && depth > 0) {
            char c = templateSpec.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        if (depth != 0) return fields;

        String mainBody = templateSpec.substring(openBrace + 1, pos - 1);

        for (String stmt : mainBody.split(";")) {
            String line = stmt.trim();
            if (line.isEmpty()) continue;

            // Strip leading @ markers (e.g. @optional, @key)
            if (line.startsWith("@")) {
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) continue;
                line = line.substring(spaceIdx + 1).trim();
                if (line.isEmpty()) continue;
            }

            // Pattern A: "TypeName(Role) alias" — cross-reference to another template
            int parenStart = line.indexOf('(');
            if (parenStart >= 0 && !line.substring(0, parenStart).trim().isEmpty()) {
                int parenEnd = line.indexOf(')', parenStart);
                if (parenEnd >= 0) {
                    String typeName   = line.substring(0, parenStart).trim();
                    String afterParen = line.substring(parenEnd + 1).trim();
                    String alias = afterParen.isEmpty()
                            ? typeName.substring(0, 1).toLowerCase() + typeName.substring(1)
                            : afterParen;
                    TssTypeData ref = findTypeByName(typeName);
                    if (ref != null) {
                        FieldData fd = new FieldData();
                        fd.setName(alias);
                        fd.setIdlType(ref.getName());
                        fd.setNested(false);
                        fields.add(fd);
                    }
                }
                continue;
            }

            // Pattern B: "rolename as alias"
            String lowerLine = line.toLowerCase();
            int asIdx = lowerLine.lastIndexOf(" as ");
            if (asIdx < 0) continue;

            String rolename = line.substring(0, asIdx).trim();
            String alias    = line.substring(asIdx + 4).trim();
            if (rolename.isEmpty() || alias.isEmpty()) continue;

            FieldData fd = resolveRolenameField(rolename, alias, entityMap, enums);
            if (fd != null) fields.add(fd);
        }

        return fields;
    }

    /**
     * Looks up {@code rolename} as a child composition of each entity in
     * {@code entityMap} and returns a {@link FieldData} with the given {@code alias},
     * or {@code null} if the rolename cannot be resolved in any entity.
     */
    private FieldData resolveRolenameField(String rolename,
                                            String alias,
                                            Map<String, Element> entityMap,
                                            List<EnumData> enumAccum) {
        for (Element entityEl : entityMap.values()) {
            Element compositionEl = findChildByRolename(entityEl, rolename);
            if (compositionEl == null) continue;

            String typeUuid = compositionEl.getAttribute("type");
            if (typeUuid == null || typeUuid.isEmpty()) continue;

            Element typeEl = faceDoc.getUuidMap().get(typeUuid);
            if (typeEl == null) continue;

            return buildFieldFromTypeElement(alias, typeUuid, typeEl, enumAccum);
        }
        return null;
    }

    /**
     * Finds a registered {@link TssTypeData} by name (not UUID).
     * Used when resolving cross-template references in the template specification.
     */
    private TssTypeData findTypeByName(String name) {
        for (TssTypeData tsd : typeById.values()) {
            if (name.equals(tsd.getName())) return tsd;
        }
        return null;
    }

    /**
     * Parses a {@code platform:Query} element's {@code specification} attribute
     * (SQL-like SELECT string) and returns one {@link FieldData} per selected column.
     */
    private List<FieldData> resolveQueryFields(Element queryEl) {
        String spec = queryEl.getAttribute("specification");
        if (spec == null || spec.isEmpty()) return new ArrayList<>();

        String specLower = spec.toLowerCase();
        int selectIdx = specLower.indexOf("select ");
        int fromIdx   = specLower.indexOf(" from ");
        if (selectIdx < 0 || fromIdx < 0) return new ArrayList<>();

        String selectPart = spec.substring(selectIdx + 7, fromIdx).trim();
        String fromPart   = spec.substring(fromIdx + 6).trim();

        Map<String, Element> entityMap = buildEntityMapFromFromClause(fromPart);

        List<FieldData> fields = new ArrayList<>();
        List<EnumData>  enums  = new ArrayList<>();

        for (String item : selectPart.split(",")) {
            FieldData fd = resolveSelectItem(item.trim(), entityMap, enums);
            if (fd != null) fields.add(fd);
        }
        return fields;
    }

    /**
     * Builds a name→{@link Element} map for platform entities/associations
     * referenced in a FROM clause string.
     */
    private Map<String, Element> buildEntityMapFromFromClause(String fromClause) {
        Map<String, Element> map = new LinkedHashMap<>();
        String[] tokens = fromClause.split("\\s+");
        boolean expectName = true;

        for (String token : tokens) {
            String lower = token.toLowerCase();
            if ("join".equals(lower)) {
                expectName = true;
            } else if ("on".equals(lower) || "where".equals(lower)) {
                expectName = false;
            } else if (expectName && !token.isEmpty()) {
                Element el = findPlatformEntityOrAssocByName(token);
                if (el != null) map.put(token, el);
                expectName = false;
            }
        }
        return map;
    }

    /**
     * Resolves one SELECT item (e.g., {@code "PA1a.pobs3 as pobs3"}) to a
     * {@link FieldData}, or {@code null} if it cannot be resolved.
     */
    private FieldData resolveSelectItem(String item,
                                        Map<String, Element> entityMap,
                                        List<EnumData> enumAccum) {
        String alias;
        String entityFieldPart;

        String itemLower = item.toLowerCase();
        int asIdx = itemLower.lastIndexOf(" as ");
        if (asIdx >= 0) {
            alias           = item.substring(asIdx + 4).trim();
            entityFieldPart = item.substring(0, asIdx).trim();
        } else {
            entityFieldPart = item;
            int dotIdx = item.lastIndexOf('.');
            alias = dotIdx >= 0 ? item.substring(dotIdx + 1).trim() : item.trim();
        }

        int dotIdx = entityFieldPart.indexOf('.');
        if (dotIdx < 0) return null;

        String entityName = entityFieldPart.substring(0, dotIdx).trim();
        String fieldName  = entityFieldPart.substring(dotIdx + 1).trim();

        Element entityEl = entityMap.get(entityName);
        if (entityEl == null) return null;

        Element compositionEl = findChildByRolename(entityEl, fieldName);
        if (compositionEl == null) return null;

        String typeUuid = compositionEl.getAttribute("type");
        if (typeUuid == null || typeUuid.isEmpty()) return null;

        Element typeEl = faceDoc.getUuidMap().get(typeUuid);
        if (typeEl == null) return null;

        return buildFieldFromTypeElement(alias, typeUuid, typeEl, enumAccum);
    }

    // =========================================================================
    // CompositeTemplate field resolution
    // =========================================================================

    /**
     * Resolves the IDL fields for a {@code uop:CompositeTemplate} from its
     * {@code uop:TemplateComposition} children.  Each child contributes a field
     * whose IDL type is the name of the referenced Template or CompositeTemplate.
     *
     * <p>Called in Pass 2, after Pass 1 has populated {@link #typeById}.
     */
    private List<FieldData> resolveCompositeTemplateFields(Element compositeEl) {
        List<FieldData> fields = new ArrayList<>();
        NodeList children = compositeEl.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;

            String childXmiType = childEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
            if (!"uop:TemplateComposition".equals(childXmiType)) continue;

            String rolename = childEl.getAttribute("rolename");
            String typeUuid = childEl.getAttribute("type");

            TssTypeData referencedType = typeById.get(typeUuid);
            if (referencedType == null) continue;

            FieldData fd = new FieldData();
            fd.setName(rolename);
            fd.setIdlType(referencedType.getName());
            fd.setNested(false);
            fields.add(fd);
        }
        return fields;
    }

    // =========================================================================
    // Shared resolution helpers (mirrored from FaceTssReader)
    // =========================================================================

    /**
     * Builds a {@link FieldData} from a platform type element and a field alias.
     * Handles struct (recursive), enum (via logical chain), and primitive types.
     */
    private FieldData buildFieldFromTypeElement(String alias,
                                                String typeUuid,
                                                Element typeEl,
                                                List<EnumData> enumAccum) {
        String typeXmiType = typeEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        FieldData fd = new FieldData();
        fd.setName(alias);

        switch (typeXmiType) {
            case "platform:Struct": {
                String structName    = typeEl.getAttribute("name");
                List<FieldData> subs = faceDoc.resolveStructMembers(
                        typeEl, enumAccum, new HashSet<>());
                fd.setNested(true);
                fd.setIdlType(structName);
                fd.setNestedFields(subs);
                break;
            }
            case "platform:Enumeration": {
                EnumData enumData = faceDoc.resolveOrCacheEnum(typeUuid, typeEl);
                if (enumData == null) return null;
                FaceXmiDocument.addEnumIfAbsent(enumData, enumAccum);
                fd.setNested(false);
                fd.setIdlType(enumData.getName());
                break;
            }
            default: {
                FaceXmiDocument.PlatformTypeResult ptr = FaceXmiDocument.resolvePrimitiveIdlType(typeXmiType, typeEl);
                if (ptr == null) return null;
                fd.setNested(false);
                fd.setIdlType(ptr.idlType);
                fd.setArrayDimension(ptr.arrayDimension);
            }
        }

        return fd.getIdlType() != null ? fd : null;
    }

    /**
     * Finds a child {@link Element} of {@code parent} whose {@code rolename}
     * attribute equals {@code rolename}.
     */
    private static Element findChildByRolename(Element parent, String rolename) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (rolename.equals(el.getAttribute("rolename"))) return el;
        }
        return null;
    }

    /**
     * Finds a {@code platform:Entity} or {@code platform:Association} element
     * in the full-document UUID map whose {@code name} attribute equals {@code name}.
     * Results are cached after the first call.
     */
    private Element findPlatformEntityOrAssocByName(String name) {
        if (platformEntityByName == null) {
            platformEntityByName = new LinkedHashMap<>();
            for (Element el : faceDoc.getUuidMap().values()) {
                String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
                if ("platform:Entity".equals(xmiType)
                        || "platform:Association".equals(xmiType)) {
                    String elName = el.getAttribute("name");
                    if (elName != null && !elName.isEmpty()) {
                        platformEntityByName.put(elName, el);
                    }
                }
            }
        }
        return platformEntityByName.get(name);
    }
}
