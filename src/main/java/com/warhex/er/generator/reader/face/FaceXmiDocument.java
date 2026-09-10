package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.EnumData;
import com.warhex.er.generator.reader.dto.EnumValueData;
import com.warhex.er.generator.reader.dto.FieldData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Shared DOM parse result for a {@code .face} XMI file.
 *
 * <p>Parses the file once on construction, builds the full UUID map, and
 * exposes resolution helpers that are needed by both
 * {@link FaceXmiModelReader} (entity / {@code <dm>} subtree) and
 * {@link FaceTssReader} (UoP / {@code <um>} subtree).
 *
 * <h2>Shared state</h2>
 * <ul>
 *   <li>{@link #uuidMap} — every {@code xmi:id → Element} in the document</li>
 *   <li>{@link #enumCache} — resolved {@link EnumData} objects keyed by
 *       {@code platform:Enumeration} UUID (prevents re-traversal of the
 *       logical model chain)</li>
 * </ul>
 *
 * <h2>Shared resolution helpers</h2>
 * <ul>
 *   <li>{@link #resolveStructMembers} — recursively resolves a
 *       {@code platform:Struct}'s member fields</li>
 *   <li>{@link #resolveOrCacheEnum} — resolves an enum via the 5-hop
 *       logical-layer chain</li>
 *   <li>{@link #resolvePrimitiveIdlType} — maps platform XMI type strings
 *       to IDL primitive type strings</li>
 *   <li>{@link #addEnumIfAbsent} — deduplication helper for enum accumulators</li>
 *   <li>{@link #firstToken} — splits a space-delimited UUID list</li>
 * </ul>
 */
public class FaceXmiDocument {

    /** Namespace URI for {@code xmi:id} and {@code xmi:type} attributes. */
    static final String XMI_NS = "http://www.omg.org/XMI";

    private static final Logger LOG = Logger.getLogger(FaceXmiDocument.class.getName());

    // -------------------------------------------------------------------------
    // Shared parse state
    // -------------------------------------------------------------------------

    private final Document document;

    /**
     * Pass-1 result: every {@code xmi:id} in the document mapped to its
     * DOM {@link Element}.  Populated on construction; never modified after.
     */
    private final Map<String, Element> uuidMap;

    /**
     * Cache of {@link EnumData} objects resolved via the logical-layer
     * 5-hop chain, keyed by {@code platform:Enumeration xmi:id}.
     * Shared between entity-reader and TSS-reader within the same parse.
     */
    private final Map<String, EnumData> enumCache;

    /**
     * Maps {@code xmi:id} of a named platform typedef (e.g. {@code platform:String}
     * inside a {@code datamodel:DataModel}) to its fully-qualified IDL name,
     * e.g. {@code "FACE::DM::Data_Model::Identifier_UUID_String"}.
     * Built on construction; never modified after.
     */
    private final Map<String, String> namedPlatformTypeIdlName;

    /**
     * Maps the same UUIDs to the IDL {@code #include} path, e.g.
     * {@code "FACE/DM/Data_Model/Identifier_UUID_String.idl"}.
     */
    private final Map<String, String> namedPlatformTypeIdlPath;

    // -------------------------------------------------------------------------
    // Constructor — parse + UUID map
    // -------------------------------------------------------------------------

    /**
     * Parses {@code source} and builds the shared UUID map.
     *
     * @param source path to the {@code .face} XMI file; must not be {@code null}
     * @throws Exception if the file cannot be parsed
     */
    public FaceXmiDocument(Path source) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        this.document = dbf.newDocumentBuilder().parse(source.toFile());

        this.uuidMap   = new LinkedHashMap<>();
        this.enumCache = new LinkedHashMap<>();
        this.namedPlatformTypeIdlName = new LinkedHashMap<>();
        this.namedPlatformTypeIdlPath = new LinkedHashMap<>();

        buildUuidMap(document.getDocumentElement());
        buildNamedPlatformTypeMap(document.getDocumentElement());
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the parsed DOM document. */
    public Document getDocument() { return document; }

    /** Returns the root element of the parsed document. */
    public Element getRoot() { return document.getDocumentElement(); }

    /**
     * Returns the unmodifiable view of the UUID map.
     * Keys are {@code xmi:id} attribute values; values are the corresponding
     * DOM elements.
     */
    public Map<String, Element> getUuidMap() {
        return Collections.unmodifiableMap(uuidMap);
    }

    /**
     * Returns the fully-qualified IDL name for a named platform typedef UUID,
     * or {@code null} if the UUID is not a named platform typedef.
     * <p>Example: UUID of {@code platform:String "Identifier_UUID_String"} in
     * {@code Data_Model} → {@code "FACE::DM::Data_Model::Identifier_UUID_String"}.
     */
    public String resolveNamedPlatformTypeIdlName(String uuid) {
        return uuid != null ? namedPlatformTypeIdlName.get(uuid) : null;
    }

    /**
     * Returns the IDL {@code #include} path for a named platform typedef UUID,
     * or {@code null} if not applicable.
     * <p>Example: → {@code "FACE/DM/Data_Model/Identifier_UUID_String.idl"}.
     */
    public String resolveNamedPlatformTypeIdlPath(String uuid) {
        return uuid != null ? namedPlatformTypeIdlPath.get(uuid) : null;
    }

    // -------------------------------------------------------------------------
    // Pass-1: UUID map builder
    // -------------------------------------------------------------------------

    private void buildUuidMap(Element el) {
        String id = el.getAttributeNS(XMI_NS, "id");
        if (id != null && !id.isEmpty()) {
            uuidMap.put(id, el);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                buildUuidMap((Element) child);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pass-1b: Named platform typedef map builder
    // -------------------------------------------------------------------------

    /**
     * Scans every direct {@code dm} child of the document root whose
     * {@code xmi:type} is {@code "datamodel:DataModel"} and registers all
     * descendant elements that are named platform primitive typedefs
     * (i.e. have a non-empty {@code name} attribute and an {@code xmi:type}
     * starting with {@code "platform:"}, excluding {@code platform:Struct}
     * (whose members are resolved inline rather than by typedef reference).
     *
     * <p>The qualified IDL name follows the pattern
     * {@code FACE::DM::<DataModelName>::<typeName>}, and the include path
     * is {@code FACE/DM/<DataModelName>/<typeName>.idl}.
     */
    private void buildNamedPlatformTypeMap(Element root) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element dm = (Element) n;
            String dmType = dm.getAttributeNS(XMI_NS, "type");
            if (!"datamodel:DataModel".equals(dmType)) continue;
            String modelName = dm.getAttribute("name");
            if (modelName == null || modelName.isEmpty()) continue;
            String idlModule = "FACE::DM::" + modelName;
            String idlDir    = "FACE/DM/" + modelName;
            registerNamedPlatformTypes(dm, idlModule, idlDir);
        }
    }

    private void registerNamedPlatformTypes(Element parent, String idlModule, String idlDir) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String xmiType = el.getAttributeNS(XMI_NS, "type");
            String uuid    = el.getAttributeNS(XMI_NS, "id");
            String name    = el.getAttribute("name");
            if (xmiType != null && xmiType.startsWith("platform:")
                    && !"platform:Struct".equals(xmiType)
                    && uuid != null && !uuid.isEmpty()
                    && name != null && !name.isEmpty()) {
                namedPlatformTypeIdlName.put(uuid, idlModule + "::" + name);
                namedPlatformTypeIdlPath.put(uuid, idlDir + "/" + name + ".idl");
            }
            // Recurse into children (platform types can be nested inside grouping elements)
            registerNamedPlatformTypes(el, idlModule, idlDir);
        }
    }

    // -------------------------------------------------------------------------
    // Shared resolution: struct members (recursive, cycle-safe)
    // -------------------------------------------------------------------------

    /**
     * Resolves the ordered member list of a {@code platform:Struct} element.
     *
     * <p>Struct members whose type resolves to an enum are added to
     * {@code enumAccum}; callers deduplicate via {@link #addEnumIfAbsent}.
     *
     * @param structEl   the {@code platform:Struct} DOM element
     * @param enumAccum  accumulator for any enums discovered inside the struct
     * @param visited    cycle-detection set of {@code xmi:id}s already on the
     *                   resolution stack
     * @return ordered list of resolved {@link FieldData} members
     */
    public List<FieldData> resolveStructMembers(Element structEl,
                                                List<EnumData> enumAccum,
                                                Set<String> visited) {
        String structId = structEl.getAttributeNS(XMI_NS, "id");
        if (structId != null && !structId.isEmpty()) {
            if (!visited.add(structId)) {
                return new ArrayList<>(); // cycle — return empty
            }
        }

        List<FieldData> members = new ArrayList<>();
        NodeList children = structEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element memberEl = (Element) child;

            String memberXmiType = memberEl.getAttributeNS(XMI_NS, "type");
            if (!"platform:StructMember".equals(memberXmiType)) continue;

            String rolename = memberEl.getAttribute("rolename");
            String typeUuid = memberEl.getAttribute("type");
            if (typeUuid == null || typeUuid.isEmpty()) continue;

            Element typeEl = uuidMap.get(typeUuid);
            if (typeEl == null) continue;

            String typeXmiType = typeEl.getAttributeNS(XMI_NS, "type");
            FieldData memberField = new FieldData();
            memberField.setName(rolename);

            if ("platform:Struct".equals(typeXmiType)) {
                String subStructName    = typeEl.getAttribute("name");
                List<FieldData> subSubs = resolveStructMembers(
                        typeEl, enumAccum, new HashSet<>(visited));
                memberField.setNested(true);
                memberField.setIdlType(subStructName);
                memberField.setNestedFields(subSubs);

            } else if ("platform:Enumeration".equals(typeXmiType)) {
                EnumData enumData = resolveOrCacheEnum(typeUuid, typeEl);
                if (enumData == null) continue;
                addEnumIfAbsent(enumData, enumAccum);
                memberField.setNested(false);
                memberField.setIdlType(enumData.getName());

            } else {
                PlatformTypeResult ptr = resolvePrimitiveIdlType(typeXmiType, typeEl);
                if (ptr == null) {
                    LOG.warning("Unsupported platform type '" + typeXmiType
                        + "' for struct member '" + rolename + "' — field omitted.");
                    continue;
                }
                memberField.setNested(false);
                memberField.setIdlType(ptr.idlType);
                memberField.setArrayDimension(ptr.arrayDimension);
            }

            if (memberField.getIdlType() != null) {
                members.add(memberField);
            }
        }
        return members;
    }

    // -------------------------------------------------------------------------
    // Shared resolution: enum via logical-layer chain
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link EnumData} for a {@code platform:Enumeration} element,
     * consulting {@link #enumCache} first to avoid repeated traversal.
     *
     * @param enumUuid UUID of the {@code platform:Enumeration} element
     * @param enumEl   the element itself
     * @return resolved {@link EnumData}, or {@code null} if the logical chain
     *         cannot be followed to a {@code logical:Enumerated} terminal
     */
    public EnumData resolveOrCacheEnum(String enumUuid, Element enumEl) {
        if (enumCache.containsKey(enumUuid)) {
            return enumCache.get(enumUuid);
        }
        String enumName     = enumEl.getAttribute("name");
        String realizesUuid = enumEl.getAttribute("realizes");
        if (realizesUuid == null || realizesUuid.isEmpty()) return null;

        List<EnumValueData> labels = followEnumChain(realizesUuid, new HashSet<>());
        if (labels == null || labels.isEmpty()) return null;

        EnumData ed = new EnumData();
        ed.setName(enumName);
        ed.setValues(labels);
        enumCache.put(enumUuid, ed);
        return ed;
    }

    /**
     * Traverses the logical-layer chain starting at {@code uuid} until a
     * {@code logical:Enumerated} element is found, then returns its label names.
     *
     * <p>Chain: {@code logical:Measurement} →{@code measurementAxis[0]}→
     * {@code logical:MeasurementAxis} →{@code valueTypeUnit[0]}→
     * {@code logical:ValueTypeUnit} →{@code valueType}→
     * {@code logical:Enumerated} →{@code <label>} children.
     */
    private List<EnumValueData> followEnumChain(String uuid, Set<String> visited) {
        if (uuid == null || uuid.isEmpty()) return null;
        if (!visited.add(uuid))            return null; // cycle

        Element el = uuidMap.get(uuid);
        if (el == null) return null;

        String xmiType = el.getAttributeNS(XMI_NS, "type");

        if ("logical:Enumerated".equals(xmiType)) {
            List<EnumValueData> labels = new ArrayList<>();
            NodeList children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                Element childEl  = (Element) child;
                String childType = childEl.getAttributeNS(XMI_NS, "type");
                if ("logical:EnumerationLabel".equals(childType)) {
                    String labelName = childEl.getAttribute("name");
                    if (labelName != null && !labelName.isEmpty()) {
                        EnumValueData evd = new EnumValueData();
                        evd.setName(labelName);
                        labels.add(evd);
                    }
                }
            }
            return labels.isEmpty() ? null : labels;
        }

        if ("logical:Measurement".equals(xmiType)) {
            return followEnumChain(firstToken(el.getAttribute("measurementAxis")), visited);
        }

        if ("logical:MeasurementAxis".equals(xmiType)) {
            return followEnumChain(firstToken(el.getAttribute("valueTypeUnit")), visited);
        }

        if ("logical:ValueTypeUnit".equals(xmiType)) {
            return followEnumChain(el.getAttribute("valueType"), visited);
        }

        return null; // unknown node in chain
    }

    // -------------------------------------------------------------------------
    // Shared: primitive platform-type mapping
    // -------------------------------------------------------------------------

    /**
     * Maps a {@code platform:*} XMI type string to a {@link PlatformTypeResult}
     * carrying both the IDL type string and an optional array-dimension suffix.
     *
     * <h2>J.8 PlatformIDLType normative table (18 types)</h2>
     * Boolean, Short, Long, LongLong, UShort, ULong, ULongLong, Float, Double,
     * LongDouble, String, BoundedString, Octet, Char, CharArray, Fixed,
     * Sequence, Array.
     *
     * <p>{@code platform:WChar} is NOT in the J.8 normative table and is
     * intentionally absent — unknown platform types fall to the {@code default}
     * branch which returns {@code null}; callers should log a warning and skip
     * the field (IDL-6).
     *
     * @param platformXmiType the {@code xmi:type} attribute value of the type element
     * @param el              the type element (used for attribute look-ups on compound types)
     * @return {@link PlatformTypeResult}, or {@code null} for non-primitive / unknown types
     */
    public static PlatformTypeResult resolvePrimitiveIdlType(String platformXmiType, Element el) {
        switch (platformXmiType) {
            // ── J.8 scalar primitive types ────────────────────────────────────
            case "platform:Boolean":    return PlatformTypeResult.scalar("boolean");
            case "platform:Char":       return PlatformTypeResult.scalar("char");
            case "platform:Double":     return PlatformTypeResult.scalar("double");
            case "platform:Float":      return PlatformTypeResult.scalar("float");
            case "platform:Long":       return PlatformTypeResult.scalar("long");
            case "platform:LongLong":   return PlatformTypeResult.scalar("long long");
            case "platform:LongDouble": return PlatformTypeResult.scalar("long double");
            case "platform:Octet":      return PlatformTypeResult.scalar("octet");
            case "platform:Short":      return PlatformTypeResult.scalar("short");
            case "platform:String":     return PlatformTypeResult.scalar("string");
            case "platform:ULong":      return PlatformTypeResult.scalar("unsigned long");
            case "platform:ULongLong":  return PlatformTypeResult.scalar("unsigned long long");
            case "platform:UShort":     return PlatformTypeResult.scalar("unsigned short");
            case "platform:Fixed": {
                String digits = el.getAttribute("digits");
                String scale  = el.getAttribute("scale");
                if (digits == null || digits.isEmpty()) digits = "10";
                if (scale  == null || scale.isEmpty())  scale  = "0";
                return PlatformTypeResult.scalar("fixed<" + digits + "," + scale + ">");
            }
            // ── J.8 compound / parameterised types (IDL-3) ───────────────────
            case "platform:BoundedString": {
                String maxLen = el.getAttribute("maxLength");
                return PlatformTypeResult.scalar(
                    maxLen == null || maxLen.isEmpty() ? "string" : "string<" + maxLen + ">");
            }
            case "platform:CharArray": {
                int len = parseAttrInt(el, "length", 1);
                return PlatformTypeResult.array("char", len);
            }
            case "platform:Sequence": {
                String maxSize = el.getAttribute("maxSize");
                return PlatformTypeResult.scalar(
                    maxSize == null || maxSize.isEmpty()
                        ? "sequence<octet>" : "sequence<octet, " + maxSize + ">");
            }
            case "platform:Array": {
                int sz = parseAttrInt(el, "size", 1);
                return PlatformTypeResult.array("octet", sz);
            }
            // ── Unknown / non-J.8 types ───────────────────────────────────────
            // platform:WChar is intentionally excluded — it is not listed in the
            // J.8 PlatformIDLType normative table and would cause FACE conformance
            // checker failures.  Callers log a warning and omit the field.
            default:
                return null;
        }
    }

    /**
     * Parses an integer-valued XMI attribute, returning {@code defaultVal} when
     * the attribute is absent, empty, or not a valid integer.
     */
    private static int parseAttrInt(Element el, String attrName, int defaultVal) {
        String val = el.getAttribute(attrName);
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOG.warning("Could not parse attribute '" + attrName + "' value '"
                + val + "' as integer — using default " + defaultVal);
            return defaultVal;
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Returns the first whitespace-delimited token in {@code spaceList}, or {@code null}. */
    public static String firstToken(String spaceList) {
        if (spaceList == null || spaceList.isEmpty()) return null;
        String[] parts = spaceList.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : null;
    }

    /** Adds {@code enumData} to {@code list} only if no entry with the same name exists. */
    public static void addEnumIfAbsent(EnumData enumData, List<EnumData> list) {
        String name = enumData.getName();
        for (EnumData existing : list) {
            if (name.equals(existing.getName())) return;
        }
        list.add(enumData);
    }

    // -------------------------------------------------------------------------
    // PlatformTypeResult — carrier for IDL type + optional array dimension
    // -------------------------------------------------------------------------

    /**
     * Carries the IDL type string and the optional array-dimension suffix
     * produced by {@link #resolvePrimitiveIdlType}.
     *
     * <p>{@link #scalar} covers all plain-scalar platform types; {@link #array}
     * is used for {@code platform:CharArray} and {@code platform:Array} whose IDL
     * declaration requires a {@code [N]} suffix on the field name rather than on
     * the type.
     */
    static final class PlatformTypeResult {
        /** IDL type string, e.g. {@code "float"} or {@code "char"}. */
        final String idlType;
        /**
         * Array-dimension suffix appended to the field name, e.g. {@code "[10]"}.
         * Empty string for scalar fields.
         */
        final String arrayDimension;

        private PlatformTypeResult(String idlType, String arrayDimension) {
            this.idlType        = idlType;
            this.arrayDimension = arrayDimension;
        }

        /** Creates a result for a plain scalar IDL type. */
        static PlatformTypeResult scalar(String idlType) {
            return new PlatformTypeResult(idlType, "");
        }

        /**
         * Creates a result for a fixed-size array type.
         * The IDL declaration uses {@code baseType name[dim];} form.
         */
        static PlatformTypeResult array(String baseType, int dim) {
            return new PlatformTypeResult(baseType, "[" + dim + "]");
        }
    }

    /**
     * Walks the immediate children of {@code root} and returns the {@code name}
     * attribute of the first child element whose {@code xmi:type} is in
     * {@code xmiTypes}.  Returns {@code null} if no matching child is found.
     *
     * <p>Used to extract the correct {@code UoPModel} or {@code DataModel} name
     * rather than falling back to the root {@code ArchitectureModel} name, which
     * may differ in multi-model architectures (IDL-5).
     *
     * @param root     document root element
     * @param xmiTypes one or more {@code xmi:type} values to match (tried in order)
     * @return the matching child element's {@code name} attribute, or {@code null}
     */
    public static String findModelName(Element root, String... xmiTypes) {
        Set<String> typeSet = new HashSet<>(Arrays.asList(xmiTypes));
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            String xmiType = childEl.getAttributeNS(XMI_NS, "type");
            if (typeSet.contains(xmiType)) {
                String name = childEl.getAttribute("name");
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return null;
    }

    /**
     * Returns all direct children of {@code root} whose {@code xmi:type} attribute
     * matches any of the given {@code xmiTypes}.
     *
     * <p>Used to enumerate all {@code uop:UoPModel} or {@code datamodel:DataModel}
     * elements when a {@code .face} file contains more than one.
     *
     * @param root     the element whose direct children are searched
     * @param xmiTypes one or more {@code xmi:type} values to match
     * @return ordered list of matching child elements; never {@code null}
     */
    public static List<Element> findAllChildren(Element root, String... xmiTypes) {
        Set<String> typeSet = new HashSet<>(Arrays.asList(xmiTypes));
        List<Element> result = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            String xmiType = childEl.getAttributeNS(XMI_NS, "type");
            if (typeSet.contains(xmiType)) result.add(childEl);
        }
        return result;
    }
}
