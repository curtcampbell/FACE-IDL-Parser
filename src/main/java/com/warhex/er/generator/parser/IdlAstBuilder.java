package com.warhex.er.generator.parser;

import com.warhex.er.generator.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * ANTLR4 visitor that walks the {@code FACE_IDL} parse tree and produces an
 * {@link IdlSpecification} (the IdlAst root).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FACE_IDLParser.SpecificationContext tree = idlParser.parse(rootIdl);
 * IdlSpecification spec = new IdlAstBuilder().visitSpecification(tree);
 * }</pre>
 *
 * <h2>Coverage</h2>
 * All FACE-relevant IDL constructs are translated:
 * <ul>
 *   <li>Modules (regular and template)</li>
 *   <li>Template module instantiations</li>
 *   <li>Structs, enums, unions</li>
 *   <li>Interfaces with operations and parameters</li>
 *   <li>Consts and typedefs</li>
 *   <li>All IDL type expressions including sequence, string, array, scoped names</li>
 * </ul>
 *
 * Unsupported constructs (exceptions, bitsets, native, forward declarations)
 * are silently skipped with a FINE log message — they are not used in FACE
 * entity data models.
 *
 * <h2>Grammar naming conventions</h2>
 * ANTLR4 Java target capitalises only the first letter of a rule name to form
 * the context class name.  Underscores are preserved:
 * {@code template_module_dcl} → {@code Template_module_dclContext}.
 */
public class IdlAstBuilder extends FACE_IDLBaseVisitor<IdlNode> {

    private static final Logger LOG = Logger.getLogger(IdlAstBuilder.class.getName());

    // ==========================================================================
    // Specification — entry point
    // ==========================================================================

    /**
     * Visits the root {@code specification} context and returns the fully-built
     * {@link IdlSpecification}.  This is the only method callers need to invoke
     * directly; all other {@code visit*} methods are dispatched internally.
     */
    @Override
    public IdlSpecification visitSpecification(FACE_IDLParser.SpecificationContext ctx) {
        List<IdlDefinition> defs = collectDefinitions(ctx.definition());
        return new IdlSpecification(defs);
    }

    // ==========================================================================
    // Definitions
    // ==========================================================================

    /**
     * Dispatches a single {@code definition} context to the appropriate
     * visitor method.  Returns {@code null} for unsupported constructs.
     */
    @Override
    public IdlDefinition visitDefinition(FACE_IDLParser.DefinitionContext ctx) {
        if (ctx.module()                    != null) return visitModule(ctx.module());
        if (ctx.const_dcl()                 != null) return visitConst_dcl(ctx.const_dcl());
        if (ctx.type_dcl()                  != null) return visitType_dcl(ctx.type_dcl());
        if (ctx.interface_or_forward_dcl()  != null) return visitInterface_or_forward_dcl(ctx.interface_or_forward_dcl());
        if (ctx.template_module_dcl()       != null) return visitTemplate_module_dcl(ctx.template_module_dcl());
        if (ctx.template_module_inst()      != null) return visitTemplate_module_inst(ctx.template_module_inst());
        // except_dcl — not used in FACE entity models
        LOG.fine(() -> "Skipping unsupported definition: " + ctx.getText());
        return null;
    }

    private List<IdlDefinition> collectDefinitions(
            List<FACE_IDLParser.DefinitionContext> ctxList) {
        List<IdlDefinition> result = new ArrayList<>();
        for (FACE_IDLParser.DefinitionContext c : ctxList) {
            IdlDefinition d = visitDefinition(c);
            if (d != null) result.add(d);
        }
        return result;
    }

    // ==========================================================================
    // Module
    // ==========================================================================

    @Override
    public ModuleNode visitModule(FACE_IDLParser.ModuleContext ctx) {
        String name = ctx.identifier().getText();
        List<IdlDefinition> defs = collectDefinitions(ctx.definition());
        return new ModuleNode(name, defs);
    }

    // ==========================================================================
    // Template modules (FACE / IDL 4.x extension)
    // ==========================================================================

    @Override
    public TemplateModuleNode visitTemplate_module_dcl(
            FACE_IDLParser.Template_module_dclContext ctx) {

        String name = ctx.identifier().getText();

        List<FormalParameter> params = new ArrayList<>();
        for (FACE_IDLParser.Formal_parameterContext fp :
                ctx.formal_parameters().formal_parameter()) {
            String kind  = fp.formal_parameter_type().getText();
            String pname = fp.identifier().getText();
            params.add(new FormalParameter(kind, pname));
        }

        List<IdlDefinition> defs = new ArrayList<>();
        for (FACE_IDLParser.Tpl_definitionContext tpl : ctx.tpl_definition()) {
            if (tpl.definition() != null) {
                IdlDefinition d = visitDefinition(tpl.definition());
                if (d != null) defs.add(d);
            }
            // template_module_ref (alias inside template body) — skip
        }

        return new TemplateModuleNode(name, params, defs);
    }

    @Override
    public TemplateInstNode visitTemplate_module_inst(
            FACE_IDLParser.Template_module_instContext ctx) {

        String templateName = buildScopedName(ctx.scoped_name());
        String alias        = ctx.identifier().getText();

        List<String> actuals = new ArrayList<>();
        for (FACE_IDLParser.Actual_parameterContext ap :
                ctx.actual_parameters().actual_parameter()) {
            if (ap.type_spec() != null) {
                actuals.add(buildTypeSpec(ap.type_spec()).toString());
            } else if (ap.const_expr() != null) {
                actuals.add(ap.const_expr().getText());
            }
        }

        return new TemplateInstNode(alias, templateName, actuals);
    }

    // ==========================================================================
    // Type declaration
    // ==========================================================================

    @Override
    public IdlDefinition visitType_dcl(FACE_IDLParser.Type_dclContext ctx) {
        if (ctx.KW_TYPEDEF() != null) {
            FACE_IDLParser.Type_dclaratorContext td = ctx.type_dclarator();
            IdlType baseType = buildTypeSpec(td.type_spec());
            // IDL allows multiple declarators in one typedef; emit one TypedefNode
            // for each.  For multi-declarator typedefs return only the first here —
            // FACE entity IDL virtually never lists more than one.
            FACE_IDLParser.DeclaratorContext first = td.declarators().declarator(0);
            String name = (first.simple_dclarator() != null)
                    ? first.simple_dclarator().getText()
                    : first.complex_dclarator().array_dclarator().ID().getText();
            return new TypedefNode(name, baseType);
        }
        if (ctx.struct_type()  != null) return visitStruct_type(ctx.struct_type());
        if (ctx.union_type()   != null) return visitUnion_type(ctx.union_type());
        if (ctx.enum_type()    != null) return visitEnum_type(ctx.enum_type());
        // bitset, bitmask, native, constr_forward_dcl — not used in FACE
        LOG.fine(() -> "Skipping unsupported type_dcl: " + ctx.getText());
        return null;
    }

    // ==========================================================================
    // Struct
    // ==========================================================================

    @Override
    public StructNode visitStruct_type(FACE_IDLParser.Struct_typeContext ctx) {
        String name = ctx.identifier().getText();

        // IDL 4 struct inheritance: struct Derived : Base { … }
        Optional<String> base = (ctx.scoped_name() != null)
                ? Optional.of(buildScopedName(ctx.scoped_name()))
                : Optional.empty();

        List<FieldNode> members = new ArrayList<>();
        for (FACE_IDLParser.MemberContext m : ctx.member_list().member()) {
            members.addAll(buildMembers(m));
        }
        return new StructNode(name, base, members);
    }

    /**
     * Expands one IDL member declaration into one or more {@link FieldNode}s.
     * IDL allows {@code long x, y;} (multiple declarators per type); each
     * declarator becomes a separate field.
     */
    private List<FieldNode> buildMembers(FACE_IDLParser.MemberContext ctx) {
        IdlType type = buildTypeSpec(ctx.type_spec());
        List<FieldNode> fields = new ArrayList<>();

        for (FACE_IDLParser.DeclaratorContext decl : ctx.declarators().declarator()) {
            String   fname = null;
            IdlType  ftype = type;

            if (decl.simple_dclarator() != null) {
                fname = decl.simple_dclarator().getText();
            } else {
                // Array declarator: long data[10][4]
                FACE_IDLParser.Array_dclaratorContext arr =
                        decl.complex_dclarator().array_dclarator();
                fname = arr.ID().getText();
                List<Integer> dims = new ArrayList<>();
                for (FACE_IDLParser.Fixed_array_sizeContext dim : arr.fixed_array_size()) {
                    dims.add(parsePositiveInt(dim.positive_int_const()));
                }
                ftype = new IdlType.Array(type, dims);
            }
            fields.add(new FieldNode(ftype, fname));
        }
        return fields;
    }

    // ==========================================================================
    // Enum
    // ==========================================================================

    @Override
    public EnumNode visitEnum_type(FACE_IDLParser.Enum_typeContext ctx) {
        String name = ctx.identifier().getText();
        List<EnumValueNode> values = new ArrayList<>();
        for (FACE_IDLParser.EnumeratorContext ev : ctx.enumerator()) {
            values.add(new EnumValueNode(ev.identifier().getText()));
        }
        return new EnumNode(name, values);
    }

    // ==========================================================================
    // Union
    // ==========================================================================

    @Override
    public UnionNode visitUnion_type(FACE_IDLParser.Union_typeContext ctx) {
        String   name       = ctx.identifier().getText();
        IdlType  switchType = buildSwitchType(ctx.switch_type_spec());

        List<UnionCaseNode> cases = new ArrayList<>();
        for (FACE_IDLParser.Case_stmtContext cs : ctx.switch_body().case_stmt()) {
            cases.add(buildCase(cs));
        }
        return new UnionNode(name, switchType, cases);
    }

    private UnionCaseNode buildCase(FACE_IDLParser.Case_stmtContext ctx) {
        List<String> labels   = new ArrayList<>();
        boolean      isDefault = false;

        for (FACE_IDLParser.Case_labelContext lbl : ctx.case_label()) {
            if (lbl.KW_DEFAULT() != null) {
                isDefault = true;
            } else {
                labels.add(lbl.const_expr().getText());
            }
        }

        FACE_IDLParser.Element_specContext spec = ctx.element_spec();
        IdlType type = buildTypeSpec(spec.type_spec());
        String  memberName;
        if (spec.declarator().simple_dclarator() != null) {
            memberName = spec.declarator().simple_dclarator().getText();
        } else {
            memberName = spec.declarator().complex_dclarator()
                    .array_dclarator().ID().getText();
        }
        return new UnionCaseNode(labels, isDefault, type, memberName);
    }

    private IdlType buildSwitchType(FACE_IDLParser.Switch_type_specContext ctx) {
        if (ctx.enum_type()    != null) return new IdlType.Scoped(ctx.enum_type().identifier().getText());
        if (ctx.scoped_name()  != null) return new IdlType.Scoped(buildScopedName(ctx.scoped_name()));
        if (ctx.integer_type() != null) return new IdlType.Primitive(buildIntegerKind(ctx.integer_type()));
        if (ctx.boolean_type() != null) return new IdlType.Primitive(PrimitiveKind.BOOLEAN);
        if (ctx.char_type()    != null) return new IdlType.Primitive(PrimitiveKind.CHAR);
        if (ctx.wide_char_type() != null) return new IdlType.Primitive(PrimitiveKind.WIDE_CHAR);
        if (ctx.octet_type()   != null) return new IdlType.Primitive(PrimitiveKind.OCTET);
        return new IdlType.Scoped(ctx.getText());   // fallback
    }

    // ==========================================================================
    // Interface
    // ==========================================================================

    @Override
    public IdlDefinition visitInterface_or_forward_dcl(
            FACE_IDLParser.Interface_or_forward_dclContext ctx) {
        if (ctx.interface_dcl() != null) return visitInterface_dcl(ctx.interface_dcl());
        // forward_dcl — declaration only, no code to generate
        return null;
    }

    @Override
    public InterfaceNode visitInterface_dcl(FACE_IDLParser.Interface_dclContext ctx) {
        FACE_IDLParser.Interface_headerContext hdr = ctx.interface_header();

        String  name       = hdr.identifier().getText();
        boolean isAbstract = hdr.KW_ABSTRACT() != null;
        boolean isLocal    = hdr.KW_LOCAL()    != null;

        List<String> inherited = new ArrayList<>();
        if (hdr.interface_inheritance_spec() != null) {
            for (FACE_IDLParser.Interface_nameContext iname :
                    hdr.interface_inheritance_spec().interface_name()) {
                inherited.add(buildScopedName(
                        iname.a_scoped_name().scoped_name()));
            }
        }

        List<OperationNode> ops = new ArrayList<>();
        for (FACE_IDLParser.Export_Context exp : ctx.interface_body().export_()) {
            if (exp.op_dcl() != null) {
                ops.add(visitOp_dcl(exp.op_dcl()));
            }
            // attr_dcl, type_dcl, const_dcl, except_dcl inside interface — skip
        }

        return new InterfaceNode(name, isAbstract, isLocal, inherited, ops);
    }

    // ==========================================================================
    // Operations and parameters
    // ==========================================================================

    @Override
    public OperationNode visitOp_dcl(FACE_IDLParser.Op_dclContext ctx) {
        String  name     = ctx.identifier().getText();
        boolean isOneway = ctx.op_attribute() != null;

        IdlType returnType;
        FACE_IDLParser.Op_type_specContext ots = ctx.op_type_spec();
        if (ots.KW_VOID() != null) {
            returnType = IdlType.Void.INSTANCE;
        } else {
            returnType = buildParamType(ots.param_type_spec());
        }

        List<ParameterNode> params = new ArrayList<>();
        List<FACE_IDLParser.Param_dclContext> paramCtxList =
                ctx.parameter_dcls().param_dcl();
        if (paramCtxList != null) {
            for (FACE_IDLParser.Param_dclContext pd : paramCtxList) {
                params.add(visitParam_dcl(pd));
            }
        }

        return new OperationNode(name, returnType, params, isOneway);
    }

    @Override
    public ParameterNode visitParam_dcl(FACE_IDLParser.Param_dclContext ctx) {
        ParamDirection dir;
        FACE_IDLParser.Param_attributeContext attr = ctx.param_attribute();
        if      (attr.KW_OUT()   != null) dir = ParamDirection.OUT;
        else if (attr.KW_INOUT() != null) dir = ParamDirection.INOUT;
        else                              dir = ParamDirection.IN;

        IdlType type = buildParamType(ctx.param_type_spec());
        String  name = ctx.simple_dclarator().getText();
        return new ParameterNode(dir, type, name);
    }

    // ==========================================================================
    // Const
    // ==========================================================================

    @Override
    public ConstNode visitConst_dcl(FACE_IDLParser.Const_dclContext ctx) {
        IdlType type  = buildConstType(ctx.const_type());
        String  name  = ctx.identifier().getText();
        String  value = ctx.const_expr().getText();
        return new ConstNode(name, type, value);
    }

    // ==========================================================================
    // Type builders — private helpers
    // ==========================================================================

    private IdlType buildTypeSpec(FACE_IDLParser.Type_specContext ctx) {
        if (ctx.simple_type_spec() != null) return buildSimpleType(ctx.simple_type_spec());
        if (ctx.constr_type_spec() != null) return buildConstrType(ctx.constr_type_spec());
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildSimpleType(FACE_IDLParser.Simple_type_specContext ctx) {
        if (ctx.base_type_spec()     != null) return buildBaseType(ctx.base_type_spec());
        if (ctx.template_type_spec() != null) return buildTemplateType(ctx.template_type_spec());
        if (ctx.scoped_name()        != null) return new IdlType.Scoped(buildScopedName(ctx.scoped_name()));
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildBaseType(FACE_IDLParser.Base_type_specContext ctx) {
        if (ctx.floating_pt_type() != null) return new IdlType.Primitive(buildFloatKind(ctx.floating_pt_type()));
        if (ctx.integer_type()     != null) return new IdlType.Primitive(buildIntegerKind(ctx.integer_type()));
        if (ctx.boolean_type()     != null) return new IdlType.Primitive(PrimitiveKind.BOOLEAN);
        if (ctx.char_type()        != null) return new IdlType.Primitive(PrimitiveKind.CHAR);
        if (ctx.wide_char_type()   != null) return new IdlType.Primitive(PrimitiveKind.WIDE_CHAR);
        if (ctx.octet_type()       != null) return new IdlType.Primitive(PrimitiveKind.OCTET);
        if (ctx.any_type()         != null) return new IdlType.Primitive(PrimitiveKind.ANY);
        // object_type, value_base_type → treat as scoped
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildTemplateType(FACE_IDLParser.Template_type_specContext ctx) {
        if (ctx.sequence_type()    != null) return buildSequenceType(ctx.sequence_type());
        if (ctx.string_type()      != null) return buildStringType(ctx.string_type());
        if (ctx.wide_string_type() != null) {
            FACE_IDLParser.Wide_string_typeContext ws = ctx.wide_string_type();
            OptionalInt bound = (ws.positive_int_const() != null)
                    ? OptionalInt.of(parsePositiveInt(ws.positive_int_const()))
                    : OptionalInt.empty();
            return new IdlType.WideStr(bound);
        }
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildConstrType(FACE_IDLParser.Constr_type_specContext ctx) {
        // Inline struct/enum/union in a field — rare in FACE; return a scoped ref
        if (ctx.struct_type() != null) return new IdlType.Scoped(ctx.struct_type().identifier().getText());
        if (ctx.union_type()  != null) return new IdlType.Scoped(ctx.union_type().identifier().getText());
        if (ctx.enum_type()   != null) return new IdlType.Scoped(ctx.enum_type().identifier().getText());
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildParamType(FACE_IDLParser.Param_type_specContext ctx) {
        if (ctx.base_type_spec()   != null) return buildBaseType(ctx.base_type_spec());
        if (ctx.string_type()      != null) return buildStringType(ctx.string_type());
        if (ctx.wide_string_type() != null) return new IdlType.WideStr();
        if (ctx.scoped_name()      != null) return new IdlType.Scoped(buildScopedName(ctx.scoped_name()));
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType buildConstType(FACE_IDLParser.Const_typeContext ctx) {
        if (ctx.integer_type()     != null) return new IdlType.Primitive(buildIntegerKind(ctx.integer_type()));
        if (ctx.boolean_type()     != null) return new IdlType.Primitive(PrimitiveKind.BOOLEAN);
        if (ctx.char_type()        != null) return new IdlType.Primitive(PrimitiveKind.CHAR);
        if (ctx.wide_char_type()   != null) return new IdlType.Primitive(PrimitiveKind.WIDE_CHAR);
        if (ctx.floating_pt_type() != null) return new IdlType.Primitive(buildFloatKind(ctx.floating_pt_type()));
        if (ctx.string_type()      != null) return buildStringType(ctx.string_type());
        if (ctx.scoped_name()      != null) return new IdlType.Scoped(buildScopedName(ctx.scoped_name()));
        if (ctx.octet_type()       != null) return new IdlType.Primitive(PrimitiveKind.OCTET);
        return new IdlType.Scoped(ctx.getText());
    }

    private IdlType.Sequence buildSequenceType(FACE_IDLParser.Sequence_typeContext ctx) {
        IdlType     elem  = buildSimpleType(ctx.simple_type_spec());
        OptionalInt bound = (ctx.positive_int_const() != null)
                ? OptionalInt.of(parsePositiveInt(ctx.positive_int_const()))
                : OptionalInt.empty();
        return new IdlType.Sequence(elem, bound);
    }

    private IdlType.Str buildStringType(FACE_IDLParser.String_typeContext ctx) {
        OptionalInt bound = (ctx.positive_int_const() != null)
                ? OptionalInt.of(parsePositiveInt(ctx.positive_int_const()))
                : OptionalInt.empty();
        return new IdlType.Str(bound);
    }

    // ==========================================================================
    // Primitive kind builders
    // ==========================================================================

    private PrimitiveKind buildFloatKind(FACE_IDLParser.Floating_pt_typeContext ctx) {
        String text = ctx.getText();
        if (text.equals("longdouble")) return PrimitiveKind.LONG_DOUBLE;
        if (text.equals("double"))     return PrimitiveKind.DOUBLE;
        return PrimitiveKind.FLOAT;
    }

    private PrimitiveKind buildIntegerKind(FACE_IDLParser.Integer_typeContext ctx) {
        if (ctx.signed_int() != null) {
            FACE_IDLParser.Signed_intContext s = ctx.signed_int();
            if (s.signed_longlong_int() != null)
                return s.signed_longlong_int().KW_INT64() != null
                        ? PrimitiveKind.INT64 : PrimitiveKind.LONG_LONG;
            if (s.signed_long_int() != null)
                return s.signed_long_int().KW_INT32() != null
                        ? PrimitiveKind.INT32 : PrimitiveKind.LONG;
            if (s.signed_short_int() != null)
                return s.signed_short_int().KW_INT16() != null
                        ? PrimitiveKind.INT16 : PrimitiveKind.SHORT;
            if (s.signed_tiny_int() != null)
                return PrimitiveKind.INT8;
        } else if (ctx.unsigned_int() != null) {
            FACE_IDLParser.Unsigned_intContext u = ctx.unsigned_int();
            if (u.unsigned_longlong_int() != null)
                return u.unsigned_longlong_int().KW_UINT64() != null
                        ? PrimitiveKind.UINT64 : PrimitiveKind.UNSIGNED_LONG_LONG;
            if (u.unsigned_long_int() != null)
                return u.unsigned_long_int().KW_UINT32() != null
                        ? PrimitiveKind.UINT32 : PrimitiveKind.UNSIGNED_LONG;
            if (u.unsigned_short_int() != null)
                return u.unsigned_short_int().KW_UINT16() != null
                        ? PrimitiveKind.UINT16 : PrimitiveKind.UNSIGNED_SHORT;
            if (u.unsigned_tiny_int() != null)
                return PrimitiveKind.UINT8;
        }
        return PrimitiveKind.LONG;   // safe fallback
    }

    // ==========================================================================
    // Utility
    // ==========================================================================

    /** Returns the full text of a {@code scoped_name} context, e.g. {@code ::FACE::GUID_TYPE}. */
    private String buildScopedName(FACE_IDLParser.Scoped_nameContext ctx) {
        return ctx.getText();
    }

    /**
     * Parses a {@code positive_int_const} context to an {@code int}.
     * Constant-expression arithmetic is not evaluated — the text must be a
     * single integer literal.  Complex expressions (referencing named consts)
     * are not expected in FACE entity IDL and default to 0.
     */
    private int parsePositiveInt(FACE_IDLParser.Positive_int_constContext ctx) {
        try {
            return Integer.parseInt(ctx.getText());
        } catch (NumberFormatException e) {
            LOG.warning("Cannot parse constant expression as integer: " + ctx.getText()
                    + " — defaulting to 0");
            return 0;
        }
    }
}
