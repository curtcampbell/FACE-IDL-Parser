package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.EnumData;
import com.warhex.er.generator.reader.dto.EnumValueData;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates IDL typedef and enum files for named platform types found inside
 * {@code datamodel:DataModel} elements in a {@code .face} XMI file.
 *
 * <p>For each {@code pdm:PlatformDataModel} child of a {@code dm:DataModel},
 * one IDL file is written per named platform type:
 * <ul>
 *   <li>Primitive types ({@code platform:String}, {@code platform:Long}, …) →
 *       {@code typedef <primitive> <Name>;}</li>
 *   <li>{@code platform:Enumeration} →
 *       {@code enum <Name> { val1, val2, … };}</li>
 * </ul>
 *
 * <p>Output layout relative to {@code outputRoot}:
 * <pre>
 *   FACE/DM/&lt;DataModelName&gt;/&lt;TypeName&gt;.idl
 * </pre>
 * This mirrors the {@code #include &lt;FACE/DM/&lt;DataModelName&gt;/&lt;TypeName&gt;.idl&gt;}
 * paths used in generated template IDL files, so adding {@code outputRoot} to the
 * IDL search path makes all cross-references resolvable.
 */
public class PlatformDataModelIdlGenerator {

    private static final Logger LOG =
            Logger.getLogger(PlatformDataModelIdlGenerator.class.getName());

    static final String XMI_NS = FaceXmiDocument.XMI_NS;

    private final FaceXmiDocument faceDoc;

    public PlatformDataModelIdlGenerator(FaceXmiDocument faceDoc) {
        this.faceDoc = faceDoc;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Scans the document root for {@code dm xmi:type="datamodel:DataModel"} children
     * and generates one IDL file per named platform type under {@code outputRoot}.
     *
     * @param outputRoot root directory; files land at
     *                   {@code outputRoot/FACE/DM/<DataModelName>/<TypeName>.idl}
     * @throws IOException on any file-system error
     */
    public void generate(Path outputRoot) throws IOException {
        Element root = faceDoc.getRoot();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element dm = (Element) n;
            String dmType = dm.getAttributeNS(XMI_NS, "type");
            if (!"datamodel:DataModel".equals(dmType)) continue;
            String modelName = dm.getAttribute("name");
            if (modelName == null || modelName.isEmpty()) continue;
            Path modelDir = outputRoot.resolve("FACE/DM/" + modelName);
            generateNamedTypes(dm, modelName, modelDir);
            LOG.info("Platform data-model IDL generated for: " + modelName);
        }
    }

    // -------------------------------------------------------------------------
    // Recursive type scanner
    // -------------------------------------------------------------------------

    /**
     * Recursively scans {@code parent} for named platform types and writes an
     * IDL file for each one found.  Non-platform elements (e.g.
     * {@code pdm:PlatformDataModel} grouping elements) are traversed without
     * generating any output themselves.
     */
    private void generateNamedTypes(Element parent, String modelName, Path modelDir)
            throws IOException {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;

            String xmiType = el.getAttributeNS(XMI_NS, "type");
            String uuid    = el.getAttributeNS(XMI_NS, "id");
            String name    = el.getAttribute("name");

            if (xmiType == null || !xmiType.startsWith("platform:")) {
                // Non-platform grouping element — recurse
                generateNamedTypes(el, modelName, modelDir);
                continue;
            }
            if (name == null || name.isEmpty()) continue; // anonymous — skip

            if ("platform:Enumeration".equals(xmiType)) {
                EnumData enumData = faceDoc.resolveOrCacheEnum(uuid, el);
                if (enumData != null && !enumData.getValues().isEmpty()) {
                    writeEnumIdl(modelDir.resolve(name + ".idl"), modelName, name, enumData);
                } else {
                    LOG.warning("platform:Enumeration '" + name
                            + "' in " + modelName + " has no resolvable values — skipped");
                }

            } else if ("platform:Struct".equals(xmiType)) {
                // platform:Struct is handled inline (nested struct) — skip standalone file
                LOG.fine("Skipping platform:Struct '" + name + "' in " + modelName);

            } else {
                // Named primitive typedef
                FaceXmiDocument.PlatformTypeResult ptr =
                        FaceXmiDocument.resolvePrimitiveIdlType(xmiType, el);
                if (ptr != null) {
                    writeTypedefIdl(modelDir.resolve(name + ".idl"),
                            modelName, name, ptr.idlType, ptr.arrayDimension);
                } else {
                    LOG.warning("Unsupported platform type '" + xmiType
                            + "' for '" + name + "' in " + modelName + " — skipped");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // IDL file writers
    // -------------------------------------------------------------------------

    private void writeTypedefIdl(Path outFile, String modelName,
                                 String typeName, String primitiveType,
                                 String arrayDimension) throws IOException {
        Files.createDirectories(outFile.getParent());
        String guard  = toIdlGuard(modelName, typeName);
        String indent = "      "; // 6 spaces — inside 3 module levels

        StringBuilder sb = new StringBuilder();
        appendFileHeader(sb, "Platform typedef", modelName, typeName, guard);
        sb.append("module FACE {\n");
        sb.append("  module DM {\n");
        sb.append("    module ").append(modelName).append(" {\n");
        if (arrayDimension == null || arrayDimension.isEmpty()) {
            sb.append(indent).append("typedef ")
              .append(primitiveType).append(" ").append(typeName).append(";\n");
        } else {
            // e.g. platform:CharArray → typedef char TypeName[N];
            sb.append(indent).append("typedef ")
              .append(primitiveType).append(" ").append(typeName)
              .append(arrayDimension).append(";\n");
        }
        sb.append("    };\n");
        sb.append("  };\n");
        sb.append("};\n\n");
        sb.append("#endif // ").append(guard).append("\n");

        Files.writeString(outFile, sb.toString());
        LOG.fine("  wrote typedef IDL: " + outFile.getFileName());
    }

    private void writeEnumIdl(Path outFile, String modelName,
                              String typeName, EnumData enumData) throws IOException {
        Files.createDirectories(outFile.getParent());
        String guard  = toIdlGuard(modelName, typeName);
        String indent = "      ";

        StringBuilder sb = new StringBuilder();
        appendFileHeader(sb, "Platform enumeration", modelName, typeName, guard);
        sb.append("module FACE {\n");
        sb.append("  module DM {\n");
        sb.append("    module ").append(modelName).append(" {\n");
        sb.append(indent).append("enum ").append(typeName).append(" {\n");

        List<EnumValueData> values = enumData.getValues();
        for (int i = 0; i < values.size(); i++) {
            sb.append(indent).append("  ").append(values.get(i).getName());
            if (i < values.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(indent).append("};\n");
        sb.append("    };\n");
        sb.append("  };\n");
        sb.append("};\n\n");
        sb.append("#endif // ").append(guard).append("\n");

        Files.writeString(outFile, sb.toString());
        LOG.fine("  wrote enum IDL: " + outFile.getFileName());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void appendFileHeader(StringBuilder sb, String kind,
                                         String modelName, String typeName,
                                         String guard) {
        sb.append("//! Source file: FACE/DM/").append(modelName)
          .append("/").append(typeName).append(".idl\n");
        sb.append("//!\n");
        sb.append("//! Origin: ER IDL Generator — generated from FACE data model (")
          .append(kind).append(").\n");
        sb.append("//!\n");
        sb.append("//! FACE DataModel: ").append(modelName).append("\n\n");
        sb.append("#ifndef ").append(guard).append("\n");
        sb.append("#define ").append(guard).append("\n\n");
        sb.append("#include <FACE/Common.idl>\n\n");
    }

    /**
     * Converts a model name and type name to an IDL include-guard macro, e.g.
     * {@code "Data_Model"} + {@code "Identifier_UUID_String"} →
     * {@code "FACE_DM_DATA_MODEL_IDENTIFIER_UUID_STRING_IDL"}.
     */
    static String toIdlGuard(String modelName, String typeName) {
        String m = modelName.toUpperCase().replace('-', '_');
        String t = typeName.toUpperCase().replace('-', '_');
        return "FACE_DM_" + m + "_" + t + "_IDL";
    }
}
