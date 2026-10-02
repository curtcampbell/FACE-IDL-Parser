package com.warhex.er.generator.integration;

import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.binding.LanguageBindingPipeline;
import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.binding.TemplateInstantiator;
import com.warhex.er.generator.binding.cpp.CppTypeHelper;
import com.warhex.er.generator.binding.generic.LanguageDescriptorLoader;
import com.warhex.er.generator.parser.IdlAstBuilder;
import com.warhex.er.generator.parser.IdlDirectoryParser;
import com.warhex.er.generator.parser.IdlParser;
import com.warhex.er.generator.parser.IdlParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for template module instantiation.
 *
 * <p>Uses {@code examples/SampleModelTypedTS.idl} — a self-contained IDL that
 * declares a minimal {@code Typed<DATATYPE_TYPE>} template inline and instantiates
 * it as {@code TrackDataTypedTS} within the {@code FACE::TSS::SampleModel} module.
 *
 * <h2>What this tests</h2>
 * <ul>
 *   <li>Template registry building from the parsed {@link IdlSpecification}</li>
 *   <li>Typedef chain resolution ({@code TrackData_t} → {@code ::FACE::DM::SampleModel::TrackData})</li>
 *   <li>Type substitution throughout the template body</li>
 *   <li>{@link CppTypeHelper#paramDeclFull} — {@code inout} local interface → {@code I**};
 *       {@code inout} primitive → standard {@code T&}</li>
 *   <li>C++ header file rendered by {@code template_inst.hpp.vm}</li>
 *   <li>Python module file rendered by {@code template_inst.py.vm}</li>
 * </ul>
 *
 * <p><strong>Prerequisites:</strong> Run {@code mvn generate-sources} first to
 * produce the ANTLR4 parser classes.
 */
class TemplateInstantiationIntegrationTest {

    private static final Path SAMPLE_IDL =
            Paths.get("examples/SampleModelTypedTS.idl");

    // -----------------------------------------------------------------------
    // TemplateInstantiator unit-level tests (no file I/O)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TemplateInstantiator: registry finds Typed<1> template")
    void testRegistryFindsTemplate() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        TemplateInstantiator inst = new TemplateInstantiator(spec);

        // Find the TemplateInstNode in the spec
        TemplateInstNode instNode = findFirstTemplateInst(spec);
        assertNotNull(instNode, "Should have at least one TemplateInstNode in spec");
        assertEquals("TrackDataTypedTS", instNode.alias());
        assertEquals(1, instNode.actualParameters().size(),
                "Typed<> has 1 actual parameter");

        Optional<TemplateInstantiator.InstantiationResult> result = inst.instantiate(instNode);
        assertTrue(result.isPresent(), "Instantiation should succeed");

        TemplateInstantiator.InstantiationResult r = result.get();
        assertFalse(r.definitions.isEmpty(), "Instantiated body should have definitions");

        // Should contain Read_Callback and TypedTS interfaces
        boolean hasReadCallback = r.definitions.stream()
                .anyMatch(d -> d instanceof InterfaceNode && d.name().equals("Read_Callback"));
        boolean hasTypedTS = r.definitions.stream()
                .anyMatch(d -> d instanceof InterfaceNode && d.name().equals("TypedTS"));
        assertTrue(hasReadCallback, "Should have Read_Callback interface");
        assertTrue(hasTypedTS, "Should have TypedTS interface");

        // Local interfaces should include both names
        assertTrue(r.localInterfaceNames.contains("Read_Callback"),
                "localInterfaceNames should contain Read_Callback");
        assertTrue(r.localInterfaceNames.contains("TypedTS"),
                "localInterfaceNames should contain TypedTS");
    }

    @Test
    @DisplayName("TemplateInstantiator: typedef resolved through chain")
    void testTypedefResolution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        TemplateInstantiator instantiator = new TemplateInstantiator(spec);
        TemplateInstNode instNode = findFirstTemplateInst(spec);
        assertNotNull(instNode);

        TemplateInstantiator.InstantiationResult result =
                instantiator.instantiate(instNode).orElseThrow();

        // The resolved actual should be ::FACE::DM::SampleModel::TrackData (resolved from TrackData_t)
        assertFalse(result.resolvedActuals.isEmpty());
        IdlType resolved = result.resolvedActuals.get(0);
        assertTrue(resolved instanceof IdlType.Scoped,
                "Resolved actual should be a Scoped type, not the raw typedef alias");
        String qn = ((IdlType.Scoped) resolved).qualifiedName();
        // Must not be the alias name
        assertFalse(qn.equals("TrackData_t"),
                "Typedef alias must be resolved: got " + qn);
        assertTrue(qn.contains("TrackData"),
                "Resolved name should contain TrackData: " + qn);
    }

    @Test
    @DisplayName("TemplateInstantiator: DATATYPE_TYPE substituted in interface operations")
    void testTypeSubstitution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        TemplateInstantiator instantiator = new TemplateInstantiator(spec);
        TemplateInstNode instNode = findFirstTemplateInst(spec);

        TemplateInstantiator.InstantiationResult result =
                instantiator.instantiate(instNode).orElseThrow();

        // Find TypedTS and check that DATATYPE_TYPE was replaced with a concrete type
        InterfaceNode typedTS = (InterfaceNode) result.definitions.stream()
                .filter(d -> d instanceof InterfaceNode && d.name().equals("TypedTS"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TypedTS not found"));

        boolean hasConcreteMessageParam = typedTS.operations().stream()
                .flatMap(op -> op.parameters().stream())
                .filter(p -> p.name().equals("message"))
                .anyMatch(p -> {
                    if (!(p.type() instanceof IdlType.Scoped s)) return false;
                    // Must not be DATATYPE_TYPE anymore
                    return !s.qualifiedName().equals("DATATYPE_TYPE")
                            && s.qualifiedName().contains("TrackData");
                });
        assertTrue(hasConcreteMessageParam,
                "DATATYPE_TYPE should have been substituted with concrete TrackData type in 'message' param");
    }

    @Test
    @DisplayName("CppTypeHelper.paramDeclFull: inout local interface → I**")
    void testParamDeclFullLocalInterface() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        TemplateInstantiator instantiator = new TemplateInstantiator(spec);
        TemplateInstNode instNode = findFirstTemplateInst(spec);
        TemplateInstantiator.InstantiationResult result =
                instantiator.instantiate(instNode).orElseThrow();

        CppTypeHelper cpp = new CppTypeHelper();

        // Find 'inout Read_Callback callback' parameter in TypedTS.Receive_Message
        InterfaceNode typedTS = (InterfaceNode) result.definitions.stream()
                .filter(d -> d instanceof InterfaceNode && d.name().equals("TypedTS"))
                .findFirst().orElseThrow();

        OperationNode receiveMsg = typedTS.operations().stream()
                .filter(op -> op.name().equals("Receive_Message"))
                .findFirst().orElseThrow();

        ParameterNode callbackParam = receiveMsg.parameters().stream()
                .filter(p -> p.name().equals("callback"))
                .findFirst().orElseThrow();

        assertEquals(ParamDirection.INOUT, callbackParam.direction());

        String decl = cpp.paramDeclFull(callbackParam,
                result.localInterfaceNames, result.interfaceKindActuals);
        assertTrue(decl.contains("Read_Callback**"),
                "inout local interface should produce I**: got '" + decl + "'");
        assertTrue(decl.endsWith("callback"),
                "Declaration should end with parameter name: got '" + decl + "'");
    }

    // -----------------------------------------------------------------------
    // File generation tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("C++ mapper generates template instantiation header")
    void testCppTemplateInstOutput(@TempDir Path tempDir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        LanguageBindingPipeline pipeline = new LanguageBindingPipeline(buildMappers());
        pipeline.generate(new IdlParseResult(spec, List.of()), tempDir);

        // Expect: cpp/FACE/TSS/SampleModel/TrackDataTypedTS.hpp
        Path hpp = tempDir.resolve("cpp/FACE/TSS/SampleModel/TrackDataTypedTS.hpp");
        assertTrue(Files.exists(hpp), "Template inst header should exist: " + hpp);

        String content = Files.readString(hpp);
        assertTrue(content.contains("#pragma once"),
                "Header should have #pragma once");
        assertTrue(content.contains("namespace TrackDataTypedTS"),
                "Header should open alias namespace");
        assertTrue(content.contains("class Read_Callback"),
                "Header should declare Read_Callback");
        assertTrue(content.contains("class TypedTS"),
                "Header should declare TypedTS");
        assertTrue(content.contains("Read_Callback**"),
                "inout Read_Callback should produce I** in header");
        // DATATYPE_TYPE must be gone — replaced by concrete TrackData type
        assertFalse(content.contains("DATATYPE_TYPE"),
                "DATATYPE_TYPE placeholder must not appear in generated header");
        assertTrue(content.contains("} // namespace TrackDataTypedTS"),
                "Header should close alias namespace");
    }

    @Test
    @DisplayName("C++ Injectable of a generated TypedTS includes the TypedTS header")
    void testCppInjectableIncludesGeneratedTypedTs(@TempDir Path tempDir) throws Exception {
        // The Injectable's actual (FACE::TSS::SampleModel::Track::TypedTS)
        // matches the "FACE::TSS" skip prefix but is generated here, so it
        // needs an #include; FACE::TSS::Base is framework, so it doesn't.
        Path include = generateTrackModelCpp(tempDir);

        assertTrue(Files.exists(include.resolve("FACE/TSS/SampleModel/Track/TypedTS.hpp")),
                "TypedTS header should be generated");
        String injectable = Files.readString(include.resolve(
                "FACE/TSS/SampleModel/Track_TypedTS_Injectable/TypedTS_Injectable.hpp"));
        assertTrue(injectable.contains("#include <FACE/TSS/SampleModel/Track/TypedTS.hpp>"),
                "Injectable of a generated TypedTS must include its header:\n" + injectable);
        String baseInjectable = Files.readString(include.resolve(
                "FACE/TSS/SampleModel/Base_Injectable/Base_Injectable.hpp"));
        assertFalse(baseInjectable.contains("#include <FACE/TSS/Base.hpp>"),
                "Framework actual FACE::TSS::Base must stay skipped:\n" + baseInjectable);
    }

    @Test
    @DisplayName("C++ framework enum parameters use ::Value (RETURN_CODE_TYPE::Value&)")
    void testCppFrameworkEnumParamsUseValue(@TempDir Path tempDir) throws Exception {
        // FACE TS 3.2 s4.14.8.8.2 maps every IDL enum to a struct wrapping
        // "enum Value", framework enums (FACE::RETURN_CODE_TYPE, declared in
        // face-idl/FACE/Common.idl, not in the rendered IDL) included.
        Path include = generateTrackModelCpp(tempDir);

        for (String rel : List.of(
                "FACE/TSS/SampleModel/Track/TypedTS.hpp",
                "FACE/TSS/SampleModel/Track_TypedTS_Injectable/TypedTS_Injectable.hpp")) {
            String hpp = Files.readString(include.resolve(rel));
            assertTrue(hpp.contains("RETURN_CODE_TYPE::Value& return_code"),
                    rel + " should declare RETURN_CODE_TYPE::Value& return_code:\n" + hpp);
            assertFalse(hpp.contains("RETURN_CODE_TYPE& "),
                    rel + " should have no bare RETURN_CODE_TYPE&:\n" + hpp);
        }
    }

    @Test
    @DisplayName("C++ inout interface-kind template actual maps to T** (Injectable::Set_Reference)")
    void testCppInjectableInterfaceReferenceIsDoublePointer(@TempDir Path tempDir) throws Exception {
        // OMG IDL-to-C++ / FACE TS 3.2 s4.14.8.10.2: an inout interface
        // parameter is T**, whether the interface is local to the template
        // body (Read_Callback) or bound to an interface-kind formal
        // (Injectable<INTERFACE_TYPE>) -- as in the platform face-core's own
        // Base_Injectable ("FACE::TSS::Base** interface_reference").
        Path include = generateTrackModelCpp(tempDir);

        String injectable = Files.readString(include.resolve(
                "FACE/TSS/SampleModel/Track_TypedTS_Injectable/TypedTS_Injectable.hpp"));
        assertTrue(injectable.contains(
                        "::FACE::TSS::SampleModel::Track::TypedTS** interface_reference"),
                "generated TypedTS Injectable should take TypedTS**:\n" + injectable);
        String baseInjectable = Files.readString(include.resolve(
                "FACE/TSS/SampleModel/Base_Injectable/Base_Injectable.hpp"));
        assertTrue(baseInjectable.contains("Base** interface_reference"),
                "framework Base Injectable should take Base**:\n" + baseInjectable);
        assertFalse(injectable.contains("*&") || baseInjectable.contains("*&"),
                "no T*& parameters:\n" + injectable + baseInjectable);
    }

    /**
     * Writes the layout generate-tss-idl produces for one message -- a DM
     * struct, its TypedTS instantiation, an Injectable of that TypedTS, one
     * file each -- plus an Injectable of the framework's FACE::TSS::Base,
     * binds it to C++ with the framework IDL from face-idl/, and returns the
     * generated include root.
     */
    private Path generateTrackModelCpp(Path tempDir) throws Exception {
        Path faceIdl = Paths.get("face-idl");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(faceIdl),
                "Skipping — framework IDL not available: " + faceIdl);

        Path idlRoot = tempDir.resolve("idl");
        writeIdl(idlRoot.resolve("FACE/DM/SampleModel/TrackData.idl"), """
                module FACE { module DM { module SampleModel {
                  struct TrackData { long track_id; };
                }; }; };
                """);
        writeIdl(idlRoot.resolve("FACE/TSS/SampleModel/Track/TypedTS.idl"), """
                #include <FACE/TSS/TypedTS.idl>
                #include <FACE/DM/SampleModel/TrackData.idl>
                module FACE { module TSS { module SampleModel {
                  typedef ::FACE::DM::SampleModel::TrackData TrackData_t;
                  module ::FACE::TSS::Typed<TrackData_t> Track;
                }; }; };
                """);
        writeIdl(idlRoot.resolve("FACE/TSS/SampleModel/Track/TypedTS_Injectable.idl"), """
                #include <FACE/Injectable.idl>
                #include <FACE/TSS/SampleModel/Track/TypedTS.idl>
                module FACE { module TSS { module SampleModel {
                  module ::FACE::Injectable<::FACE::TSS::SampleModel::Track::TypedTS> Track_TypedTS_Injectable;
                }; }; };
                """);
        writeIdl(idlRoot.resolve("FACE/TSS/SampleModel/Base_Injectable.idl"), """
                #include <FACE/Injectable.idl>
                #include <FACE/TSS/Base.idl>
                module FACE { module TSS { module SampleModel {
                  module ::FACE::Injectable<::FACE::TSS::Base> Base_Injectable;
                }; }; };
                """);

        IdlParseResult result = new IdlDirectoryParser(List.of(faceIdl, idlRoot)).parse(idlRoot);
        List<LanguageMapper> cppOnly = buildMappers().stream()
                .filter(m -> m.languageName().equals("C++"))
                .toList();
        new LanguageBindingPipeline(cppOnly).generate(result, tempDir.resolve("out"));
        return tempDir.resolve("out/cpp/include");
    }

    @Test
    @DisplayName("Python mapper generates template instantiation module")
    void testPythonTemplateInstOutput(@TempDir Path tempDir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SAMPLE_IDL),
                "Skipping — IDL parser not available: " + SAMPLE_IDL);

        IdlSpecification spec = parse(SAMPLE_IDL);
        LanguageBindingPipeline pipeline = new LanguageBindingPipeline(buildMappers());
        pipeline.generate(new IdlParseResult(spec, List.of()), tempDir);

        // Expect: python/FACE/TSS/SampleModel/TrackDataTypedTS.py
        Path py = tempDir.resolve("python/FACE/TSS/SampleModel/TrackDataTypedTS.py");
        assertTrue(Files.exists(py), "Template inst Python file should exist: " + py);

        String content = Files.readString(py);
        assertTrue(content.contains("class TrackDataTypedTS"),
                "Python file should declare TrackDataTypedTS namespace class");
        assertTrue(content.contains("class Read_Callback"),
                "Python file should declare Read_Callback");
        assertTrue(content.contains("class TypedTS"),
                "Python file should declare TypedTS");
        assertTrue(content.contains("abstractmethod"),
                "Python file should use @abstractmethod");
        // DATATYPE_TYPE must be gone
        assertFalse(content.contains("DATATYPE_TYPE"),
                "DATATYPE_TYPE placeholder must not appear in generated Python");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void writeIdl(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private IdlSpecification parse(Path idlFile) throws Exception {
        IdlParser parser = new IdlParser();
        IdlAstBuilder builder = new IdlAstBuilder();
        return builder.visitSpecification(parser.parse(idlFile));
    }

    /**
     * Walks the spec recursively and returns the first {@link TemplateInstNode} found.
     */
    private TemplateInstNode findFirstTemplateInst(IdlSpecification spec) {
        return findInDefs(spec.definitions());
    }

    private TemplateInstNode findInDefs(List<IdlDefinition> defs) {
        for (IdlDefinition def : defs) {
            if (def instanceof TemplateInstNode t) return t;
            if (def instanceof ModuleNode m) {
                TemplateInstNode found = findInDefs(m.definitions());
                if (found != null) return found;
            }
        }
        return null;
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
}
