package com.warhex.er.generator.integration;

import com.warhex.er.generator.idl.IdlGeneratorPipeline;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.reader.EntityModelMapper;
import com.warhex.er.generator.reader.dto.IdlModelData;
import com.warhex.er.generator.reader.dto.UoPModelData;
import com.warhex.er.generator.reader.face.FaceTemplateEntityReader;
import com.warhex.er.generator.reader.face.FaceTssReader;
import com.warhex.er.generator.reader.face.TssToEntityModelAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: {@code .face} → {@link UoPModelData} →
 * {@link IdlModelData} → {@link EntityModel} → IDL files on disk.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   examples/model.face
 *     → FaceTssReader         (UoPModelData)
 *     → TssToEntityModelAdapter (IdlModelData)
 *     → EntityModelMapper     (EntityModel)
 *     → IdlGeneratorPipeline  (data-model-idl templates)
 *       → data-model/  — one .idl per Template/CompositeTemplate
 *       → tss/         — one .idl per unique connection type (deduplicated)
 * </pre>
 *
 * <p>Templates are loaded from {@code templates/data-model-idl}.
 * The test uses a JUnit {@link TempDir} as the output root.
 *
 * <p>Also covers the template-entity-source path (Workflow B):
 * <pre>
 *   examples/model.face + "Model_Templates" group
 *     → FaceTemplateEntityReader  (IdlModelData with structNamePattern="{name}Entity")
 *     → EntityModelMapper         (EntityModel)
 *     → IdlGeneratorPipeline      (entity-reactor-idl templates)
 *       → IDL/  — one .idl per entity, named <Name>Entity.idl
 * </pre>
 */
@DisplayName("TSS IDL Generator — end-to-end integration")
class TssIdlGeneratorIntegrationTest {

    private static final Path MODEL_FACE         = Paths.get("examples/model.face");
    private static final Path TEMPLATE_ROOT      = Paths.get("templates/data-model-idl");
    private static final Path ER_TEMPLATE_ROOT   = Paths.get("templates/entity-reactor-idl");

    @BeforeAll
    static void checkPrerequisites() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(MODEL_FACE),
                "Skipping — examples/model.face not found");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(TEMPLATE_ROOT),
                "Skipping — templates/data-model-idl not found");
    }

    /** Runs the full TSS pipeline and returns the output root. */
    private static Path runPipeline(Path outputRoot) throws Exception {
        UoPModelData uoPModel = new FaceTssReader().read(MODEL_FACE);
        IdlModelData idlData  = new TssToEntityModelAdapter().adapt(uoPModel);
        EntityModel  model    = new EntityModelMapper().map(idlData);

        IdlGeneratorPipeline pipeline = new IdlGeneratorPipeline(TEMPLATE_ROOT);
        pipeline.generate(model, uoPModel, outputRoot);
        return outputRoot;
    }

    // -----------------------------------------------------------------------
    // Data-model structs
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("data-model: one IDL file generated per platform type (T1, T2, PV1)")
    void dataModelStructFiles(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        // struct.vtl produces: <outputRoot>/<relDir>/<StructName>.idl
        // The output directory is whatever struct.vtl's @outputDir resolves to.
        // Walk the tree and check by filename.
        assertTrue(idlExistsAnywhere(tmp, "T1.idl"),  "T1.idl should be generated");
        assertTrue(idlExistsAnywhere(tmp, "T2.idl"),  "T2.idl should be generated");
        assertTrue(idlExistsAnywhere(tmp, "PV1.idl"), "PV1.idl should be generated");
    }

    @Test
    @DisplayName("data-model struct: T1.idl contains struct declaration and include guard")
    void t1IdlContent(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        Path t1 = findIdlAnywhere(tmp, "T1.idl");
        assertNotNull(t1, "T1.idl not found under " + tmp);

        String content = Files.readString(t1);
        assertTrue(content.contains("struct T1"),
                "T1.idl should declare 'struct T1'");
        assertTrue(content.contains("#ifndef") || content.contains("#define"),
                "T1.idl should have an include guard");
        assertFalse(content.contains("DATATYPE_TYPE"),
                "T1.idl must not contain unresolved template placeholders");
    }

    @Test
    @DisplayName("data-model struct: PV1.idl contains struct declaration with fields mT1 and mT2")
    void pv1IdlContent(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        Path pv1 = findIdlAnywhere(tmp, "PV1.idl");
        assertNotNull(pv1, "PV1.idl not found under " + tmp);

        String content = Files.readString(pv1);
        assertTrue(content.contains("struct PV1") || content.contains("union PV1"),
                "PV1.idl should declare struct or union PV1");
        assertTrue(content.contains("mT1"), "PV1.idl should have field mT1");
        assertTrue(content.contains("mT2"), "PV1.idl should have field mT2");
    }

    // -----------------------------------------------------------------------
    // UoP TypedTS files — standard variant
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tss: standard TypedTS generated for UoP1MP1 (QUEUING/PV1) at FACE/TSS/.../PV1/TypedTS.idl")
    void standardTssFileUoP1(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);
        // typed_ts_standard.vtl: output path = FACE/TSS/<model>/PV1/TypedTS.idl  (§4.8.4.1)
        assertTrue(tssIdlExistsFor(tmp, "PV1"),
                "PV1/TypedTS.idl should be generated for QUEUING connection UoP1MP1");
    }

    @Test
    @DisplayName("tss: standard TypedTS generated for UoP1MP2 (SINGLE_INSTANCE/T1) at FACE/TSS/.../T1/TypedTS.idl")
    void standardTssFileUoP1Mp2(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);
        // UoP1MP2 → SINGLE_INSTANCE → T1 → FACE/TSS/<model>/T1/TypedTS.idl  (§4.8.4.1)
        assertTrue(tssIdlExistsFor(tmp, "T1"),
                "T1/TypedTS.idl should be generated for SINGLE_INSTANCE connection UoP1MP2");
    }

    @Test
    @DisplayName("tss: standard TypedTS generated for UoP2MP2 (SINGLE_INSTANCE/T2) at FACE/TSS/.../T2/TypedTS.idl")
    void standardTssFileUoP2Mp2(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);
        // UoP2MP2 → SINGLE_INSTANCE → T2 → FACE/TSS/<model>/T2/TypedTS.idl  (§4.8.4.1)
        assertTrue(tssIdlExistsFor(tmp, "T2"),
                "T2/TypedTS.idl should be generated for SINGLE_INSTANCE connection UoP2MP2");
    }

    @Test
    @DisplayName("tss: standard TypedTS content includes TypedTS include and typedef")
    void standardTssContent(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        Path tsFile = findTssIdlFor(tmp, "PV1");
        assertNotNull(tsFile, "PV1/TypedTS.idl not found under " + tmp);

        String content = Files.readString(tsFile);
        // Must include the standard TypedTS header
        assertTrue(content.contains("TypedTS.idl"),
                "Standard TypedTS file must include TypedTS.idl");
        // Must have a typedef for the message type
        assertTrue(content.contains("PV1_t"),
                "Standard TypedTS file must declare PV1_t typedef");
        // Must instantiate FACE::TSS::Typed
        assertTrue(content.contains("FACE::TSS::Typed"),
                "Standard TypedTS file must instantiate ::FACE::TSS::Typed");
    }

    // -----------------------------------------------------------------------
    // UoP TypedTS files — extended variant
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tss: extended TypedTS generated for UoP1CS1 at FACE/TSS/.../PV1_T2/TypedTS.idl")
    void extendedTssFileUoP1Cs1(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);
        // typed_ts_extended.vtl: output path = FACE/TSS/<model>/PV1_T2/TypedTS.idl  (§4.8.4.1)
        // UoP1CS1 → requestType=PV1, responseType=T2 → PV1_T2/TypedTS.idl
        assertTrue(tssIdlExistsFor(tmp, "PV1_T2"),
                "PV1_T2/TypedTS.idl should be generated for CLIENT_SERVER connection UoP1CS1");
    }

    @Test
    @DisplayName("tss: extended TypedTS generated for UoP2CS1 at FACE/TSS/.../PV1_T1/TypedTS.idl")
    void extendedTssFileUoP2Cs1(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);
        // UoP2CS1 → requestType=PV1, responseType=T1 → FACE/TSS/<model>/PV1_T1/TypedTS.idl  (§4.8.4.1)
        assertTrue(tssIdlExistsFor(tmp, "PV1_T1"),
                "PV1_T1/TypedTS.idl should be generated for CLIENT_SERVER connection UoP2CS1");
    }

    @Test
    @DisplayName("tss: extended TypedTS content includes Extended.idl and both typedefs")
    void extendedTssContent(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        Path tsFile = findTssIdlFor(tmp, "PV1_T2");
        assertNotNull(tsFile, "PV1_T2/TypedTS.idl not found under " + tmp);

        String content = Files.readString(tsFile);
        assertTrue(content.contains("Extended.idl"),
                "Extended TypedTS file must include Extended.idl");
        assertTrue(content.contains("PV1_t"),
                "Extended TypedTS file must declare PV1_t typedef");
        assertTrue(content.contains("T2_t"),
                "Extended TypedTS file must declare T2_t typedef");
        assertTrue(content.contains("FACE::TSS::Typed"),
                "Extended TypedTS file must instantiate ::FACE::TSS::Typed");
    }

    // -----------------------------------------------------------------------
    // Blank-output guard: STANDARD connections must not produce extended files
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no extended file should be named after a pub/sub connection's single message type")
    void noSpuriousExtendedFiles(@TempDir Path tmp) throws Exception {
        runPipeline(tmp);

        // typed_ts_extended.vtl uses #stop for STANDARD connections → blank → skipped.
        // A spurious file would look like T1_null_ts.idl or T1__ts.idl.
        // With the new layout, a spurious extended file would appear as TypedTS.idl
        // under a directory named T1_null, T1_, or PV1_null.
        assertFalse(tssIdlExistsFor(tmp, "T1_null"),
                "No TypedTS.idl should exist under T1_null/ (null responseMessageType)");
        assertFalse(tssIdlExistsFor(tmp, "T1_"),
                "No TypedTS.idl should exist under T1_/ (empty response name)");
        assertFalse(tssIdlExistsFor(tmp, "PV1_null"),
                "No TypedTS.idl should exist under PV1_null/ (null responseMessageType)");
    }

    // -----------------------------------------------------------------------
    // Workflow B: template entity source → entity-reactor IDL
    // -----------------------------------------------------------------------

    /**
     * End-to-end test for the template entity source path (Workflow B).
     *
     * <p>Runs {@link FaceTemplateEntityReader} with group {@code "Model_Templates"}
     * against {@code examples/model.face}, then drives the entity-reactor IDL pipeline,
     * and asserts that {@code T1Entity.idl}, {@code T2Entity.idl}, and
     * {@code PV1Entity.idl} are produced in the output directory.
     */
    @Test
    @DisplayName("Workflow B: template entity source produces T1Entity.idl, T2Entity.idl, PV1Entity.idl")
    void templateEntitySourceProducesEntityIdls(@TempDir Path tmp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(ER_TEMPLATE_ROOT),
                "Skipping — templates/entity-reactor-idl not found");

        IdlModelData idlData = new FaceTemplateEntityReader("Model_Templates").read(MODEL_FACE);
        EntityModel  model   = new EntityModelMapper().map(idlData);

        Path idlOutDir = tmp.resolve("IDL");
        new IdlGeneratorPipeline(ER_TEMPLATE_ROOT).generate(model, idlOutDir);

        assertTrue(idlExistsAnywhere(tmp, "T1Entity.idl"),
                "T1Entity.idl must be generated from template entity source");
        assertTrue(idlExistsAnywhere(tmp, "T2Entity.idl"),
                "T2Entity.idl must be generated from template entity source");
        assertTrue(idlExistsAnywhere(tmp, "PV1Entity.idl"),
                "PV1Entity.idl must be generated from template entity source");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns true if any .idl file anywhere under root has the given filename. */
    private static boolean idlExistsAnywhere(Path root, String filename) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().equals(filename));
        }
    }

    /** Returns the first path matching filename under root, or null if not found. */
    private static Path findIdlAnywhere(Path root, String filename) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(filename))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Returns true if a {@code TypedTS.idl} file exists anywhere under {@code root}
     * whose immediate parent directory name equals {@code typeDirName}.
     *
     * <p>Used to verify §4.8.4.1-compliant output paths such as
     * {@code FACE/TSS/Model_Templates/PV1/TypedTS.idl}.
     */
    private static boolean tssIdlExistsFor(Path root, String typeDirName) throws Exception {
        return findTssIdlFor(root, typeDirName) != null;
    }

    /**
     * Returns the first {@code TypedTS.idl} whose parent directory name equals
     * {@code typeDirName}, or {@code null} if not found.
     */
    private static Path findTssIdlFor(Path root, String typeDirName) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("TypedTS.idl"))
                    .filter(p -> p.getParent() != null
                              && p.getParent().getFileName().toString().equals(typeDirName))
                    .findFirst()
                    .orElse(null);
        }
    }
}
