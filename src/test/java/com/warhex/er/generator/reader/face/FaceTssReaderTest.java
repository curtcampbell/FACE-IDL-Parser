package com.warhex.er.generator.reader.face;

import com.warhex.er.generator.reader.dto.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FaceTssReader} using {@code examples/model.face}.
 *
 * <p>The sample model contains:
 * <ul>
 *   <li>Two Templates: T1, T2</li>
 *   <li>One CompositeTemplate: PV1 (struct, not union; fields mT1→T1, mT2→T2)</li>
 *   <li>Two UoPs: UoP1 (PortableComponent), UoP2 (PlatformSpecificComponent)</li>
 *   <li>Each UoP has 3 connections and 2 LCM ports (ports are intentionally skipped)</li>
 * </ul>
 */
@DisplayName("FaceTssReader — model.face")
class FaceTssReaderTest {

    private static final Path MODEL_FACE = Paths.get("examples/model.face");

    private static UoPModelData model;

    @BeforeAll
    static void parse() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(MODEL_FACE),
                "Skipping — examples/model.face not found");
        model = new FaceTssReader().read(MODEL_FACE);
    }

    // -----------------------------------------------------------------------
    // Model-level assertions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("model name is read from root element")
    void modelName() {
        assertEquals("SampleUSM", model.getModelName());
    }

    @Test
    @DisplayName("IDL module is derived from model name")
    void idlModule() {
        assertEquals("FACE.DM.SampleUSM", model.getIdlModule());
    }

    // -----------------------------------------------------------------------
    // Platform types (Templates + CompositeTemplates)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("three platform types are registered: T1, T2, PV1")
    void platformTypeCount() {
        assertEquals(3, model.getPlatformTypes().size());
    }

    @Test
    @DisplayName("platform type names are T1, T2, PV1")
    void platformTypeNames() {
        List<String> names = model.getPlatformTypes().stream()
                .map(TssTypeData::getName)
                .collect(Collectors.toList());
        assertTrue(names.contains("T1"), "Expected T1: " + names);
        assertTrue(names.contains("T2"), "Expected T2: " + names);
        assertTrue(names.contains("PV1"), "Expected PV1: " + names);
    }

    @Test
    @DisplayName("T1 and T2 are struct (not union)")
    void templatesAreStructs() {
        Map<String, TssTypeData> byName = byName(model.getPlatformTypes());
        assertFalse(byName.get("T1").isUnion(),  "T1 should not be a union");
        assertFalse(byName.get("T2").isUnion(),  "T2 should not be a union");
    }

    @Test
    @DisplayName("PV1 is a CompositeTemplate struct (isUnion=false)")
    void compositeTemplateIsStruct() {
        TssTypeData pv1 = byName(model.getPlatformTypes()).get("PV1");
        assertNotNull(pv1, "PV1 should exist");
        assertFalse(pv1.isUnion(), "PV1 should not be a union (isUnion not set in XMI)");
    }

    @Test
    @DisplayName("PV1 has two TemplateComposition fields: mT1 and mT2")
    void compositeTemplateFields() {
        TssTypeData pv1 = byName(model.getPlatformTypes()).get("PV1");
        assertNotNull(pv1);
        List<FieldData> fields = pv1.getFields();
        assertEquals(2, fields.size(), "PV1 should have exactly 2 fields");

        Map<String, FieldData> byFieldName = fields.stream()
                .collect(Collectors.toMap(FieldData::getName, f -> f));

        assertTrue(byFieldName.containsKey("mT1"), "Expected field mT1");
        assertTrue(byFieldName.containsKey("mT2"), "Expected field mT2");
        assertEquals("T1", byFieldName.get("mT1").getIdlType());
        assertEquals("T2", byFieldName.get("mT2").getIdlType());
    }

    @Test
    @DisplayName("T1 fields are resolved from boundQuery")
    void templateT1Fields() {
        TssTypeData t1 = byName(model.getPlatformTypes()).get("T1");
        assertNotNull(t1);
        assertNotNull(t1.getFields(), "T1 fields list should not be null");
        assertFalse(t1.getFields().isEmpty(), "T1 should have at least one resolved field");
    }

    // -----------------------------------------------------------------------
    // UoP collection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two UoPs are discovered: UoP1 and UoP2")
    void uopCount() {
        assertEquals(2, model.getUoPs().size());
    }

    @Test
    @DisplayName("UoP names are UoP1 and UoP2")
    void uopNames() {
        List<String> names = model.getUoPs().stream()
                .map(UoPData::getName)
                .collect(Collectors.toList());
        assertTrue(names.contains("UoP1"), "Expected UoP1: " + names);
        assertTrue(names.contains("UoP2"), "Expected UoP2: " + names);
    }

    // -----------------------------------------------------------------------
    // Connection parsing — UoP1
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UoP1 has exactly 3 connections (LCM ports skipped)")
    void uoP1ConnectionCount() {
        UoPData uoP1 = uopByName("UoP1");
        assertEquals(3, uoP1.getConnections().size(),
                "LCM ports must be skipped; expected exactly 3 connections");
    }

    @Test
    @DisplayName("UoP1MP1 is QUEUING / CONSUMER / STANDARD variant")
    void uoP1Mp1() {
        ConnectionData conn = connByName(uopByName("UoP1"), "UoP1MP1");
        assertEquals(ConnectionKind.QUEUING,          conn.getKind());
        assertEquals(ConnectionRole.CONSUMER,          conn.getRole());
        assertEquals(TypedTsVariant.STANDARD,          conn.getTypedTsVariant());
        assertEquals("PV1",                            conn.getMessageType().getName());
        assertNull(conn.getResponseMessageType(),      "pub/sub response type must be null");
    }

    @Test
    @DisplayName("UoP1MP2 is SINGLE_INSTANCE / PRODUCER / STANDARD variant")
    void uoP1Mp2() {
        ConnectionData conn = connByName(uopByName("UoP1"), "UoP1MP2");
        assertEquals(ConnectionKind.SINGLE_INSTANCE,  conn.getKind());
        assertEquals(ConnectionRole.PRODUCER,          conn.getRole());
        assertEquals(TypedTsVariant.STANDARD,          conn.getTypedTsVariant());
        assertEquals("T1",                             conn.getMessageType().getName());
        assertNull(conn.getResponseMessageType());
    }

    @Test
    @DisplayName("UoP1CS1 is CLIENT_SERVER / REQUESTER / EXTENDED variant with request=PV1, response=T2")
    void uoP1Cs1() {
        ConnectionData conn = connByName(uopByName("UoP1"), "UoP1CS1");
        assertEquals(ConnectionKind.CLIENT_SERVER,     conn.getKind());
        assertEquals(ConnectionRole.REQUESTER,         conn.getRole());
        assertEquals(TypedTsVariant.EXTENDED,          conn.getTypedTsVariant());
        assertEquals("PV1",                            conn.getMessageType().getName());
        assertNotNull(conn.getResponseMessageType(),   "CLIENT_SERVER must have a response type");
        assertEquals("T2",                             conn.getResponseMessageType().getName());
    }

    // -----------------------------------------------------------------------
    // Connection parsing — UoP2
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UoP2 has exactly 3 connections (LCM ports skipped)")
    void uoP2ConnectionCount() {
        UoPData uoP2 = uopByName("UoP2");
        assertEquals(3, uoP2.getConnections().size(),
                "LCM ports must be skipped; expected exactly 3 connections");
    }

    @Test
    @DisplayName("UoP2MP1 is QUEUING / CONSUMER / STANDARD with messageType PV1")
    void uoP2Mp1() {
        ConnectionData conn = connByName(uopByName("UoP2"), "UoP2MP1");
        assertEquals(ConnectionKind.QUEUING,           conn.getKind());
        assertEquals(ConnectionRole.CONSUMER,           conn.getRole());
        assertEquals(TypedTsVariant.STANDARD,           conn.getTypedTsVariant());
        assertEquals("PV1",                             conn.getMessageType().getName());
    }

    @Test
    @DisplayName("UoP2MP2 is SINGLE_INSTANCE / PRODUCER / STANDARD with messageType T2")
    void uoP2Mp2() {
        ConnectionData conn = connByName(uopByName("UoP2"), "UoP2MP2");
        assertEquals(ConnectionKind.SINGLE_INSTANCE,   conn.getKind());
        assertEquals(ConnectionRole.PRODUCER,           conn.getRole());
        assertEquals(TypedTsVariant.STANDARD,           conn.getTypedTsVariant());
        assertEquals("T2",                              conn.getMessageType().getName());
    }

    @Test
    @DisplayName("UoP2CS1 is CLIENT_SERVER / REQUESTER / EXTENDED with request=PV1, response=T1")
    void uoP2Cs1() {
        ConnectionData conn = connByName(uopByName("UoP2"), "UoP2CS1");
        assertEquals(ConnectionKind.CLIENT_SERVER,      conn.getKind());
        assertEquals(ConnectionRole.REQUESTER,          conn.getRole());
        assertEquals(TypedTsVariant.EXTENDED,           conn.getTypedTsVariant());
        assertEquals("PV1",                             conn.getMessageType().getName());
        assertNotNull(conn.getResponseMessageType());
        assertEquals("T1",                              conn.getResponseMessageType().getName());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<String, TssTypeData> byName(List<TssTypeData> types) {
        return types.stream().collect(Collectors.toMap(TssTypeData::getName, t -> t));
    }

    private static UoPData uopByName(String name) {
        return model.getUoPs().stream()
                .filter(u -> name.equals(u.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UoP not found: " + name));
    }

    private static ConnectionData connByName(UoPData uoP, String name) {
        return uoP.getConnections().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Connection not found: " + name + " in UoP " + uoP.getName()));
    }
}
