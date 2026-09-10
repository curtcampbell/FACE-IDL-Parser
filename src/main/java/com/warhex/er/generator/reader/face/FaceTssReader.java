package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads the UoP model section ({@code <um>} subtrees) of a {@code .face} XMI file
 * and produces a {@link UoPModelData} DTO tree.
 *
 * <h2>What is parsed</h2>
 * <ul>
 *   <li>{@code uop:Template} — becomes one {@link TssTypeData}; fields are resolved
 *       from the template's {@code boundQuery} (a {@code platform:Query} whose SQL-like
 *       {@code specification} identifies the column types)</li>
 *   <li>{@code uop:CompositeTemplate} — becomes one {@link TssTypeData}; fields come
 *       from its {@code uop:TemplateComposition} children (each child field type is the
 *       referenced template's name); {@code isUnion=true} sets
 *       {@link TssTypeData#isUnion()}</li>
 *   <li>{@code uop:PortableComponent} / {@code uop:PlatformSpecificComponent} — each
 *       becomes one {@link UoPData}</li>
 *   <li>{@code uop:QueuingConnection} / {@code uop:SingleInstanceMessageConnection} —
 *       pub/sub {@link ConnectionData}; direction from {@code messageExchangeType}</li>
 *   <li>{@code uop:ClientServerConnection} — client/server {@link ConnectionData};
 *       both {@code requestType} and {@code responseType} are resolved</li>
 * </ul>
 *
 * <h2>Two-pass template resolution</h2>
 * <ol>
 *   <li>Pass 1 — walk the whole document and register every Template and
 *       CompositeTemplate in an id→{@link TssTypeData} map (fields left empty).</li>
 *   <li>Pass 2 — resolve fields: Templates via query spec parsing; CompositeTemplates
 *       via their {@code uop:TemplateComposition} children (which now have valid
 *       type-map entries from Pass 1).</li>
 * </ol>
 *
 * <h2>Shared infrastructure</h2>
 * DOM parsing, UUID map, enum resolution, and struct-member resolution are all
 * delegated to {@link FaceXmiDocument}.
 */
public class FaceTssReader {

    private static final Logger LOG = Logger.getLogger(FaceTssReader.class.getName());

    // -------------------------------------------------------------------------
    // Per-parse state
    // -------------------------------------------------------------------------

    private FaceXmiDocument faceDoc;

    /**
     * Registry of all Templates and CompositeTemplates keyed by {@code xmi:id}.
     * Populated in Pass 1; fields are filled in Pass 2.
     */
    private final Map<String, TssTypeData> typeById = new LinkedHashMap<>();

    /**
     * Name→Element cache for platform-layer entities and associations.
     * Used to resolve column references in platform:Query spec strings.
     * Built lazily on first use.
     */
    private Map<String, Element> platformEntityByName;


    // =========================================================================
    // Integration-model XMI type constants (face.integration metamodel, c232 J.2.3)
    //
    // These must match the xmi:type attribute values emitted by the modelling tool
    // for FACE integration-model elements.  The prefix ("integration:") corresponds
    // to the xmlns:integration declaration on the root <face:ArchitectureModel> element
    // (typically http://www.opengroup.us/face/integration/3.1 or /3.2).
    // Adjust if a different prefix is used in the target tool's output.
    // =========================================================================

    private static final String INTEGRATION_CONTEXT_TYPE        = "integration:IntegrationContext";
    private static final String INTEGRATION_TS_NODE_CONN_TYPE   = "integration:TSNodeConnection";
    private static final String INTEGRATION_VIEW_TRANSPORTER_TYPE = "integration:ViewTransporter";
    private static final String INTEGRATION_TRANSPORT_CHANNEL_TYPE = "integration:TransportChannel";
    private static final String INTEGRATION_UOP_INPUT_EP_TYPE   = "integration:UoPInputEndPoint";
    private static final String INTEGRATION_UOP_OUTPUT_EP_TYPE  = "integration:UoPOutputEndPoint";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Parses the given {@code .face} file and returns the full UoP model DTO.
     *
     * <p>Collects <em>all</em> templates and UoPs from the entire document and
     * names the result after the first {@code uop:UoPModel} child found.
     * For multi-model files use {@link #readAll(Path)}.
     *
     * @param source path to the {@code .face} XMI file
     * @return populated {@link UoPModelData}; never {@code null}
     * @throws Exception if the file cannot be parsed
     */
    public UoPModelData read(Path source) throws Exception {
        faceDoc = new FaceXmiDocument(source);
        typeById.clear();
        platformEntityByName = null;

        Element root = faceDoc.getRoot();
        // IDL-5: use uop:UoPModel child element name, not root ArchitectureModel name
        String modelName = FaceXmiDocument.findModelName(root, "uop:UoPModel", "UoP:UoPModel");
        if (modelName == null || modelName.isEmpty()) {
            modelName = root.getAttribute("name");
            LOG.warning("No uop:UoPModel child found; using root name for model.");
        }
        if (modelName == null || modelName.isEmpty()) modelName = "UnknownModel";

        // Pass 1: register all Templates and CompositeTemplates
        registerTemplates(root);

        // Pass 2: resolve fields for each type
        resolveAllTemplateFields(root);

        // Phase C: collect UoP components and their connections
        List<UoPData> uoPs = new ArrayList<>();
        collectUoPs(root, uoPs);

        // Pass D: collect IntegrationContextData (integration model section)
        Map<String, ConnectionData> connectionByUuid = buildConnectionByUuidMap(uoPs);
        Map<String, UoPData>        uopByConnUuid    = buildUopByConnectionUuidMap(uoPs);
        List<IntegrationContextData> integrationContexts =
                collectIntegrationContexts(root, uopByConnUuid, connectionByUuid);

        if (integrationContexts.isEmpty()) {
            LOG.info("No integration:IntegrationContext elements found in model '"
                    + modelName + "'. "
                    + "Ensure the .face file includes an <im> (integration model) subtree "
                    + "and that the xmi:type prefix matches INTEGRATION_CONTEXT_TYPE.");
        } else {
            LOG.info("Integration contexts loaded: " + integrationContexts.size()
                    + " for model '" + modelName + "'.");
        }

        UoPModelData model = new UoPModelData();
        model.setModelName(modelName);
        model.setIdlModule("FACE.DM." + modelName);
        model.setUoPs(uoPs);
        List<TssTypeData> allPlatformTypes = new ArrayList<>(typeById.values());
        propagateIdlModule(allPlatformTypes, "FACE.DM." + modelName);
        model.setPlatformTypes(allPlatformTypes);
        model.setIntegrationContexts(integrationContexts);

        return model;
    }

    /**
     * Parses the given {@code .face} file and returns one {@link UoPModelData}
     * per {@code uop:UoPModel} element found in the file.
     *
     * <h2>Strategy</h2>
     * <ol>
     *   <li><b>Global Pass 1</b>: register every {@code uop:Template} and
     *       {@code uop:CompositeTemplate} across the entire document in
     *       {@link #typeById} (needed so cross-UoPModel type references resolve
     *       correctly in field resolution).</li>
     *   <li><b>Global Pass 2</b>: resolve fields for all registered types.</li>
     *   <li><b>Per-UoPModel phase</b>: collect the templates and UoP components
     *       that belong to each {@code uop:UoPModel} element and build a scoped
     *       {@link UoPModelData}.</li>
     *   <li><b>Integration-context phase</b>: walk the full document once to
     *       collect {@code integration:IntegrationContext} elements; each IC is
     *       attached to the model that owns the referenced UoP connections.</li>
     * </ol>
     *
     * @param source path to the {@code .face} XMI file
     * @return ordered list of {@link UoPModelData}; never {@code null}, never empty
     * @throws Exception if the file cannot be parsed
     */
    public List<UoPModelData> readAll(Path source) throws Exception {
        faceDoc = new FaceXmiDocument(source);
        typeById.clear();
        platformEntityByName = null;

        Element root = faceDoc.getRoot();

        // Global Pass 1: register ALL templates across the entire document
        registerTemplates(root);

        // Global Pass 2: resolve fields for ALL registered templates
        resolveAllTemplateFields(root);

        // Global Pass 2b: stamp every registered Template / CompositeTemplate with the
        // root uop:UoPModel that DEFINES it.  FACE Technical Standard 3.2 §J.8 and
        // §4.8.4.1 both key the generated IDL module on "the root UoPModel in which the
        // element is a member" — never on the UoPModel whose UoP happens to connect to
        // it.  Doing this here, before any per-model scoping, makes the assignment
        // explicit and independent of the order the <um> elements appear in the file.
        stampDefiningUoPModels(root);

        // Global Pass 2c: now that every type knows its defining module, rewrite
        // template-to-template field references.  §J.8: "an inter-Model type reference
        // must manifest in IDL using an IDL module-scoped name."
        resolveInterModelTemplateReferences();

        // Global Phase C: collect ALL UoP components from the entire document.
        // UoPs are not partitioned per UoPModel group — they reference templates by UUID
        // and may live in a sibling uop:UoPModel element (e.g. a "UoPs" container).
        List<UoPData> allUoPs = new ArrayList<>();
        collectUoPs(root, allUoPs);

        // Global Phase D: integration contexts across the entire document
        Map<String, ConnectionData> connectionByUuid = buildConnectionByUuidMap(allUoPs);
        Map<String, UoPData>        uopByConnUuid    = buildUopByConnectionUuidMap(allUoPs);
        List<IntegrationContextData> integrationContexts =
                collectIntegrationContexts(root, uopByConnUuid, connectionByUuid);

        if (integrationContexts.isEmpty()) {
            LOG.info("No integration:IntegrationContext elements found. "
                    + "Ensure the .face file includes an <im> subtree.");
        } else {
            LOG.info("Integration contexts loaded: " + integrationContexts.size());
        }

        // Find all uop:UoPModel direct children of the root element
        List<Element> uopModelEls = FaceXmiDocument.findAllChildren(
                root, "uop:UoPModel", "UoP:UoPModel");

        List<UoPModelData> results = new ArrayList<>();
        if (uopModelEls.isEmpty()) {
            // No explicit uop:UoPModel wrapper — treat root as the single model
            LOG.warning("No uop:UoPModel children found; treating root as single model.");
            String fallbackName = root.getAttribute("name");
            if (fallbackName == null || fallbackName.isEmpty()) fallbackName = "UnknownModel";
            UoPModelData fallback = buildUoPModelData(root, fallbackName, true, allUoPs, integrationContexts);
            if (fallback != null) results.add(fallback);
        } else {
            for (Element uopModelEl : uopModelEls) {
                String modelName = uopModelEl.getAttribute("name");
                if (modelName == null || modelName.isEmpty()) continue;
                UoPModelData built = buildUoPModelData(uopModelEl, modelName, false, allUoPs, integrationContexts);
                if (built != null) results.add(built);
            }
            if (results.isEmpty()) {
                // All children were template-only or unnamed — fall back to root
                String fallbackName = root.getAttribute("name");
                if (fallbackName == null || fallbackName.isEmpty()) fallbackName = "UnknownModel";
                UoPModelData fallback = buildUoPModelData(root, fallbackName, true, allUoPs, integrationContexts);
                if (fallback != null) results.add(fallback);
            }
        }

        return results;
    }

    /**
     * Builds one {@link UoPModelData} scoped to {@code scopeEl}.
     *
     * <p>Only ums that directly contain {@code uop:PortableComponent} (or
     * {@code uop:PlatformSpecificComponent}) elements are considered for TSS
     * IDL generation.  Template-only ums (e.g. {@code CORE_Templates},
     * {@code CheckoutGateway_Templates}) are skipped — they define data types
     * but do not own UoP instances and therefore do not drive TypedTS output.
     *
     * <p>For a UoP-containing um the {@link UoPModelData#getPlatformTypes()}
     * list is derived from the unique {@link TssTypeData} objects referenced by
     * that um's UoP connections (deduplicated by UUID within the um; independent
     * deduplication per um).
     *
     * @param scopeEl              the {@code uop:UoPModel} element (or root)
     * @param modelName            the model name
     * @param rootScope            {@code true} → include all registered types
     * @param allUoPs              global UoP list (used only for root-scope fallback)
     * @param integrationContexts  global IC list (collected once from the entire document)
     * @return populated {@link UoPModelData}, or {@code null} if the um
     *         contains no UoP instances (template-only um — caller must skip nulls)
     */
    private UoPModelData buildUoPModelData(Element scopeEl,
                                            String modelName,
                                            boolean rootScope,
                                            List<UoPData> allUoPs,
                                            List<IntegrationContextData> integrationContexts) {
        if (modelName == null || modelName.isEmpty()) modelName = "UnknownModel";

        List<UoPData>     scopedUoPs;
        List<TssTypeData> platformTypes;

        if (rootScope) {
            // Root-scope fallback: use all UoPs and all registered types
            scopedUoPs    = allUoPs;
            platformTypes = new ArrayList<>(typeById.values());
        } else {
            // Collect only the UoP instances (PortableComponent / PlatformSpecificComponent)
            // that live directly within this uop:UoPModel element's subtree.
            scopedUoPs = new ArrayList<>();
            collectUoPs(scopeEl, scopedUoPs);

            // platformTypes are the types this um DEFINES — not the types its
            // connections happen to reference.  FACE Technical Standard 3.2 §J.8
            // places a Template's IDL in the module named after the root UoPModel
            // in which it is a member, so data-model IDL must be driven by
            // definition, not by usage.  A um such as "UoPs" defines no templates
            // and therefore contributes no data-model IDL; the template ums define
            // types but own no UoPs and therefore contribute no TypedTS IDL.
            LinkedHashMap<String, TssTypeData> typesByUuid = new LinkedHashMap<>();
            List<String> definedIds = new ArrayList<>();
            collectTemplateIdsInSubtree(scopeEl, definedIds);
            for (String id : definedIds) {
                TssTypeData tsd = typeById.get(id);
                if (tsd != null && tsd.getUuid() != null) typesByUuid.put(tsd.getUuid(), tsd);
            }
            platformTypes = new ArrayList<>(typesByUuid.values());

            if (scopedUoPs.isEmpty() && platformTypes.isEmpty()) {
                LOG.info("Skipping um '" + modelName
                        + "' — it defines no templates and owns no UoP instances.");
                return null;
            }
        }

        UoPModelData model = new UoPModelData();
        model.setModelName(modelName);
        model.setIdlModule("FACE.DM." + modelName);
        model.setUoPs(scopedUoPs);
        propagateIdlModule(platformTypes, "FACE.DM." + modelName);
        model.setPlatformTypes(platformTypes);
        model.setIntegrationContexts(integrationContexts);
        return model;
    }

    /**
     * Stamps {@code idlModule} on every registered {@link TssTypeData} using the
     * name of the root {@code uop:UoPModel} that lexically contains its defining
     * element.
     *
     * <p>FACE Technical Standard 3.2 §J.8 (Notes to the Implementer): "For each
     * Template or CompositeTemplate, the IDL types generated by this binding must
     * be defined in an IDL module (in the FACE::DM namespace) whose name is the
     * same as the root UoPModel in which the element is a member."
     *
     * <p>Only direct {@code uop:UoPModel} children of the architecture-model root
     * are treated as roots; templates nested more deeply belong to the enclosing
     * root model.
     *
     * @param root the {@code face:ArchitectureModel} root element
     */
    private void stampDefiningUoPModels(Element root) {
        List<Element> uopModelEls = FaceXmiDocument.findAllChildren(
                root, "uop:UoPModel", "UoP:UoPModel");

        for (Element uopModelEl : uopModelEls) {
            String modelName = uopModelEl.getAttribute("name");
            if (modelName == null || modelName.isEmpty()) continue;

            List<String> ids = new ArrayList<>();
            collectTemplateIdsInSubtree(uopModelEl, ids);
            if (ids.isEmpty()) continue;

            String idlModule = "FACE.DM." + modelName;
            for (String id : ids) {
                TssTypeData tsd = typeById.get(id);
                if (tsd == null) continue;
                if (tsd.getIdlModule() != null && !tsd.getIdlModule().isEmpty()
                        && !tsd.getIdlModule().equals(idlModule)) {
                    LOG.warning("Template '" + tsd.getName() + "' appears in more than one "
                            + "root UoPModel; keeping '" + tsd.getIdlModule()
                            + "' and ignoring '" + idlModule + "'.");
                    continue;
                }
                tsd.setIdlModule(idlModule);
            }
            LOG.fine("Stamped " + ids.size() + " template(s) with module " + idlModule);
        }
    }

    /**
     * Rewrites template-to-template field references now that every type carries
     * its defining {@code idlModule}.
     *
     * <p>A reference to a type in another IDL module is emitted as a fully
     * module-scoped name ({@code ::FACE::DM::CORE_Templates::Money}) and gains an
     * {@code #include} of that type's own file; a reference within the same module
     * stays unqualified.  FACE Technical Standard 3.2 §J.8 requires the scoped form
     * for inter-model references.
     */
    private void resolveInterModelTemplateReferences() {
        for (TssTypeData owner : typeById.values()) {
            resolveInterModelFields(owner.getFields(), owner.getIdlModule());
        }
    }

    private void resolveInterModelFields(List<FieldData> fields, String ownerModule) {
        if (fields == null) return;
        for (FieldData fd : fields) {
            resolveInterModelFields(fd.getNestedFields(), ownerModule);

            String refUuid = fd.getTemplateTypeUuid();
            if (refUuid == null || refUuid.isEmpty()) continue;

            TssTypeData ref = typeById.get(refUuid);
            if (ref == null) continue;

            String refModule = ref.getIdlModule();
            if (refModule == null || refModule.isEmpty()) continue;

            fd.setIdlIncludePath(refModule.replace('.', '/') + "/" + ref.getName() + ".idl");
            if (!refModule.equals(ownerModule)) {
                fd.setIdlType("::" + refModule.replace(".", "::") + "::" + ref.getName());
            } else {
                fd.setIdlType(ref.getName());
            }
        }
    }

    /**
     * Recursively collects the {@code xmi:id} values of all {@code uop:Template}
     * and {@code uop:CompositeTemplate} elements within the subtree rooted at
     * {@code el}, preserving document order.
     *
     * @param el  root of the subtree to search
     * @param ids accumulator list for collected IDs
     */
    private void collectTemplateIdsInSubtree(Element el, List<String> ids) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        if ("uop:Template".equals(xmiType) || "uop:CompositeTemplate".equals(xmiType)) {
            String id = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            if (id != null && !id.isEmpty()) ids.add(id);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) collectTemplateIdsInSubtree((Element) child, ids);
        }
    }

    // =========================================================================
    // Pass 1: register Templates and CompositeTemplates
    // =========================================================================

    private void registerTemplates(Element el) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

        if ("uop:Template".equals(xmiType) || "uop:CompositeTemplate".equals(xmiType)) {
            String id   = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            String name = el.getAttribute("name");

            TssTypeData tsd = new TssTypeData();
            tsd.setName(name);
            tsd.setUuid(id);

            if ("uop:CompositeTemplate".equals(xmiType)) {
                String isUnionAttr = el.getAttribute("isUnion");
                tsd.setUnion("true".equalsIgnoreCase(isUnionAttr));
                // IDL-1: CompositeTemplate does NOT get the T_ module wrapper
                tsd.setCompositeTemplate(true);
            }
            // uop:Template leaves compositeTemplate=false → receives T_ wrapper

            typeById.put(id, tsd);
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                registerTemplates((Element) child);
            }
        }
    }

    // =========================================================================
    // Pass 2: resolve fields
    // =========================================================================

    private void resolveAllTemplateFields(Element el) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

        if ("uop:Template".equals(xmiType)) {
            String id  = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            TssTypeData tsd = typeById.get(id);
            if (tsd != null) {
                tsd.setFields(resolveTemplateFields(el));
            }

        } else if ("uop:CompositeTemplate".equals(xmiType)) {
            String id  = el.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
            TssTypeData tsd = typeById.get(id);
            if (tsd != null) {
                tsd.setFields(resolveCompositeTemplateFields(el));
            }
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                resolveAllTemplateFields((Element) child);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Template field resolution via boundQuery spec parsing
    // -------------------------------------------------------------------------

    /**
     * Resolves the IDL fields for a {@code uop:Template} by following its
     * {@code boundQuery} to a {@code platform:Query} and parsing that query's
     * SQL-like {@code specification} string.
     */
    /**
     * Resolves fields for a {@code uop:Template} element.
     *
     * <p><strong>Primary path</strong>: parses the {@code specification} attribute
     * of the {@code uop:Template} element itself.  This attribute uses a domain-specific
     * language that provides both field <em>aliases</em> (the intended IDL member names)
     * and the platform rolenames needed for type look-up:
     * <pre>
     *   main(Role) {
     *     rolename as alias;
     *     @optional rolename as alias;
     *     OtherTemplateName(Role) alias;
     *   }
     * </pre>
     *
     * <p><strong>Fallback</strong>: if the template specification is absent or yields
     * no fields, falls back to parsing the {@code platform:Query.specification} SELECT
     * clause (dot-notation format: {@code entity.field as alias}).
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
     * Parses the {@code uop:Template.specification} domain-specific language and
     * returns one {@link FieldData} per field declared in the {@code main(...){}}
     * block.
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
     *
     * <ul>
     *   <li>{@code rolename as alias} — direct platform-entity field; type is
     *       resolved by looking up {@code rolename} in the entities of {@code entityMap}.</li>
     *   <li>{@code @optional rolename as alias} — same as above; the {@code @optional}
     *       marker is stripped before processing.</li>
     *   <li>{@code OtherTemplateName(RoleAlias) alias} — cross-reference to another
     *       registered template; emitted as a scoped IDL type reference.</li>
     * </ul>
     *
     * @param templateSpec raw {@code specification} attribute value
     * @param entityMap    name→element map built from the {@code platform:Query} FROM clause
     * @return ordered list of resolved fields; empty if none could be resolved
     */
    private List<FieldData> parseTemplateSpecFields(String templateSpec,
                                                     Map<String, Element> entityMap) {
        List<FieldData> fields = new ArrayList<>();
        List<EnumData>  enums  = new ArrayList<>();

        // Locate the main(...){...} block
        int mainIdx = templateSpec.indexOf("main(");
        if (mainIdx < 0) return fields;

        int openBrace = templateSpec.indexOf('{', mainIdx);
        if (openBrace < 0) return fields;

        // Find the matching closing brace
        int depth = 1;
        int pos   = openBrace + 1;
        while (pos < templateSpec.length() && depth > 0) {
            char c = templateSpec.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        if (depth != 0) return fields; // malformed

        String mainBody = templateSpec.substring(openBrace + 1, pos - 1);

        // Each statement ends with ';'
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
                    // afterParen may be "alias" or empty
                    String alias = afterParen.isEmpty()
                            ? typeName.substring(0, 1).toLowerCase() + typeName.substring(1)
                            : afterParen;
                    TssTypeData ref = findTypeByName(typeName);
                    if (ref != null) {
                        FieldData fd = new FieldData();
                        fd.setName(alias);
                        fd.setIdlType(ref.getName());
                        fd.setTemplateTypeUuid(ref.getUuid());
                        fd.setNested(false);
                        fields.add(fd);
                    }
                }
                continue; // handled as template-reference line
            }

            // Pattern B: "rolename as alias" — direct platform-entity field
            String lowerLine = line.toLowerCase();
            int asIdx = lowerLine.lastIndexOf(" as ");
            if (asIdx < 0) continue; // unrecognised pattern; skip

            String rolename = line.substring(0, asIdx).trim();
            String alias    = line.substring(asIdx + 4).trim();

            if (rolename.isEmpty() || alias.isEmpty()) continue;

            // Resolve rolename against each entity in the map
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

            FieldData fd = buildFieldFromTypeElement(alias, typeUuid, typeEl, enumAccum);
            if (fd != null) {
                // IDL-2: apply J.8 DE_REF multiplicity rules from platform:Composition
                String rawLower = compositionEl.getAttribute("lower");
                String rawUpper = compositionEl.getAttribute("upper");
                int lower = (rawLower == null || rawLower.isEmpty()) ? 1 : parseMult(rawLower);
                int upper = (rawUpper == null || rawUpper.isEmpty()) ? 1 : parseMult(rawUpper);
                applyDeRef(fd, lower, upper);
                // IDL-7: mark optional fields
                if (hasOptionalAnnotation(compositionEl)) fd.setOptional(true);
            }
            return fd;
        }
        return null; // rolename not found in any entity
    }

    /**
     * Propagates {@code idlModule} to each {@link TssTypeData} in {@code types}
     * that does not already have one set.  Called after platform types are
     * collected so that {@link TssTypeData#getModelNamespace()} returns the
     * correct simple namespace token for every type.
     */
    private static void propagateIdlModule(List<TssTypeData> types, String idlModule) {
        for (TssTypeData t : types) {
            if (t.getIdlModule() == null || t.getIdlModule().isEmpty()) {
                t.setIdlModule(idlModule);
            }
        }
    }

    /**
     * Finds a registered {@link TssTypeData} by name (not UUID).
     * Used when resolving cross-template references in the template specification.
     *
     * @param name the template or composite-template name
     * @return matching {@link TssTypeData}, or {@code null} if not found
     */
    private TssTypeData findTypeByName(String name) {
        for (TssTypeData tsd : typeById.values()) {
            if (name.equals(tsd.getName())) return tsd;
        }
        return null;
    }

    /**
     * Parses a {@code platform:Query} element's {@code specification} attribute
     * (a SQL-like SELECT string) and returns one {@link FieldData} per selected
     * column.
     *
     * <p>Format handled:
     * {@code select <entity>.<field> as <alias>, ... from <entity> join <entity> on ...}
     *
     * <p>Also handles bare rolenames without an entity prefix (format used when the
     * entity is implicit from the FROM clause): in that case the rolename is searched
     * across all entities in the entity map.
     */
    private List<FieldData> resolveQueryFields(Element queryEl) {
        String spec = queryEl.getAttribute("specification");
        if (spec == null || spec.isEmpty()) return new ArrayList<>();

        // Split on FROM keyword (case-insensitive)
        String specLower = spec.toLowerCase();
        int selectIdx = specLower.indexOf("select ");
        int fromIdx   = specLower.indexOf(" from ");
        if (selectIdx < 0 || fromIdx < 0) return new ArrayList<>();

        String selectPart = spec.substring(selectIdx + 7, fromIdx).trim();
        String fromPart   = spec.substring(fromIdx + 6).trim();

        // Build a name→element map from the FROM clause
        Map<String, Element> entityMap = buildEntityMapFromFromClause(fromPart);

        List<FieldData> fields = new ArrayList<>();
        List<EnumData>  enums  = new ArrayList<>(); // shared accumulator

        for (String item : selectPart.split(",")) {
            FieldData fd = resolveSelectItem(item.trim(), entityMap, enums);
            if (fd != null) fields.add(fd);
        }
        return fields;
    }

    /**
     * Builds a name→{@link Element} map for the platform entities/associations
     * referenced in a FROM clause string.
     *
     * <p>Handles: {@code from EntityA join EntityB on EntityA.field ...}
     */
    private Map<String, Element> buildEntityMapFromFromClause(String fromClause) {
        Map<String, Element> map = new LinkedHashMap<>();
        String[] tokens = fromClause.split("\\s+");
        boolean expectName = true; // first token is always a name

        for (String token : tokens) {
            String lower = token.toLowerCase();
            if ("join".equals(lower)) {
                expectName = true;
            } else if ("on".equals(lower) || "where".equals(lower)) {
                expectName = false;
            } else if (expectName && !token.isEmpty()) {
                Element el = findPlatformEntityOrAssocByName(token);
                if (el != null) map.put(token, el);
                expectName = false; // subsequent tokens are conditions until next JOIN
            }
        }
        return map;
    }

    /**
     * Resolves one SELECT item (e.g., {@code "PA1a.pobs3 as pobs3"} or
     * {@code "PE2a.pobs6"}) to a {@link FieldData}, or {@code null} if it
     * cannot be resolved.
     */
    private FieldData resolveSelectItem(String item,
                                        Map<String, Element> entityMap,
                                        List<EnumData> enumAccum) {
        // Parse: "entity.field as alias"  or  "entity.field"
        String alias;
        String entityFieldPart;

        String itemLower = item.toLowerCase();
        int asIdx = itemLower.lastIndexOf(" as ");
        if (asIdx >= 0) {
            alias          = item.substring(asIdx + 4).trim();
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

        // Find child (composition or participant) with the given rolename
        Element compositionEl = findChildByRolename(entityEl, fieldName);
        if (compositionEl == null) return null;

        String typeUuid = compositionEl.getAttribute("type");
        if (typeUuid == null || typeUuid.isEmpty()) return null;

        Element typeEl = faceDoc.getUuidMap().get(typeUuid);
        if (typeEl == null) return null;

        FieldData fd = buildFieldFromTypeElement(alias, typeUuid, typeEl, enumAccum);
        if (fd != null) {
            // IDL-2: apply J.8 DE_REF multiplicity rules from platform:Composition
            String rawLower = compositionEl.getAttribute("lower");
            String rawUpper = compositionEl.getAttribute("upper");
            int lower = (rawLower == null || rawLower.isEmpty()) ? 1 : parseMult(rawLower);
            int upper = (rawUpper == null || rawUpper.isEmpty()) ? 1 : parseMult(rawUpper);
            applyDeRef(fd, lower, upper);
            // IDL-7: mark optional fields
            if (hasOptionalAnnotation(compositionEl)) fd.setOptional(true);
        }
        return fd;
    }

    // -------------------------------------------------------------------------
    // CompositeTemplate field resolution
    // -------------------------------------------------------------------------

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

            String rolename   = childEl.getAttribute("rolename");
            String typeUuid   = childEl.getAttribute("type");

            TssTypeData referencedType = typeById.get(typeUuid);
            if (referencedType == null) continue; // unresolved — skip

            FieldData fd = new FieldData();
            fd.setName(rolename);
            fd.setIdlType(referencedType.getName()); // e.g. "T1", "T2"
            fd.setTemplateTypeUuid(typeUuid);
            fd.setNested(false); // cross-reference to a separately-generated type

            fields.add(fd);
        }
        return fields;
    }

    // =========================================================================
    // Phase C: UoP collection
    // =========================================================================

    /**
     * Recursively walks {@code el}'s subtree and appends one {@link UoPData}
     * for each {@code uop:PortableComponent} or {@code uop:PlatformSpecificComponent}
     * found.
     */
    private void collectUoPs(Element el, List<UoPData> uoPs) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

        if ("uop:PortableComponent".equals(xmiType)
                || "uop:PlatformSpecificComponent".equals(xmiType)) {
            UoPData uoP = buildUoP(el);
            uoPs.add(uoP);
            return; // don't recurse into the component's children for UoP discovery
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                collectUoPs((Element) child, uoPs);
            }
        }
    }

    /**
     * Builds a {@link UoPData} from a {@code uop:PortableComponent} or
     * {@code uop:PlatformSpecificComponent} element.
     */
    private UoPData buildUoP(Element componentEl) {
        String id   = componentEl.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
        String name = componentEl.getAttribute("name");

        List<ConnectionData> connections = new ArrayList<>();

        NodeList children = componentEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;

            String childXmiType = childEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

            switch (childXmiType) {
                case "uop:QueuingConnection":
                    connections.add(buildPubSubConnection(childEl, ConnectionKind.QUEUING));
                    break;
                case "uop:SingleInstanceMessageConnection":
                    connections.add(buildPubSubConnection(childEl, ConnectionKind.SINGLE_INSTANCE));
                    break;
                case "uop:ClientServerConnection":
                    connections.add(buildClientServerConnection(childEl));
                    break;
                // lcmPort: intentionally skipped for now
                default:
                    break;
            }
        }

        UoPData uoP = new UoPData();
        uoP.setName(name);
        uoP.setUuid(id);
        uoP.setConnections(connections);
        return uoP;
    }

    /**
     * Builds a pub/sub {@link ConnectionData} from a {@code QueuingConnection} or
     * {@code SingleInstanceMessageConnection} element.
     *
     * <p>Direction is determined from the {@code messageExchangeType} attribute:
     * {@code "OutboundMessage"} → {@link ConnectionRole#PRODUCER};
     * absent or any other value → {@link ConnectionRole#CONSUMER}.
     */
    private ConnectionData buildPubSubConnection(Element connEl, ConnectionKind kind) {
        String id   = connEl.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
        String name = connEl.getAttribute("name");

        String msgExchange = connEl.getAttribute("messageExchangeType");
        ConnectionRole role = "OutboundMessage".equals(msgExchange)
                ? ConnectionRole.PRODUCER
                : ConnectionRole.CONSUMER;

        String msgTypeUuid = connEl.getAttribute("messageType");
        TssTypeData messageType = msgTypeUuid != null ? typeById.get(msgTypeUuid) : null;

        ConnectionData cd = new ConnectionData();
        cd.setName(name);
        cd.setUuid(id);
        cd.setKind(kind);       // also sets typedTsVariant automatically
        cd.setRole(role);
        cd.setMessageType(messageType);
        return cd;
    }

    /**
     * Builds a client/server {@link ConnectionData} from a
     * {@code uop:ClientServerConnection} element.
     *
     * <p>Both {@code requestType} and {@code responseType} attributes are resolved
     * to {@link TssTypeData} references.  The connection role defaults to
     * {@link ConnectionRole#REQUESTER} since the XMI does not carry an explicit
     * direction indicator at this level.
     */
    private ConnectionData buildClientServerConnection(Element connEl) {
        String id   = connEl.getAttributeNS(FaceXmiDocument.XMI_NS, "id");
        String name = connEl.getAttribute("name");

        String reqUuid  = connEl.getAttribute("requestType");
        String respUuid = connEl.getAttribute("responseType");

        TssTypeData requestType  = reqUuid  != null ? typeById.get(reqUuid)  : null;
        TssTypeData responseType = respUuid != null ? typeById.get(respUuid) : null;

        ConnectionData cd = new ConnectionData();
        cd.setName(name);
        cd.setUuid(id);
        cd.setKind(ConnectionKind.CLIENT_SERVER); // also sets EXTENDED variant
        cd.setRole(ConnectionRole.REQUESTER);     // default; can be overridden by caller
        cd.setMessageType(requestType);
        cd.setResponseMessageType(responseType);
        return cd;
    }

    // =========================================================================
    // Shared resolution helpers
    // =========================================================================

    /**
     * Builds a {@link FieldData} from a platform type element and a field alias name.
     * Handles struct (recursive), enum (via logical chain), and primitive types.
     *
     * @param alias     the field alias / rolename to assign
     * @param typeUuid  xmi:id of the type element (used for enum cache lookup)
     * @param typeEl    the type element
     * @param enumAccum accumulator for enum types discovered during resolution
     * @return populated {@link FieldData}, or {@code null} if type is unknown
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
                // IDL-8: named platform enumeration inside a dm:DataModel → qualified name
                String namedEnumIdlName = faceDoc.resolveNamedPlatformTypeIdlName(typeUuid);
                if (namedEnumIdlName != null) {
                    fd.setNested(false);
                    fd.setIdlType(namedEnumIdlName);
                    fd.setIdlIncludePath(faceDoc.resolveNamedPlatformTypeIdlPath(typeUuid));
                    break;
                }
                // Inline / anonymous enumeration — resolve and accumulate
                EnumData enumData = faceDoc.resolveOrCacheEnum(typeUuid, typeEl);
                if (enumData == null) return null;
                FaceXmiDocument.addEnumIfAbsent(enumData, enumAccum);
                fd.setNested(false);
                fd.setIdlType(enumData.getName());
                break;
            }
            default: {
                // IDL-8: if this is a named platform typedef (e.g. platform:String
                // "Identifier_UUID_String" inside a datamodel:DataModel), emit the
                // fully-qualified IDL typedef name instead of the raw primitive.
                String namedIdlName = faceDoc.resolveNamedPlatformTypeIdlName(typeUuid);
                if (namedIdlName != null) {
                    fd.setNested(false);
                    fd.setIdlType(namedIdlName);
                    fd.setIdlIncludePath(faceDoc.resolveNamedPlatformTypeIdlPath(typeUuid));
                    break;
                }
                FaceXmiDocument.PlatformTypeResult ptr =
                        FaceXmiDocument.resolvePrimitiveIdlType(typeXmiType, typeEl);
                if (ptr == null) {
                    LOG.warning("Unsupported platform type '" + typeXmiType
                        + "' for field '" + alias + "' — field omitted.");
                    return null;
                }
                fd.setNested(false);
                fd.setIdlType(ptr.idlType);
                fd.setArrayDimension(ptr.arrayDimension);
            }
        }

        return fd.getIdlType() != null ? fd : null;
    }

    /**
     * Parses a FACE multiplicity value string per J.8 DE_REF rules.
     *
     * @return {@code -1} for unbounded ({@code ""}, {@code "*"}, {@code "-1"});
     *         otherwise the parsed integer value
     */
    /**
     * IDL-7: Returns true when {@code compositionEl} has a child element
     * whose {@code xmi:type} is {@code uop:OptionalAnnotation}.
     */
    private static boolean hasOptionalAnnotation(Element el) {
        org.w3c.dom.NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            String type = childEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
            if ("uop:OptionalAnnotation".equals(type)) return true;
        }
        return false;
    }

    private static int parseMult(String val) {
        if (val == null || val.isEmpty() || "*".equals(val) || "-1".equals(val)) return -1;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return -1; // treat unparseable as unbounded
        }
    }

    /**
     * Applies J.8 DE_REF1–4 multiplicity rules to {@code fd} in place.
     *
     * <ul>
     *   <li><b>DE_REF1</b> upper == -1 (unbounded): {@code sequence&lt;Type&gt;}</li>
     *   <li><b>DE_REF2</b> lower &ne; upper (bounded range): {@code sequence&lt;Type, N&gt;}</li>
     *   <li><b>DE_REF3</b> upper &gt; 1 and lower == upper (fixed size): {@code Type name[N]}</li>
     *   <li><b>DE_REF4</b> scalar (all other cases): no change</li>
     * </ul>
     *
     * @param fd    field whose {@code idlType} / {@code arrayDimension} may be mutated
     * @param lower parsed lower multiplicity (1 if absent)
     * @param upper parsed upper multiplicity (-1 if unbounded, 1 if absent)
     */
    private static void applyDeRef(FieldData fd, int lower, int upper) {
        if (fd == null || fd.getIdlType() == null) return;
        String baseType = fd.getIdlType();
        if (upper == -1) {
            // DE_REF1: unbounded sequence
            fd.setIdlType("sequence<" + baseType + ">");
        } else if (lower != upper) {
            // DE_REF2: bounded range sequence
            fd.setIdlType("sequence<" + baseType + ", " + upper + ">");
        } else if (upper > 1) {
            // DE_REF3: fixed-size array (suffix on name, not type)
            fd.setArrayDimension("[" + upper + "]");
        }
        // DE_REF4: scalar — no change
    }

    /**
     * Finds a child {@link Element} of {@code parent} (by any element tag name)
     * whose {@code rolename} attribute equals {@code rolename}.
     *
     * <p>Handles both {@code platform:Composition} and {@code platform:Participant}
     * children since both carry a {@code rolename} and a {@code type} attribute.
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
     * in the UUID map whose {@code name} attribute equals {@code name}.
     * Results are cached in {@link #platformEntityByName} after the first call.
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
    // =========================================================================
    // Pass D: IntegrationContext collection
    // =========================================================================

    /**
     * Builds a map from each {@link ConnectionData#getUuid()} to its owning
     * {@link UoPData}, covering all UoPs in the model.
     */
    private static Map<String, UoPData> buildUopByConnectionUuidMap(List<UoPData> uoPs) {
        Map<String, UoPData> map = new LinkedHashMap<>();
        for (UoPData uop : uoPs) {
            for (ConnectionData conn : uop.getConnections()) {
                if (conn.getUuid() != null) {
                    map.put(conn.getUuid(), uop);
                }
            }
        }
        return map;
    }

    /**
     * Builds a map from each {@link ConnectionData#getUuid()} to the
     * {@link ConnectionData} object itself, covering all UoPs in the model.
     */
    private static Map<String, ConnectionData> buildConnectionByUuidMap(List<UoPData> uoPs) {
        Map<String, ConnectionData> map = new LinkedHashMap<>();
        for (UoPData uop : uoPs) {
            for (ConnectionData conn : uop.getConnections()) {
                if (conn.getUuid() != null) {
                    map.put(conn.getUuid(), conn);
                }
            }
        }
        return map;
    }

    /**
     * Walks the document tree and collects one {@link IntegrationContextData}
     * for every {@code integration:IntegrationContext} element found.
     *
     * <p>Returns an empty list (not an error) when the model has no integration
     * section — the UOP-scoped templates continue to work normally in that case,
     * and the new UOP_INTEGRATION_CONTEXT scope simply produces no output files.
     */
    private List<IntegrationContextData> collectIntegrationContexts(
            Element root,
            Map<String, UoPData> uopByConnUuid,
            Map<String, ConnectionData> connectionByUuid) {

        List<IntegrationContextData> result = new ArrayList<>();
        walkForIntegrationContexts(root, uopByConnUuid, connectionByUuid, result);
        return result;
    }

    private void walkForIntegrationContexts(Element el,
                                             Map<String, UoPData> uopByConnUuid,
                                             Map<String, ConnectionData> connectionByUuid,
                                             List<IntegrationContextData> result) {
        String xmiType = el.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

        if (INTEGRATION_CONTEXT_TYPE.equals(xmiType)) {
            // Produces one entry per (IC, UoP) pair — multiple UoPs may share an IC.
            List<IntegrationContextData> ics =
                    buildIntegrationContexts(el, uopByConnUuid, connectionByUuid);
            result.addAll(ics);
            return; // don't recurse into IC children for more ICs
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                walkForIntegrationContexts((Element) child, uopByConnUuid, connectionByUuid, result);
            }
        }
    }

    /**
     * Builds one {@link IntegrationContextData} per participating UoP from an
     * {@code integration:IntegrationContext} element.
     *
     * <p>A single IC can span multiple UoP instances (e.g. a PRODUCER in one
     * UoP and a CONSUMER in another).  Each participating UoP receives its own
     * {@link IntegrationContextData} entry containing only the connections that
     * belong to it within this IC.  The pipeline's
     * {@code UOP_INTEGRATION_CONTEXT} scope then generates a separate
     * ConnectionTable per (UoP, IC) pair.
     *
     * @return a list with one entry per participating UoP; empty if the IC has
     *         no name or no resolvable connections
     */
    private List<IntegrationContextData> buildIntegrationContexts(
            Element icEl,
            Map<String, UoPData> uopByConnUuid,
            Map<String, ConnectionData> connectionByUuid) {

        String icName = icEl.getAttribute("name");
        if (icName == null || icName.isEmpty()) {
            LOG.warning("integration:IntegrationContext element has no 'name' attribute — skipped.");
            return Collections.emptyList();
        }

        // Group connections by owning UoP name, preserving first-seen order.
        // Outer map: uopName → (connUuid → ConnectionData)
        LinkedHashMap<String, LinkedHashMap<String, ConnectionData>> connsByUop =
                new LinkedHashMap<>();

        String transportChannelName = null;

        NodeList children = icEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;

            String childType = childEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");

            if (INTEGRATION_TS_NODE_CONN_TYPE.equals(childType)) {
                String srcUuid = childEl.getAttribute("source");
                resolveEndpointConnectionGrouped(
                        srcUuid, connsByUop, uopByConnUuid, connectionByUuid);

                String dstUuid = childEl.getAttribute("destination");
                resolveEndpointConnectionGrouped(
                        dstUuid, connsByUop, uopByConnUuid, connectionByUuid);

            } else if (INTEGRATION_VIEW_TRANSPORTER_TYPE.equals(childType)
                    && transportChannelName == null) {
                transportChannelName = resolveTransportChannelName(childEl);
            }
        }

        if (connsByUop.isEmpty()) {
            LOG.warning("IntegrationContext '" + icName
                    + "' has no resolvable UoP connections — skipped.");
            return Collections.emptyList();
        }

        List<IntegrationContextData> result = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, ConnectionData>> entry
                : connsByUop.entrySet()) {
            IntegrationContextData ic = new IntegrationContextData();
            ic.setName(icName);
            ic.setUopName(entry.getKey());
            ic.setTransportChannelName(transportChannelName);
            ic.setConnections(new ArrayList<>(entry.getValue().values()));
            LOG.fine("IntegrationContext '" + icName + "' → UoP '" + entry.getKey()
                    + "', channel='" + transportChannelName
                    + "', connections=" + ic.getConnections().size());
            result.add(ic);
        }
        return result;
    }

    /**
     * Resolves a port UUID (from TSNodeConnection source or destination) to a
     * UoP connection and adds it to {@code scopedConns} if it is a
     * {@code UoPInputEndPoint} or {@code UoPOutputEndPoint}.
     *
     * <p>The endpoint element's {@code connection} attribute is the UUID of the
     * face.uop.Connection that flows through this TSNodeConnection.
     */
    /**
     * Resolves a port UUID to a UoP connection and groups it by owning UoP
     * in {@code connsByUop}.  Connections are deduplicated per UoP (putIfAbsent).
     */
    private void resolveEndpointConnectionGrouped(
            String portUuid,
            LinkedHashMap<String, LinkedHashMap<String, ConnectionData>> connsByUop,
            Map<String, UoPData> uopByConnUuid,
            Map<String, ConnectionData> connectionByUuid) {

        if (portUuid == null || portUuid.isEmpty()) return;
        String uuid = FaceXmiDocument.firstToken(portUuid);
        if (uuid == null) return;

        Element portEl = faceDoc.getUuidMap().get(uuid);
        if (portEl == null) return;

        String portType = portEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        if (!INTEGRATION_UOP_INPUT_EP_TYPE.equals(portType)
                && !INTEGRATION_UOP_OUTPUT_EP_TYPE.equals(portType)) {
            return;
        }

        String connUuid = portEl.getAttribute("connection");
        if (connUuid == null || connUuid.isEmpty()) return;
        connUuid = FaceXmiDocument.firstToken(connUuid);
        if (connUuid == null) return;

        ConnectionData conn = connectionByUuid.get(connUuid);
        if (conn == null) {
            LOG.fine("UoPEndPoint references connection UUID '" + connUuid
                    + "' which was not found in the UoP model — endpoint skipped.");
            return;
        }

        UoPData owningUop = uopByConnUuid.get(connUuid);
        if (owningUop == null) {
            LOG.fine("Connection UUID '" + connUuid
                    + "' has no owning UoP — endpoint skipped.");
            return;
        }

        connsByUop
                .computeIfAbsent(owningUop.getName(), k -> new LinkedHashMap<>())
                .putIfAbsent(connUuid, conn);
    }

    private void resolveEndpointConnection(String portUuid,
                                            LinkedHashMap<String, ConnectionData> scopedConns,
                                            Map<String, UoPData> uopByConnUuid,
                                            Map<String, ConnectionData> connectionByUuid) {
        if (portUuid == null || portUuid.isEmpty()) return;

        // portUuid may be a space-delimited list (per XMI idrefs convention) — take first
        String uuid = FaceXmiDocument.firstToken(portUuid);
        if (uuid == null) return;

        Element portEl = faceDoc.getUuidMap().get(uuid);
        if (portEl == null) return;

        String portType = portEl.getAttributeNS(FaceXmiDocument.XMI_NS, "type");
        if (!INTEGRATION_UOP_INPUT_EP_TYPE.equals(portType)
                && !INTEGRATION_UOP_OUTPUT_EP_TYPE.equals(portType)) {
            // Not a UoP endpoint — may be a TSNodePort on a ViewTransporter; skip.
            return;
        }

        String connUuid = portEl.getAttribute("connection");
        if (connUuid == null || connUuid.isEmpty()) return;

        // connUuid may also be space-delimited
        connUuid = FaceXmiDocument.firstToken(connUuid);
        if (connUuid == null) return;

        ConnectionData conn = connectionByUuid.get(connUuid);
        if (conn == null) {
            LOG.fine("UoPEndPoint references connection UUID '" + connUuid
                    + "' which was not found in the UoP model — endpoint skipped.");
            return;
        }

        scopedConns.putIfAbsent(connUuid, conn);
    }

    /**
     * Resolves the transport-channel name from a
     * {@code integration:ViewTransporter} element.
     *
     * <p>The {@code channel} attribute holds the UUID of the associated
     * {@code integration:TransportChannel} element; that element's {@code name}
     * attribute is the human-readable transport-service name.
     *
     * @param viewTransporterEl the {@code ViewTransporter} DOM element
     * @return transport channel name, or {@code null} if not resolvable
     */
    private String resolveTransportChannelName(Element viewTransporterEl) {
        String channelUuid = viewTransporterEl.getAttribute("channel");
        if (channelUuid == null || channelUuid.isEmpty()) return null;

        channelUuid = FaceXmiDocument.firstToken(channelUuid);
        if (channelUuid == null) return null;

        Element channelEl = faceDoc.getUuidMap().get(channelUuid);
        if (channelEl == null) return null;

        String name = channelEl.getAttribute("name");
        return (name != null && !name.isEmpty()) ? name : null;
    }


}
