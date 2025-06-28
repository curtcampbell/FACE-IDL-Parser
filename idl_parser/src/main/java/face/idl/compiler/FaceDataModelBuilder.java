package face.idl.compiler;

import face.idl.model.ModuleObject;
import face.idl.model.StructObject;
import face.idl.model.UnionObject;
import face.idl.model.v4.*;
import face.idl.model.v4.Module;
import mil.army.face.idl.FACE_IDLBaseListener;
import mil.army.face.idl.FACE_IDLParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Stack;
import java.util.Vector;

public class FaceDataModelBuilder extends FACE_IDLBaseListener {

    public FaceDataModelBuilder(CompilerContext compilerContext, Path fileBeingCompiled) {
        this.compilerContext = compilerContext;
        this.globalModule = compilerContext.getGlobalModule();
        this.fileBeingCompiled = fileBeingCompiled.getFileName().toString();

        //Push the global module onto the stack as the first one.
        moduleStack.push(globalModule);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSpecification(FACE_IDLParser.SpecificationContext ctx) {
        super.enterSpecification(ctx);

        //Create the global scope
        var module = new face.idl.model.v4.Module("");
        _scopeStack.push(module);

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSpecification(FACE_IDLParser.SpecificationContext ctx) {
        super.exitSpecification(ctx);

        //For completeness, we pop the global scope.
        _scopeStack.pop();
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterDefinition(FACE_IDLParser.DefinitionContext ctx) {
        super.enterDefinition(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitDefinition(FACE_IDLParser.DefinitionContext ctx) {
        super.exitDefinition(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterModule(FACE_IDLParser.ModuleContext ctx) {
        super.enterModule(ctx);

        var id = ctx.identifier().getText();

        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error enterModule.");
        }

        var currentScope = _scopeStack.peek();

        if (currentScope.containsId(id)){
            //Error here.
            throw new RuntimeException("ID already defined.  I know not enough information.");
        }


        var newModule = new Module(id);
        currentScope.addScopedObject(newModule);
        _scopeStack.push(newModule);

    }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void exitModule(FACE_IDLParser.ModuleContext ctx) {
        super.exitModule(ctx);

        if(_scopeStack.isEmpty() ||
                !(_scopeStack.peek() instanceof Module)) {
            throw new RuntimeException("Unexpected coding error exitModule.");
        }

        // Pop this module as we exit this namespace.
        _scopeStack.pop();
    }

    private Optional<StructObject> currentStruct = Optional.empty();

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void enterStruct_type(FACE_IDLParser.Struct_typeContext ctx) {
        super.enterStruct_type(ctx);

        var id = ctx.identifier().getText();
        var scopedName = ctx.scoped_name();
        if(scopedName != null) {
            throw new RuntimeException("Scoped name not supported.  I know not enough information.");
        }

        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error enterStruct_type.");
        }

        var currentScope = _scopeStack.peek();

        if (currentScope.containsId(id)){
            //Error here.
            throw new RuntimeException("ID already defined.  I know not enough information.");
        }

        var newStruct = new StructType(id);
        currentScope.addScopedObject(newStruct);
        _scopeStack.push(newStruct);

        var ctxIdentifier = ctx.identifier();
        var token = ctxIdentifier.start;
        var structId = ctxIdentifier.getText();
        var currentModule = getCurrentModule();
        currentStruct = currentModule.newStruct(structId, fileBeingCompiled, token.getLine(), token.getCharPositionInLine());
        if(currentStruct.isEmpty()) {
            var metadata = currentModule.getIdMetadata(structId).get();
            throw new RuntimeException(("Error: %s line: %d, col %d.%nIn struct declaration, " +
                    "identifier %s is already defined.%n" +
                    "Previous declaration is here: %s line: %d, %d")
                    .formatted(fileBeingCompiled,
                            ctx.start.getLine(),
                            ctx.start.getCharPositionInLine(),
                            structId,
                            metadata.getFilePath(),
                            metadata.getLineNumber(),
                            metadata.getColumn()));
        }
    }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void exitStruct_type(FACE_IDLParser.Struct_typeContext ctx) {
        super.exitStruct_type(ctx);



        _scopeStack.pop();

        currentStruct  = Optional.empty();
    }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_dcl(FACE_IDLParser.Interface_dclContext ctx) {
        super.enterInterface_dcl(ctx);


     }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_dcl(FACE_IDLParser.Interface_dclContext ctx) {
        super.exitInterface_dcl(ctx);
    }

    private Optional<UnionObject> currentUnion = Optional.empty();
    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void enterUnion_type(FACE_IDLParser.Union_typeContext ctx) {
        super.enterUnion_type(ctx);

        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error enterUnion_type.");
        }

        var newUnion = new UnionType(ctx.identifier().getText());
        _scopeStack.push(newUnion);


        var ctxIdentifier = ctx.identifier();
        var token = ctxIdentifier.start;
        var unionId = ctxIdentifier.getText();
        var currentModule = getCurrentModule();

         currentUnion = currentModule.newUnionObject(unionId, fileBeingCompiled, token.getLine(), token.getCharPositionInLine());
        if(currentUnion.isEmpty()) {
            var metadata = currentModule.getIdMetadata(unionId).get();
            throw new RuntimeException(("Error: %s line: %d, col %d.%nIn union declaration, " +
                    "identifier %s is already defined.%n" +
                    "Previous declaration is here: %s line: %d, %d")
                    .formatted(fileBeingCompiled,
                            ctx.start.getLine(),
                            ctx.start.getCharPositionInLine(),
                            unionId,
                            metadata.getFilePath(),
                            metadata.getLineNumber(),
                            metadata.getColumn()));
        }
    }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void exitUnion_type(FACE_IDLParser.Union_typeContext ctx) {
        super.exitUnion_type(ctx);

        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error enterUnion_type.");
        }

        if(_scopeStack.peek().getKind() != IScopedObject.ScopedObjectKind.Union){
            throw new RuntimeException("Unexpected coding error exitUnion_type.");
        }
        _scopeStack.pop();
    }

    /**
     *
     * @param ctx the parse tree
     */
    @Override
    public void enterEnum_type(FACE_IDLParser.Enum_typeContext ctx) {
        super.enterEnum_type(ctx);

        var ctxIdentifier = ctx.identifier();
        var token = ctxIdentifier.start;
        var enumId = ctxIdentifier.getText();
        var currentModule = getCurrentModule();
        var newEnum = currentModule.newEnumObject(enumId, fileBeingCompiled, token.getLine(), token.getCharPositionInLine());

        if(newEnum.isEmpty()) {
            var metadata = currentModule.getIdMetadata(enumId).get();
            throw new RuntimeException(("Error: %s line: %d, col %d.%nIn enum declaration, " +
                    "identifier %s is already defined.%n" +
                    "Previous declaration is here: %s line: %d, %d")
                    .formatted(fileBeingCompiled,
                            ctx.start.getLine(),
                            ctx.start.getCharPositionInLine(),
                            enumId,
                            metadata.getFilePath(),
                            metadata.getLineNumber(),
                            metadata.getColumn()));
        }
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitEnum_type(FACE_IDLParser.Enum_typeContext ctx) {
        super.exitEnum_type(ctx);
    }



    @Override
    public void enterType_dclarator(FACE_IDLParser.Type_dclaratorContext ctx) {
        super.enterType_dclarator(ctx);

        //Let other methods know to store type information in designated register variables.
        passTypeInfoFlag = true;
    }

    @Override
    public void exitType_dclarator(FACE_IDLParser.Type_dclaratorContext ctx) {
        super.exitType_dclarator(ctx);

        //Typedef is a special case since it doesn't have a single identifier.

        var declarators = ctx.declarators();


        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error exitType_dclarator.");
        }

        var currentScope = _scopeStack.peek();

        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Module) {
            declaratorListRegister.forEach(
                declarator -> {
                    var typedef = new Typedef(declarator);
                    if(currentScope.containsDeclarator(declarator)) {
                        throw new RuntimeException("Redefinition of typedef declarator.");
                    }
                    typedef.setTypeSpec(typeSpecRegister);
                    currentScope.addScopedObject(typedef);
                });
            declaratorListRegister.clear();
            typeSpecRegister = null;
        } else {
            throw new RuntimeException("Unexpected coding error exitType_dclarator.");
        }
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterTemplate_module_dcl(FACE_IDLParser.Template_module_dclContext ctx) {
        super.enterTemplate_module_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitTemplate_module_dcl(FACE_IDLParser.Template_module_dclContext ctx) {
        super.exitTemplate_module_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFormal_parameters(FACE_IDLParser.Formal_parametersContext ctx) {
        super.enterFormal_parameters(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFormal_parameters(FACE_IDLParser.Formal_parametersContext ctx) {
        super.exitFormal_parameters(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFormal_parameter(FACE_IDLParser.Formal_parameterContext ctx) {
        super.enterFormal_parameter(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFormal_parameter(FACE_IDLParser.Formal_parameterContext ctx) {
        super.exitFormal_parameter(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFormal_parameter_type(FACE_IDLParser.Formal_parameter_typeContext ctx) {
        super.enterFormal_parameter_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFormal_parameter_type(FACE_IDLParser.Formal_parameter_typeContext ctx) {
        super.exitFormal_parameter_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterTpl_definition(FACE_IDLParser.Tpl_definitionContext ctx) {
        super.enterTpl_definition(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitTpl_definition(FACE_IDLParser.Tpl_definitionContext ctx) {
        super.exitTpl_definition(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterTemplate_module_inst(FACE_IDLParser.Template_module_instContext ctx) {
        super.enterTemplate_module_inst(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitTemplate_module_inst(FACE_IDLParser.Template_module_instContext ctx) {
        super.exitTemplate_module_inst(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterActual_parameters(FACE_IDLParser.Actual_parametersContext ctx) {
        super.enterActual_parameters(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitActual_parameters(FACE_IDLParser.Actual_parametersContext ctx) {
        super.exitActual_parameters(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterActual_parameter(FACE_IDLParser.Actual_parameterContext ctx) {
        super.enterActual_parameter(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitActual_parameter(FACE_IDLParser.Actual_parameterContext ctx) {
        super.exitActual_parameter(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterTemplate_module_ref(FACE_IDLParser.Template_module_refContext ctx) {
        super.enterTemplate_module_ref(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitTemplate_module_ref(FACE_IDLParser.Template_module_refContext ctx) {
        super.exitTemplate_module_ref(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFormal_parameter_names(FACE_IDLParser.Formal_parameter_namesContext ctx) {
        super.enterFormal_parameter_names(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFormal_parameter_names(FACE_IDLParser.Formal_parameter_namesContext ctx) {
        super.exitFormal_parameter_names(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_or_forward_dcl(FACE_IDLParser.Interface_or_forward_dclContext ctx) {
        super.enterInterface_or_forward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_or_forward_dcl(FACE_IDLParser.Interface_or_forward_dclContext ctx) {
        super.exitInterface_or_forward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterForward_dcl(FACE_IDLParser.Forward_dclContext ctx) {
        super.enterForward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitForward_dcl(FACE_IDLParser.Forward_dclContext ctx) {
        super.exitForward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_header(FACE_IDLParser.Interface_headerContext ctx) {
        super.enterInterface_header(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_header(FACE_IDLParser.Interface_headerContext ctx) {
        super.exitInterface_header(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_body(FACE_IDLParser.Interface_bodyContext ctx) {
        super.enterInterface_body(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_body(FACE_IDLParser.Interface_bodyContext ctx) {
        super.exitInterface_body(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterExport_(FACE_IDLParser.Export_Context ctx) {
        super.enterExport_(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitExport_(FACE_IDLParser.Export_Context ctx) {
        super.exitExport_(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_inheritance_spec(FACE_IDLParser.Interface_inheritance_specContext ctx) {
        super.enterInterface_inheritance_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_inheritance_spec(FACE_IDLParser.Interface_inheritance_specContext ctx) {
        super.exitInterface_inheritance_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInterface_name(FACE_IDLParser.Interface_nameContext ctx) {
        super.enterInterface_name(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInterface_name(FACE_IDLParser.Interface_nameContext ctx) {
        super.exitInterface_name(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterA_scoped_name(FACE_IDLParser.A_scoped_nameContext ctx) {
        super.enterA_scoped_name(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitA_scoped_name(FACE_IDLParser.A_scoped_nameContext ctx) {
        super.exitA_scoped_name(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterScoped_name(FACE_IDLParser.Scoped_nameContext ctx) {
        super.enterScoped_name(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitScoped_name(FACE_IDLParser.Scoped_nameContext ctx) {
        super.exitScoped_name(ctx);

        var scopedName = new ScopedNameType();
        scopedName.setScopedName(ctx.getText());
        typeSpecRegister = scopedName;
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterConst_dcl(FACE_IDLParser.Const_dclContext ctx) {
        super.enterConst_dcl(ctx);

        var id = ctx.identifier().getText();

        if(_scopeStack.isEmpty()) {
            throw new RuntimeException("Unexpected coding error enterConst_dcl.");
        }

        var currentScope = _scopeStack.peek();

        if (currentScope.containsId(id)){
            //Error here.
            throw new RuntimeException("ID already defined.  I know not enough information.");
        }

        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Module) {
            var newConstant = new Constant(id);

            _scopeStack.push(newConstant);
        } else {
            throw new RuntimeException("Unexpected coding error enterConst_dcl.");
        }

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitConst_dcl(FACE_IDLParser.Const_dclContext ctx) {
        super.exitConst_dcl(ctx);

        if(_scopeStack.isEmpty() ||
           !(_scopeStack.peek() instanceof Constant currentScope)) {
            throw new RuntimeException("Unexpected coding error exitConst_dcl.");
        }

        if(!(typeSpecRegister instanceof BaseTypeSpec ||
             typeSpecRegister instanceof ScopedNameType) ||
                typeSpecRegister == null) {
            throw new RuntimeException("Unexpected coding error exitConst_dcl.");
        }

        Constant newConstant;
        newConstant = currentScope;
        newConstant.setDataType(typeSpecRegister);
        newConstant.setExpression(ctx.const_expr().getText());
        //currentScope.addScopedObject(newConstant);

        //Set the register back to None so its clear for the next process.
        typeSpecRegister = null;


        _scopeStack.pop();
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterConst_type(FACE_IDLParser.Const_typeContext ctx) {
        super.enterConst_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitConst_type(FACE_IDLParser.Const_typeContext ctx) {
        super.exitConst_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterConst_expr(FACE_IDLParser.Const_exprContext ctx) {
        super.enterConst_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitConst_expr(FACE_IDLParser.Const_exprContext ctx) {
        super.exitConst_expr(ctx);

        var txt = ctx.getText();
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterOr_expr(FACE_IDLParser.Or_exprContext ctx) {
        super.enterOr_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitOr_expr(FACE_IDLParser.Or_exprContext ctx) {
        super.exitOr_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterXor_expr(FACE_IDLParser.Xor_exprContext ctx) {
        super.enterXor_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitXor_expr(FACE_IDLParser.Xor_exprContext ctx) {
        super.exitXor_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAnd_expr(FACE_IDLParser.And_exprContext ctx) {
        super.enterAnd_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAnd_expr(FACE_IDLParser.And_exprContext ctx) {
        super.exitAnd_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterShift_expr(FACE_IDLParser.Shift_exprContext ctx) {
        super.enterShift_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitShift_expr(FACE_IDLParser.Shift_exprContext ctx) {
        super.exitShift_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAdd_expr(FACE_IDLParser.Add_exprContext ctx) {
        super.enterAdd_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAdd_expr(FACE_IDLParser.Add_exprContext ctx) {
        super.exitAdd_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterMult_expr(FACE_IDLParser.Mult_exprContext ctx) {
        super.enterMult_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitMult_expr(FACE_IDLParser.Mult_exprContext ctx) {
        super.exitMult_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnary_expr(FACE_IDLParser.Unary_exprContext ctx) {
        super.enterUnary_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnary_expr(FACE_IDLParser.Unary_exprContext ctx) {
        super.exitUnary_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnary_operator(FACE_IDLParser.Unary_operatorContext ctx) {
        super.enterUnary_operator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnary_operator(FACE_IDLParser.Unary_operatorContext ctx) {
        super.exitUnary_operator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterPrimary_expr(FACE_IDLParser.Primary_exprContext ctx) {
        super.enterPrimary_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitPrimary_expr(FACE_IDLParser.Primary_exprContext ctx) {
        super.exitPrimary_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterLiteral(FACE_IDLParser.LiteralContext ctx) {
        super.enterLiteral(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitLiteral(FACE_IDLParser.LiteralContext ctx) {
        super.exitLiteral(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterPositive_int_const(FACE_IDLParser.Positive_int_constContext ctx) {
        super.enterPositive_int_const(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitPositive_int_const(FACE_IDLParser.Positive_int_constContext ctx) {
        super.exitPositive_int_const(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterType_dcl(FACE_IDLParser.Type_dclContext ctx) {
        super.enterType_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitType_dcl(FACE_IDLParser.Type_dclContext ctx) {
        super.exitType_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterType_spec(FACE_IDLParser.Type_specContext ctx) {
        super.enterType_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitType_spec(FACE_IDLParser.Type_specContext ctx) {
        super.exitType_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSimple_type_spec(FACE_IDLParser.Simple_type_specContext ctx) {
        super.enterSimple_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSimple_type_spec(FACE_IDLParser.Simple_type_specContext ctx) {
        super.exitSimple_type_spec(ctx);

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBitfield_type_spec(FACE_IDLParser.Bitfield_type_specContext ctx) {
        super.enterBitfield_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBitfield_type_spec(FACE_IDLParser.Bitfield_type_specContext ctx) {
        super.exitBitfield_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBase_type_spec(FACE_IDLParser.Base_type_specContext ctx) {
        super.enterBase_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBase_type_spec(FACE_IDLParser.Base_type_specContext ctx) {
        super.exitBase_type_spec(ctx);

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterTemplate_type_spec(FACE_IDLParser.Template_type_specContext ctx) {
        super.enterTemplate_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitTemplate_type_spec(FACE_IDLParser.Template_type_specContext ctx) {
        super.exitTemplate_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterConstr_type_spec(FACE_IDLParser.Constr_type_specContext ctx) {
        super.enterConstr_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitConstr_type_spec(FACE_IDLParser.Constr_type_specContext ctx) {
        super.exitConstr_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSimple_dclarators(FACE_IDLParser.Simple_dclaratorsContext ctx) {
        super.enterSimple_dclarators(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSimple_dclarators(FACE_IDLParser.Simple_dclaratorsContext ctx) {
        super.exitSimple_dclarators(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterDeclarators(FACE_IDLParser.DeclaratorsContext ctx) {
        super.enterDeclarators(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitDeclarators(FACE_IDLParser.DeclaratorsContext ctx) {
        super.exitDeclarators(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterDeclarator(FACE_IDLParser.DeclaratorContext ctx) {
        super.enterDeclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitDeclarator(FACE_IDLParser.DeclaratorContext ctx) {
        super.exitDeclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSimple_dclarator(FACE_IDLParser.Simple_dclaratorContext ctx) {
        super.enterSimple_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSimple_dclarator(FACE_IDLParser.Simple_dclaratorContext ctx) {
        super.exitSimple_dclarator(ctx);

        var id = ctx.start.getText();
        declaratorListRegister.add(id);
        declaratorStack.push(id);  //Todo: Look into removing this one.
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterComplex_dclarator(FACE_IDLParser.Complex_dclaratorContext ctx) {
        super.enterComplex_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitComplex_dclarator(FACE_IDLParser.Complex_dclaratorContext ctx) {
        super.exitComplex_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFloating_pt_type(FACE_IDLParser.Floating_pt_typeContext ctx) {
        super.enterFloating_pt_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFloating_pt_type(FACE_IDLParser.Floating_pt_typeContext ctx) {
        super.exitFloating_pt_type(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.Float);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterInteger_type(FACE_IDLParser.Integer_typeContext ctx) {
        super.enterInteger_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitInteger_type(FACE_IDLParser.Integer_typeContext ctx) {
        super.exitInteger_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSigned_int(FACE_IDLParser.Signed_intContext ctx) {
        super.enterSigned_int(ctx);

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSigned_int(FACE_IDLParser.Signed_intContext ctx) {
        super.exitSigned_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSigned_tiny_int(FACE_IDLParser.Signed_tiny_intContext ctx) {
        super.enterSigned_tiny_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSigned_tiny_int(FACE_IDLParser.Signed_tiny_intContext ctx) {
        super.exitSigned_tiny_int(ctx);

        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.SignedTiny);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSigned_short_int(FACE_IDLParser.Signed_short_intContext ctx) {
        super.enterSigned_short_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSigned_short_int(FACE_IDLParser.Signed_short_intContext ctx) {
        super.exitSigned_short_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.SignedShort);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSigned_long_int(FACE_IDLParser.Signed_long_intContext ctx) {
        super.enterSigned_long_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSigned_long_int(FACE_IDLParser.Signed_long_intContext ctx) {
        super.exitSigned_long_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.SignedLong);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSigned_longlong_int(FACE_IDLParser.Signed_longlong_intContext ctx) {
        super.enterSigned_longlong_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSigned_longlong_int(FACE_IDLParser.Signed_longlong_intContext ctx) {
        super.exitSigned_longlong_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.SignedLongLong);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnsigned_int(FACE_IDLParser.Unsigned_intContext ctx) {
        super.enterUnsigned_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnsigned_int(FACE_IDLParser.Unsigned_intContext ctx) {
        super.exitUnsigned_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnsigned_tiny_int(FACE_IDLParser.Unsigned_tiny_intContext ctx) {
        super.enterUnsigned_tiny_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnsigned_tiny_int(FACE_IDLParser.Unsigned_tiny_intContext ctx) {
        super.exitUnsigned_tiny_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.UnsignedTiny);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnsigned_short_int(FACE_IDLParser.Unsigned_short_intContext ctx) {
        super.enterUnsigned_short_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnsigned_short_int(FACE_IDLParser.Unsigned_short_intContext ctx) {
        super.exitUnsigned_short_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.UnsignedShort);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnsigned_long_int(FACE_IDLParser.Unsigned_long_intContext ctx) {
        super.enterUnsigned_long_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnsigned_long_int(FACE_IDLParser.Unsigned_long_intContext ctx) {
        super.exitUnsigned_long_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.UnsignedLong);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterUnsigned_longlong_int(FACE_IDLParser.Unsigned_longlong_intContext ctx) {
        super.enterUnsigned_longlong_int(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitUnsigned_longlong_int(FACE_IDLParser.Unsigned_longlong_intContext ctx) {
        super.exitUnsigned_longlong_int(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.UnsignedLongLong);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterChar_type(FACE_IDLParser.Char_typeContext ctx) {
        super.enterChar_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitChar_type(FACE_IDLParser.Char_typeContext ctx) {
        super.exitChar_type(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.Char);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterWide_char_type(FACE_IDLParser.Wide_char_typeContext ctx) {
        super.enterWide_char_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitWide_char_type(FACE_IDLParser.Wide_char_typeContext ctx) {
        super.exitWide_char_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBoolean_type(FACE_IDLParser.Boolean_typeContext ctx) {
        super.enterBoolean_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBoolean_type(FACE_IDLParser.Boolean_typeContext ctx) {
        super.exitBoolean_type(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.Boolean);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterOctet_type(FACE_IDLParser.Octet_typeContext ctx) {
        super.enterOctet_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitOctet_type(FACE_IDLParser.Octet_typeContext ctx) {
        super.exitOctet_type(ctx);
        typeSpecRegister = new BaseTypeSpec(BaseDataTypes.Octet);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAny_type(FACE_IDLParser.Any_typeContext ctx) {
        super.enterAny_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAny_type(FACE_IDLParser.Any_typeContext ctx) {
        super.exitAny_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterObject_type(FACE_IDLParser.Object_typeContext ctx) {
        super.enterObject_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitObject_type(FACE_IDLParser.Object_typeContext ctx) {
        super.exitObject_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBitset_type(FACE_IDLParser.Bitset_typeContext ctx) {
        super.enterBitset_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBitset_type(FACE_IDLParser.Bitset_typeContext ctx) {
        super.exitBitset_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBitfield(FACE_IDLParser.BitfieldContext ctx) {
        super.enterBitfield(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBitfield(FACE_IDLParser.BitfieldContext ctx) {
        super.exitBitfield(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBitfield_spec(FACE_IDLParser.Bitfield_specContext ctx) {
        super.enterBitfield_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBitfield_spec(FACE_IDLParser.Bitfield_specContext ctx) {
        super.exitBitfield_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBitmask_type(FACE_IDLParser.Bitmask_typeContext ctx) {
        super.enterBitmask_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBitmask_type(FACE_IDLParser.Bitmask_typeContext ctx) {
        super.exitBitmask_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterBit_values(FACE_IDLParser.Bit_valuesContext ctx) {
        super.enterBit_values(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitBit_values(FACE_IDLParser.Bit_valuesContext ctx) {
        super.exitBit_values(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterMember_list(FACE_IDLParser.Member_listContext ctx) {
        super.enterMember_list(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitMember_list(FACE_IDLParser.Member_listContext ctx) {
        super.exitMember_list(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterMember(FACE_IDLParser.MemberContext ctx) {
        super.enterMember(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitMember(FACE_IDLParser.MemberContext ctx) {
        super.exitMember(ctx);

        var currentScope = _scopeStack.peek();
        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Struct){
            StructType struct = (StructType) currentScope;
            struct.addMember(typeSpecRegister, declaratorListRegister.toArray(new String[0]));
            declaratorListRegister.clear();
        } else {
            throw new RuntimeException("Unexpected coding exit Member.");
        }
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSwitch_type_spec(FACE_IDLParser.Switch_type_specContext ctx) {
        super.enterSwitch_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSwitch_type_spec(FACE_IDLParser.Switch_type_specContext ctx) {
        super.exitSwitch_type_spec(ctx);

        var currentScope = _scopeStack.peek();
        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Union){
            var union = (UnionType)currentScope;
            union.setSwitchType(typeSpecRegister);
            typeSpecRegister = null;
        } else {
            throw new RuntimeException("Unexpected coding exit Switch_type_spec.");
        }

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSwitch_body(FACE_IDLParser.Switch_bodyContext ctx) {
        super.enterSwitch_body(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSwitch_body(FACE_IDLParser.Switch_bodyContext ctx) {
        super.exitSwitch_body(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterCase_stmt(FACE_IDLParser.Case_stmtContext ctx) {
        super.enterCase_stmt(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitCase_stmt(FACE_IDLParser.Case_stmtContext ctx) {
        super.exitCase_stmt(ctx);

        var currentScope = _scopeStack.peek();
        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Union) {
            var union = (UnionType)currentScope;

            var labels = ctx.case_label().stream().map(x -> {
                if(x.KW_DEFAULT() != null){
                    return x.KW_DEFAULT().getText();
                }else {
                    return x.const_expr().getText();
                }
            }).toArray(String[]::new);
            union.addCaseStatement(labels,typeSpecRegister, declaratorListRegister.get(0));
            typeSpecRegister = null;
            declaratorListRegister.clear();
        } else {
            throw new RuntimeException("Unexpected coding error exitCase_stmt.");
        }
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterCase_label(FACE_IDLParser.Case_labelContext ctx) {
        super.enterCase_label(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitCase_label(FACE_IDLParser.Case_labelContext ctx) {
        super.exitCase_label(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterElement_spec(FACE_IDLParser.Element_specContext ctx) {
        super.enterElement_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitElement_spec(FACE_IDLParser.Element_specContext ctx) {
        super.exitElement_spec(ctx);

        var currentScope = _scopeStack.peek();
        if(currentScope.getKind() == IScopedObject.ScopedObjectKind.Enum ||
           currentScope.getKind() == IScopedObject.ScopedObjectKind.Union) {

        } else {
            throw new RuntimeException("Unexpected coding error exitElement_spec.");
        }
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterEnumerator(FACE_IDLParser.EnumeratorContext ctx) {
        super.enterEnumerator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitEnumerator(FACE_IDLParser.EnumeratorContext ctx) {
        super.exitEnumerator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSequence_type(FACE_IDLParser.Sequence_typeContext ctx) {
        super.enterSequence_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSequence_type(FACE_IDLParser.Sequence_typeContext ctx) {
        super.exitSequence_type(ctx);

        var newSequenceType = new SequenceType();

        if(ctx.positive_int_const() != null) {
            newSequenceType.setPositiveInt(ctx.positive_int_const().getText());
        }
        newSequenceType.setTypeSpec(typeSpecRegister);

        //Set the register for any upstream types.
        typeSpecRegister = newSequenceType;
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSet_type(FACE_IDLParser.Set_typeContext ctx) {
        super.enterSet_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSet_type(FACE_IDLParser.Set_typeContext ctx) {
        super.exitSet_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterMap_type(FACE_IDLParser.Map_typeContext ctx) {
        super.enterMap_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitMap_type(FACE_IDLParser.Map_typeContext ctx) {
        super.exitMap_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterString_type(FACE_IDLParser.String_typeContext ctx) {
        super.enterString_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitString_type(FACE_IDLParser.String_typeContext ctx) {
        super.exitString_type(ctx);

        var newStringType = new StringType();
        if(ctx.positive_int_const() != null){
            newStringType.setLength(ctx.positive_int_const().getText());
        }

        typeSpecRegister = newStringType;
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterWide_string_type(FACE_IDLParser.Wide_string_typeContext ctx) {
        super.enterWide_string_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitWide_string_type(FACE_IDLParser.Wide_string_typeContext ctx) {
        super.exitWide_string_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterArray_dclarator(FACE_IDLParser.Array_dclaratorContext ctx) {
        super.enterArray_dclarator(ctx);

        throw new RuntimeException("Array type definitions not supported.");
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitArray_dclarator(FACE_IDLParser.Array_dclaratorContext ctx) {
        super.exitArray_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFixed_array_size(FACE_IDLParser.Fixed_array_sizeContext ctx) {
        super.enterFixed_array_size(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFixed_array_size(FACE_IDLParser.Fixed_array_sizeContext ctx) {
        super.exitFixed_array_size(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAttr_dcl(FACE_IDLParser.Attr_dclContext ctx) {
        super.enterAttr_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAttr_dcl(FACE_IDLParser.Attr_dclContext ctx) {
        super.exitAttr_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterExcept_dcl(FACE_IDLParser.Except_dclContext ctx) {
        super.enterExcept_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitExcept_dcl(FACE_IDLParser.Except_dclContext ctx) {
        super.exitExcept_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterOp_dcl(FACE_IDLParser.Op_dclContext ctx) {
        super.enterOp_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitOp_dcl(FACE_IDLParser.Op_dclContext ctx) {
        super.exitOp_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterOp_attribute(FACE_IDLParser.Op_attributeContext ctx) {
        super.enterOp_attribute(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitOp_attribute(FACE_IDLParser.Op_attributeContext ctx) {
        super.exitOp_attribute(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterOp_type_spec(FACE_IDLParser.Op_type_specContext ctx) {
        super.enterOp_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitOp_type_spec(FACE_IDLParser.Op_type_specContext ctx) {
        super.exitOp_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterParameter_dcls(FACE_IDLParser.Parameter_dclsContext ctx) {
        super.enterParameter_dcls(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitParameter_dcls(FACE_IDLParser.Parameter_dclsContext ctx) {
        super.exitParameter_dcls(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterParam_dcl(FACE_IDLParser.Param_dclContext ctx) {
        super.enterParam_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitParam_dcl(FACE_IDLParser.Param_dclContext ctx) {
        super.exitParam_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterParam_attribute(FACE_IDLParser.Param_attributeContext ctx) {
        super.enterParam_attribute(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitParam_attribute(FACE_IDLParser.Param_attributeContext ctx) {
        super.exitParam_attribute(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterRaises_expr(FACE_IDLParser.Raises_exprContext ctx) {
        super.enterRaises_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitRaises_expr(FACE_IDLParser.Raises_exprContext ctx) {
        super.exitRaises_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterContext_expr(FACE_IDLParser.Context_exprContext ctx) {
        super.enterContext_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitContext_expr(FACE_IDLParser.Context_exprContext ctx) {
        super.exitContext_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterParam_type_spec(FACE_IDLParser.Param_type_specContext ctx) {
        super.enterParam_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitParam_type_spec(FACE_IDLParser.Param_type_specContext ctx) {
        super.exitParam_type_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFixed_pt_type(FACE_IDLParser.Fixed_pt_typeContext ctx) {
        super.enterFixed_pt_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFixed_pt_type(FACE_IDLParser.Fixed_pt_typeContext ctx) {
        super.exitFixed_pt_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterFixed_pt_const_type(FACE_IDLParser.Fixed_pt_const_typeContext ctx) {
        super.enterFixed_pt_const_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitFixed_pt_const_type(FACE_IDLParser.Fixed_pt_const_typeContext ctx) {
        super.exitFixed_pt_const_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterValue_base_type(FACE_IDLParser.Value_base_typeContext ctx) {
        super.enterValue_base_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitValue_base_type(FACE_IDLParser.Value_base_typeContext ctx) {
        super.exitValue_base_type(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterConstr_forward_dcl(FACE_IDLParser.Constr_forward_dclContext ctx) {
        super.enterConstr_forward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitConstr_forward_dcl(FACE_IDLParser.Constr_forward_dclContext ctx) {
        super.exitConstr_forward_dcl(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterReadonly_attr_spec(FACE_IDLParser.Readonly_attr_specContext ctx) {
        super.enterReadonly_attr_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitReadonly_attr_spec(FACE_IDLParser.Readonly_attr_specContext ctx) {
        super.exitReadonly_attr_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterReadonly_attr_dclarator(FACE_IDLParser.Readonly_attr_dclaratorContext ctx) {
        super.enterReadonly_attr_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitReadonly_attr_dclarator(FACE_IDLParser.Readonly_attr_dclaratorContext ctx) {
        super.exitReadonly_attr_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAttr_spec(FACE_IDLParser.Attr_specContext ctx) {
        super.enterAttr_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAttr_spec(FACE_IDLParser.Attr_specContext ctx) {
        super.exitAttr_spec(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAttr_dclarator(FACE_IDLParser.Attr_dclaratorContext ctx) {
        super.enterAttr_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAttr_dclarator(FACE_IDLParser.Attr_dclaratorContext ctx) {
        super.exitAttr_dclarator(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterAttr_raises_expr(FACE_IDLParser.Attr_raises_exprContext ctx) {
        super.enterAttr_raises_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitAttr_raises_expr(FACE_IDLParser.Attr_raises_exprContext ctx) {
        super.exitAttr_raises_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterGet_excep_expr(FACE_IDLParser.Get_excep_exprContext ctx) {
        super.enterGet_excep_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitGet_excep_expr(FACE_IDLParser.Get_excep_exprContext ctx) {
        super.exitGet_excep_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterSet_excep_expr(FACE_IDLParser.Set_excep_exprContext ctx) {
        super.enterSet_excep_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitSet_excep_expr(FACE_IDLParser.Set_excep_exprContext ctx) {
        super.exitSet_excep_expr(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterException_list(FACE_IDLParser.Exception_listContext ctx) {
        super.enterException_list(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitException_list(FACE_IDLParser.Exception_listContext ctx) {
        super.exitException_list(ctx);
    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void enterIdentifier(FACE_IDLParser.IdentifierContext ctx) {
        super.enterIdentifier(ctx);

    }

    /**
     * @param ctx the parse tree
     */
    @Override
    public void exitIdentifier(FACE_IDLParser.IdentifierContext ctx) {
        super.exitIdentifier(ctx);
        var id = ctx.start.getText();
    }

    /**
     * @param ctx
     */
    @Override
    public void enterEveryRule(ParserRuleContext ctx) {
        super.enterEveryRule(ctx);
    }

    /**
     * @param ctx
     */
    @Override
    public void exitEveryRule(ParserRuleContext ctx) {
        super.exitEveryRule(ctx);
    }

    /**
     * @param node
     */
    @Override
    public void visitTerminal(TerminalNode node) {
        super.visitTerminal(node);
    }

    /**
     * @param node
     */
    @Override
    public void visitErrorNode(ErrorNode node) {
        super.visitErrorNode(node);
    }

    /**
     * @return
     */
    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * @param obj
     * @return
     */
    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }

    /**
     * @return
     * @throws CloneNotSupportedException
     */
    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /**
     * @return
     */
    @Override
    public String toString() {
        return super.toString();
    }

     private ModuleObject getCurrentModule() {
        return moduleStack.peek();
    }

    enum IDL_Types {
        signed_short_int,
        signed_long_int,
        signed_longlong_int,
        signed_tiny_int,
        unsigned_short_int,
        unsigned_long_int,
        unsigned_longlong_int,
        unsigned_tiny_int,
        char_type,
        wide_char_type,
        floating_point_type,
        string_type,
        wide_string_type,
        boolean_type,
        octet_type,
        scoped_name,
        any_type
    }


    private boolean passTypeInfoFlag = false;
    //private BaseDataTypes dataTypeRegister = BaseDataTypes.None;
    private ITypeSpec typeSpecRegister = null;
    private final Vector<String> declaratorListRegister = new Vector<>();

    private final CompilerContext compilerContext;
    private final ModuleObject globalModule;
    private final Stack<ModuleObject> moduleStack = new Stack<>();
    private final String fileBeingCompiled;
    private final Stack<String> declaratorStack = new Stack<>();
    private final Stack<IScopedObject> _scopeStack = new Stack<>();
}
