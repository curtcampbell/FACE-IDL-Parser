package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.EntityData;
import com.warhex.er.generator.reader.dto.FieldData;
import com.warhex.er.generator.reader.dto.IdlModelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FaceTemplateEntityReader} using {@code examples/model.face}.
 *
 * <p>The sample model has a {@code um:UoPModel} group named {@code "Model_Templates"}
 * containing three template elements:
 * <ul>
 *   <li>T1 — {@code uop:Template}, fields resolved from {@code boundQuery}</li>
 *   <li>T2 — {@code uop:Template}, fields resolved from {@code boundQuery}</li>
 *   <li>PV1 — {@code uop:CompositeTemplate}, fields {@code mT1→T1} and {@code mT2→T2}</li>
 * </ul>
 */
@DisplayName("FaceTemplateEntityReader — model.face / Model_Templates group")
class FaceTemplateEntityReaderTest {

    private static final Path MODEL_FACE = Paths.get("examples/model.face");
    private static final String GROUP    = "Model_Templates";

    /** Populated by test 1; reused by subsequent tests. */
    private static IdlModelData idlData;

    @BeforeAll
    static void parse() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(MODEL_FACE),
                "Skipping — examples/model.face not found");
        idlData = new FaceTemplateEntityReader(GROUP).read(MODEL_FACE);
    }

    // -----------------------------------------------------------------------
    // Test 1: group found and types registered
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("1. Group found — produces IdlModelData with 3 entities (T1, T2, PV1)")
    void groupFoundThreeEntities() {
        assertNotNull(idlData, "IdlModelData must not be null");
        assertEquals(3, idlData.getEntities().size(),
                "Expected exactly 3 entities from Model_Templates group: "
                + entityNames(idlData));
    }

    // -----------------------------------------------------------------------
    // Test 2: entity simple names
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("2. Entity simple names are T1, T2, PV1")
    void entityNames() {
        List<String> names = entityNames(idlData);
        assertTrue(names.contains("T1"),  "Expected entity T1, got: " + names);
        assertTrue(names.contains("T2"),  "Expected entity T2, got: " + names);
        assertTrue(names.contains("PV1"), "Expected entity PV1, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Test 3: struct name pattern
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("3. structNamePattern is \"{name}Entity\" (entity reactor suffix)")
    void structNamePattern() {
        assertEquals("{name}Entity", idlData.getStructNamePattern(),
                "Template entity source must use the {name}Entity pattern");
    }

    // -----------------------------------------------------------------------
    // Test 4: IDL module
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("4. idlModule is \"FACE.DM.SampleUSM\"")
    void idlModule() {
        assertEquals("FACE.DM.SampleUSM", idlData.getIdlModule());
    }

    // -----------------------------------------------------------------------
    // Test 5: T1 fields resolved from boundQuery
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("5. T1 has fields resolved from its boundQuery (non-empty list)")
    void t1FieldsResolved() {
        EntityData t1 = entityByName(idlData, "T1");
        assertNotNull(t1.getFields(),    "T1 fields list must not be null");
        assertFalse(t1.getFields().isEmpty(),
                "T1 must have at least one field resolved from boundQuery");
    }

    // -----------------------------------------------------------------------
    // Test 6: PV1 is composite with mT1 and mT2 fields
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("6. PV1 is composite (isUnion=false), fields mT1→T1 and mT2→T2")
    void pv1CompositeFields() {
        EntityData pv1 = entityByName(idlData, "PV1");

        assertFalse(pv1.isUnion(), "PV1 should not be a union (isUnion not set in XMI)");

        List<FieldData> fields = pv1.getFields();
        assertEquals(2, fields.size(),
                "PV1 must have exactly 2 TemplateComposition fields");

        Map<String, FieldData> byName = fields.stream()
                .collect(Collectors.toMap(FieldData::getName, f -> f));

        assertTrue(byName.containsKey("mT1"), "PV1 must have field mT1");
        assertTrue(byName.containsKey("mT2"), "PV1 must have field mT2");
        assertEquals("T1", byName.get("mT1").getIdlType(),
                "mT1 must reference type T1");
        assertEquals("T2", byName.get("mT2").getIdlType(),
                "mT2 must reference type T2");
    }

    // -----------------------------------------------------------------------
    // Test 7: group not found → IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("7. Non-existent group name throws IllegalArgumentException")
    void groupNotFoundThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new FaceTemplateEntityReader("NonExistentGroup").read(MODEL_FACE),
                "Reader must throw IllegalArgumentException when group is not found");
    }

    // -----------------------------------------------------------------------
    // Test 8: empty group → empty entities list, no exception
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("8. Group with no template children produces empty entities list without throwing")
    void emptyGroupProducesEmptyList() throws Exception {
        // "UoPs" is a real um:UoPModel in model.face that contains only
        // uop:PortableComponent / uop:PlatformSpecificComponent children —
        // no uop:Template or uop:CompositeTemplate.
        // The reader must not throw; it must return an empty entities list.
        IdlModelData result = new FaceTemplateEntityReader("UoPs").read(MODEL_FACE);
        assertNotNull(result, "Result must not be null for an empty group");
        assertNotNull(result.getEntities(), "Entities list must not be null");
        assertTrue(result.getEntities().isEmpty(),
                "Entities list must be empty for a group with no template children");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<String> entityNames(IdlModelData data) {
        return data.getEntities().stream()
                .map(EntityData::getSimpleName)
                .collect(Collectors.toList());
    }

    private static EntityData entityByName(IdlModelData data, String name) {
        return data.getEntities().stream()
                .filter(e -> name.equals(e.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Entity not found: " + name + " in " + entityNames(data)));
    }
}
