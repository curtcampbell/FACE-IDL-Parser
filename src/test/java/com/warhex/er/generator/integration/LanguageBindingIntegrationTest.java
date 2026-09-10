package com.warhex.er.generator.integration;

import com.warhex.er.generator.ast.IdlSpecification;
import com.warhex.er.generator.binding.LanguageBindingPipeline;
import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.binding.generic.LanguageDescriptorLoader;
import com.warhex.er.generator.idl.ModelLoader;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.parser.IdlAstBuilder;
import com.warhex.er.generator.parser.IdlDirectoryParser;
import com.warhex.er.generator.parser.IdlParser;
import com.warhex.er.generator.parser.IdlParseResult;
import com.warhex.er.generator.reader.DefaultModelLoader;
import com.warhex.er.generator.reader.EntityModelMapper;
import com.warhex.er.generator.reader.ModelReaderFactory;
import com.warhex.er.generator.reader.ModelToIdlAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for Stage 2 (language binding).
 *
 * <h2>YAML path</h2>
 * Loads {@code examples/SampleModel.yaml} → {@link ModelToIdlAdapter} →
 * {@link IdlSpecification} → C++ and Python mappers.
 *
 * <h2>IDL path</h2>
 * Parses {@code examples/SampleModel.idl} via {@link IdlParser} +
 * {@link IdlAstBuilder} → same mappers.
 *
 * <p><strong>Prerequisites:</strong> The IDL-path test requires ANTLR4-generated
 * parser classes ({@code FACE_IDLParser}, {@code FACE_IDLLexer}, …) which are
 * created by the {@code antlr4-maven-plugin} during {@code mvn generate-sources}.
 * Run {@code mvn test} from the {@code code_generator/} directory.
 *
 * <p>Tests must be run with working directory = {@code code_generator/} so that
 * relative paths to {@code examples/} resolve correctly (Maven's default).
 */
class LanguageBindingIntegrationTest {

    private static final Path YAML_MODEL = Paths.get("examples/SampleModel.yaml");
    private static final Path IDL_MODEL  = Paths.get("examples/SampleModel.idl");

    // -----------------------------------------------------------------------
    // YAML input path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("YAML path: C++ and Python mappers produce expected files")
    void testYamlPathProducesExpectedFiles(@TempDir Path tempDir) throws Exception {
        // Arrange
        assumeFileExists(YAML_MODEL);

        ModelLoader loader = new DefaultModelLoader(
                ModelReaderFactory.forFile(YAML_MODEL),
                new EntityModelMapper());
        EntityModel model = loader.load(YAML_MODEL);
        IdlSpecification spec = new ModelToIdlAdapter().adapt(model);

        // Act
        LanguageBindingPipeline pipeline = new LanguageBindingPipeline(buildMappers());
        // Wrap IdlSpecification in IdlParseResult (no per-file units for YAML path)
        pipeline.generate(new IdlParseResult(spec, List.of()), tempDir);

        // Assert — C++ files
        Path cppBase = tempDir.resolve("cpp/FACE/DM/SampleModel");
        assertHppExists(cppBase, "GeoPosition");
        assertHppExists(cppBase, "ThreatLevel");
        assertHppExists(cppBase, "ThreatEntity");
        assertHppExists(cppBase, "TrackEntity");
        assertHppExists(cppBase, "WaypointEntity");

        // Spot-check TrackEntity.hpp content
        String trackHpp = Files.readString(cppBase.resolve("TrackEntity.hpp"));
        assertTrue(trackHpp.contains("struct TrackEntity"),
                "TrackEntity.hpp should declare struct TrackEntity");
        assertTrue(trackHpp.contains("FACE_DM_SAMPLEMODEL_TRACKENTITY_HPP"),
                "TrackEntity.hpp should have correct include guard");
        assertTrue(trackHpp.contains("#include \"GeoPosition.hpp\""),
                "TrackEntity.hpp should include GeoPosition.hpp");
        assertTrue(trackHpp.contains("TrackEntity()"),
                "TrackEntity.hpp should have a default constructor");

        // Spot-check ThreatLevel.hpp (enum)
        String threatLevelHpp = Files.readString(cppBase.resolve("ThreatLevel.hpp"));
        assertTrue(threatLevelHpp.contains("enum ThreatLevel"),
                "ThreatLevel.hpp should declare enum ThreatLevel");
        assertTrue(threatLevelHpp.contains("THREAT_UNKNOWN"),
                "ThreatLevel.hpp should list THREAT_UNKNOWN enumerator");

        // Assert — Python files
        Path pyBase = tempDir.resolve("python/FACE/DM/SampleModel");
        assertPyExists(pyBase, "GeoPosition");
        assertPyExists(pyBase, "ThreatLevel");
        assertPyExists(pyBase, "ThreatEntity");
        assertPyExists(pyBase, "TrackEntity");
        assertPyExists(pyBase, "WaypointEntity");
        assertTrue(Files.exists(pyBase.resolve("__init__.py")),
                "__init__.py must exist");

        // Spot-check TrackEntity.py content
        String trackPy = Files.readString(pyBase.resolve("TrackEntity.py"));
        assertTrue(trackPy.contains("@dataclass"),
                "TrackEntity.py should be annotated @dataclass");
        assertTrue(trackPy.contains("class TrackEntity"),
                "TrackEntity.py should declare class TrackEntity");
        assertTrue(trackPy.contains("from .GeoPosition import GeoPosition"),
                "TrackEntity.py should import GeoPosition");
        assertTrue(trackPy.contains("heading_deg: float"),
                "TrackEntity.py should have heading_deg: float field");

        // Spot-check ThreatLevel.py (IntEnum)
        String threatLevelPy = Files.readString(pyBase.resolve("ThreatLevel.py"));
        assertTrue(threatLevelPy.contains("class ThreatLevel(IntEnum)"),
                "ThreatLevel.py should declare ThreatLevel as IntEnum");
        assertTrue(threatLevelPy.contains("THREAT_UNKNOWN = 0"),
                "ThreatLevel.py should assign THREAT_UNKNOWN = 0");

        // Spot-check __init__.py re-exports
        String initPy = Files.readString(pyBase.resolve("__init__.py"));
        assertTrue(initPy.contains("from .TrackEntity import TrackEntity"),
                "__init__.py should re-export TrackEntity");
    }

    // -----------------------------------------------------------------------
    // IDL input path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("IDL path: C++ and Python mappers produce expected files")
    void testIdlPathProducesExpectedFiles(@TempDir Path tempDir) throws Exception {
        // Arrange
        assumeFileExists(IDL_MODEL);

        IdlParser parser = new IdlParser();
        IdlAstBuilder builder = new IdlAstBuilder();
        IdlSpecification spec = builder.visitSpecification(parser.parse(IDL_MODEL));

        // Sanity-check the parsed spec before running the mappers
        assertNotNull(spec, "IdlAstBuilder should return a non-null spec");
        assertFalse(spec.definitions().isEmpty(), "Spec should have at least one definition");

        // Act
        LanguageBindingPipeline pipeline = new LanguageBindingPipeline(buildMappers());
        // Wrap IdlSpecification in IdlParseResult (no per-file units here — tests
        // the per-type fallback path; per-file path tested via IDL directory parser)
        pipeline.generate(new IdlParseResult(spec, List.of()), tempDir);

        // Assert — same structural checks as YAML path
        Path cppBase = tempDir.resolve("cpp/FACE/DM/SampleModel");
        assertHppExists(cppBase, "GeoPosition");
        assertHppExists(cppBase, "TrackEntity");
        assertHppExists(cppBase, "ThreatEntity");
        assertHppExists(cppBase, "WaypointEntity");
        assertHppExists(cppBase, "ThreatLevel");

        Path pyBase = tempDir.resolve("python/FACE/DM/SampleModel");
        assertPyExists(pyBase, "GeoPosition");
        assertPyExists(pyBase, "TrackEntity");
        assertPyExists(pyBase, "ThreatEntity");
        assertPyExists(pyBase, "WaypointEntity");
        assertPyExists(pyBase, "ThreatLevel");
        assertTrue(Files.exists(pyBase.resolve("__init__.py")),
                "__init__.py must exist");

        // Structural content checks
        String trackHpp = Files.readString(cppBase.resolve("TrackEntity.hpp"));
        assertTrue(trackHpp.contains("struct TrackEntity"),   "C++ struct declared");
        assertTrue(trackHpp.contains("FACE_DM_SAMPLEMODEL_TRACKENTITY_HPP"), "Guard present");

        String trackPy = Files.readString(pyBase.resolve("TrackEntity.py"));
        assertTrue(trackPy.contains("@dataclass"),            "Python @dataclass present");
        assertTrue(trackPy.contains("class TrackEntity"),     "Python class declared");
    }

    // -----------------------------------------------------------------------
    // IDL directory path (exercises IdlDirectoryParser + all 4 languages)
    // -----------------------------------------------------------------------

    /**
     * Exercises the full {@code generate --all-languages} code path:
     * <ol>
     *   <li>{@link IdlDirectoryParser} parses {@code examples/test-output/IDL/}
     *       using {@code face-idl/} as the framework search directory.</li>
     *   <li>All four language mappers (C++, C#, Java, Python) run against the
     *       resulting {@link IdlParseResult}.</li>
     *   <li>Spot-checks confirm that user-defined types appear in every language's
     *       output and that framework include types (TSS, Common) do NOT.</li>
     * </ol>
     *
     * <p>This test reproduces the bug where only C++ output was produced because
     * {@code per_construct} mappers walked the merged spec (including framework
     * includes) instead of the per-file units.
     */
    @Test
    @DisplayName("IDL directory path: all four languages produce expected output")
    void testIdlDirectoryPathAllLanguages(@TempDir Path tempDir) throws Exception {
        Path idlDir    = Paths.get("examples/test-output/IDL");
        Path faceIdl   = Paths.get("face-idl");
        assumeFileExists(idlDir.resolve("Entities/GeoPosition.idl"));
        assumeFileExists(faceIdl.resolve("FACE/Common.idl"));

        // Parse the generated IDL directory (same as generate --all-languages step 2)
        IdlParseResult result =
                new IdlDirectoryParser(List.of(faceIdl)).parse(idlDir);

        assertFalse(result.fileUnits().isEmpty(),
                "IdlDirectoryParser should produce at least one file unit");

        // Run all four language mappers
        LanguageBindingPipeline pipeline = new LanguageBindingPipeline(buildMappers());
        pipeline.generate(result, tempDir);

        // ---- C++ assertions ----
        Path cppBase = tempDir.resolve("cpp/FACE/DM/SampleModel");
        assertHppExists(cppBase, "GeoPosition");
        assertHppExists(cppBase, "ThreatEntity");
        assertHppExists(cppBase, "TrackEntity");

        // ---- C# assertions ----
        Path csBase = tempDir.resolve("csharp/FACE/DM/SampleModel");
        assertTrue(Files.exists(csBase.resolve("GeoPosition.cs")),
                "C# GeoPosition.cs must exist");
        assertTrue(Files.exists(csBase.resolve("ThreatEntity.cs")),
                "C# ThreatEntity.cs must exist");
        String geoCs = Files.readString(csBase.resolve("GeoPosition.cs"));
        assertTrue(geoCs.contains("class GeoPosition") || geoCs.contains("struct GeoPosition"),
                "GeoPosition.cs should declare GeoPosition type");

        // ---- Java assertions ----
        Path javaBase = tempDir.resolve("java/face/dm/samplemodel");
        assertTrue(Files.exists(javaBase.resolve("GeoPosition.java")),
                "Java GeoPosition.java must exist");
        assertTrue(Files.exists(javaBase.resolve("ThreatEntity.java")),
                "Java ThreatEntity.java must exist");

        // ---- Python assertions ----
        Path pyBase = tempDir.resolve("python/FACE/DM/SampleModel");
        assertPyExists(pyBase, "GeoPosition");
        assertPyExists(pyBase, "ThreatEntity");
        assertTrue(Files.exists(pyBase.resolve("__init__.py")),
                "__init__.py must exist");

        // ---- Negative: framework types must NOT appear in C# or Java output ----
        // (they were previously rendered when merged spec was walked directly)
        Path csFramework = tempDir.resolve("csharp/FACE/TSS");
        assertFalse(Files.exists(csFramework.resolve("QoS_Element.cs")),
                "C# output must not contain TSS framework type QoS_Element");
    }

    // -----------------------------------------------------------------------
    // Spec-only: CppTypeHelper type mapping
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CppTypeHelper: Table 14 primitive type mappings are correct")
    void testCppTypeMapping() {
        com.warhex.er.generator.binding.cpp.CppTypeHelper cpp =
                new com.warhex.er.generator.binding.cpp.CppTypeHelper();

        assertEquals("FACE::Short",
                cpp.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.SHORT)));
        assertEquals("FACE::Float",
                cpp.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.FLOAT)));
        assertEquals("FACE::Boolean",
                cpp.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.BOOLEAN)));
        assertEquals("FACE::GUID_TYPE",
                cpp.type(new com.warhex.er.generator.ast.IdlType.Scoped("::FACE::GUID_TYPE")));
    }

    @Test
    @DisplayName("PythonTypeHelper: IDL primitive type mappings are correct")
    void testPythonTypeMapping() {
        com.warhex.er.generator.binding.python.PythonTypeHelper py =
                new com.warhex.er.generator.binding.python.PythonTypeHelper();

        assertEquals("int",
                py.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.SHORT)));
        assertEquals("float",
                py.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.FLOAT)));
        assertEquals("bool",
                py.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.BOOLEAN)));
        assertEquals("str",
                py.type(new com.warhex.er.generator.ast.IdlType.Primitive(
                        com.warhex.er.generator.ast.PrimitiveKind.CHAR)));
        assertEquals("int",
                py.type(new com.warhex.er.generator.ast.IdlType.Scoped("::FACE::GUID_TYPE")));
        assertEquals("str",
                py.type(new com.warhex.er.generator.ast.IdlType.Scoped("::FACE::STRING_TYPE")));
        // Local class type — preserves simple name
        assertEquals("GeoPosition",
                py.type(new com.warhex.er.generator.ast.IdlType.Scoped("GeoPosition")));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void assertHppExists(Path dir, String typeName) {
        Path p = dir.resolve(typeName + ".hpp");
        assertTrue(Files.exists(p), typeName + ".hpp should exist at " + p);
    }

    private void assertPyExists(Path dir, String typeName) {
        Path p = dir.resolve(typeName + ".py");
        assertTrue(Files.exists(p), typeName + ".py should exist at " + p);
    }

    /**
     * Builds the mapper list: all languages from generic engine
     * (templates/languages/<lang>/language.yaml).  C++ joined in Phase 5.
     */
    private List<LanguageMapper> buildMappers() {
        Path langRoot = Paths.get("templates/languages");
        if (Files.isDirectory(langRoot)) {
            return new ArrayList<>(new LanguageDescriptorLoader().load(langRoot));
        }
        return List.of();
    }

    /** Skip the test gracefully when the example file is not on the path. */
    private void assumeFileExists(Path path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(path),
                "Skipping test — file not found: " + path.toAbsolutePath());
    }
}
