package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TssToEntityModelAdapter}.
 *
 * <p>Two test scenarios:
 * <ol>
 *   <li><strong>Constructed</strong> — builds a minimal {@link UoPModelData} in code so
 *       tests run without the FACE file on the classpath.</li>
 *   <li><strong>Round-trip</strong> — reads {@code examples/model.face} via
 *       {@link FaceTssReader} and adapts it, then verifies the output shape.</li>
 * </ol>
 */
@DisplayName("TssToEntityModelAdapter")
class TssToEntityModelAdapterTest {

    private static final TssToEntityModelAdapter ADAPTER = new TssToEntityModelAdapter();

    // -----------------------------------------------------------------------
    // Constructed input — no file I/O required
    // -----------------------------------------------------------------------

    private static UoPModelData buildMinimalModel() {
        TssTypeData t1 = new TssTypeData();
        t1.setName("T1");
        t1.setUnion(false);
        FieldData f1 = new FieldData();
        f1.setName("obs3");
        f1.setIdlType("unsigned long");
        t1.setFields(List.of(f1));

        TssTypeData pv1 = new TssTypeData();
        pv1.setName("PV1");
        pv1.setUnion(false);
        FieldData fPv1 = new FieldData();
        fPv1.setName("mT1");
        fPv1.setIdlType("T1");
        pv1.setFields(List.of(fPv1));

        UoPModelData m = new UoPModelData();
        m.setModelName("MyModel");
        m.setIdlModule("FACE.DM.MyModel");
        m.setPlatformTypes(List.of(t1, pv1));
        m.setUoPs(new ArrayList<>());
        return m;
    }

    @Test
    @DisplayName("model name is copied through")
    void modelName() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals("MyModel", out.getModelName());
    }

    @Test
    @DisplayName("idlModule copied from UoPModelData")
    void idlModule() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals("FACE.DM.MyModel", out.getIdlModule());
    }

    @Test
    @DisplayName("tssIdlModule replaces .DM. with .TSS.")
    void tssModuleDerived() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals("FACE.TSS.MyModel", out.getTssIdlModule());
    }

    @Test
    @DisplayName("cppNamespace uses :: separator")
    void cppNamespace() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals("FACE::DM::MyModel", out.getCppNamespace());
    }

    @Test
    @DisplayName("structNamePattern is identity {name}")
    void structNamePattern() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals("{name}", out.getStructNamePattern());
    }

    @Test
    @DisplayName("entity count matches platform type count")
    void entityCount() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        assertEquals(2, out.getEntities().size());
    }

    @Test
    @DisplayName("entity names match TssTypeData names")
    void entityNames() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        List<String> names = out.getEntities().stream()
                .map(e -> e.getSimpleName())
                .collect(Collectors.toList());
        assertTrue(names.contains("T1"),  "Expected T1: " + names);
        assertTrue(names.contains("PV1"), "Expected PV1: " + names);
    }

    @Test
    @DisplayName("entity union flag matches TssTypeData.isUnion()")
    void entityUnionFlag() {
        UoPModelData m = buildMinimalModel();
        m.getPlatformTypes().get(0).setUnion(true);  // T1 → union

        IdlModelData out = ADAPTER.adapt(m);
        Map<String, Boolean> unionByName = out.getEntities().stream()
                .collect(Collectors.toMap(e -> e.getSimpleName(), e -> e.isUnion()));

        assertTrue(unionByName.get("T1"),   "T1 should be union");
        assertFalse(unionByName.get("PV1"), "PV1 should not be union");
    }

    @Test
    @DisplayName("entity fields are passed through by reference")
    void entityFields() {
        IdlModelData out = ADAPTER.adapt(buildMinimalModel());
        EntityData t1Entity = out.getEntities().stream()
                .filter(e -> "T1".equals(e.getSimpleName()))
                .findFirst().orElseThrow();

        assertEquals(1, t1Entity.getFields().size());
        assertEquals("obs3",         t1Entity.getFields().get(0).getName());
        assertEquals("unsigned long", t1Entity.getFields().get(0).getIdlType());
    }

    @Test
    @DisplayName("tssIdlModule falls back to append .TSS when .DM. not present")
    void tssModuleFallback() {
        UoPModelData m = buildMinimalModel();
        m.setIdlModule("CUSTOM.SomeModel");  // no .DM. token

        IdlModelData out = ADAPTER.adapt(m);
        assertEquals("CUSTOM.SomeModel.TSS", out.getTssIdlModule());
    }

    // -----------------------------------------------------------------------
    // Round-trip through model.face
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("round-trip: model.face → FaceTssReader → TssToEntityModelAdapter")
    void roundTrip() throws Exception {
        var modelFace = Paths.get("examples/model.face");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(modelFace),
                "Skipping round-trip — examples/model.face not found");

        UoPModelData uoPModel = new FaceTssReader().read(modelFace);
        IdlModelData adapted  = ADAPTER.adapt(uoPModel);

        assertEquals("SampleUSM",         adapted.getModelName());
        assertEquals("FACE.DM.SampleUSM", adapted.getIdlModule());
        assertEquals("FACE.TSS.SampleUSM",adapted.getTssIdlModule());
        assertEquals("FACE::DM::SampleUSM", adapted.getCppNamespace());
        assertEquals("{name}",             adapted.getStructNamePattern());

        // 3 platform types → 3 EntityData entries
        assertEquals(3, adapted.getEntities().size());

        List<String> entityNames = adapted.getEntities().stream()
                .map(EntityData::getSimpleName)
                .collect(Collectors.toList());
        assertTrue(entityNames.containsAll(List.of("T1", "T2", "PV1")),
                "Entities must include T1, T2, PV1: " + entityNames);
    }
}
