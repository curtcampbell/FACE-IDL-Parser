package face.idl.compiler;

import face.idl.model.ModuleObject;
import face.idl.model.StructObject;
import face.idl.model.UnionObject;
import mil.army.face.idl.FACE_IDLBaseListener;
import mil.army.face.idl.FACE_IDLParser;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Stack;

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
    public void enterModule(FACE_IDLParser.ModuleContext ctx) {
        super.enterModule(ctx);

        var moduleId = ctx.identifier().getText();

        var newParentModule = getCurrentModule();
        var moduleForId = newParentModule.getModule(moduleId);
        ModuleObject newCurrentModule
                = moduleForId.orElseGet(() -> newParentModule.getNewOrExistingModule(moduleId));

        moduleStack.push(newCurrentModule);
    }

    /**
     * 
     * @param ctx the parse tree
     */
    @Override
    public void exitModule(FACE_IDLParser.ModuleContext ctx) {
        super.exitModule(ctx);

        // Pop this module as we exit this namespace.
        moduleStack.pop();
    }

    private Optional<StructObject> currentStruct = Optional.empty();

    /**
     * 
     * @param ctx the parse tree
     */
    @Override
    public void enterStruct_type(FACE_IDLParser.Struct_typeContext ctx) {
        super.enterStruct_type(ctx);

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

        currentUnion = Optional.empty();
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

    private ModuleObject getCurrentModule() {
        return moduleStack.peek();
    }

    private final CompilerContext compilerContext;
    private final ModuleObject globalModule;
    private final Stack<ModuleObject> moduleStack = new Stack<>();
    private final String fileBeingCompiled;
}
