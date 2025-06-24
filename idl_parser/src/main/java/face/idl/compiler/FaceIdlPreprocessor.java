package face.idl.compiler;

import mil.army.face.idl.preprocessor.FACE_PreprocessParser;
import mil.army.face.idl.preprocessor.FACE_PreprocessParserBaseListener;

import java.nio.file.Path;
import java.util.Stack;

public class FaceIdlPreprocessor extends FACE_PreprocessParserBaseListener {

    //Stack tracking nested if conditions.
    final Stack<Boolean> ifCheckStack = new Stack<>();

    public FaceIdlPreprocessor(CompilerContext compilerContext, StringBuffer outputBuffer) {
        this.compilerContext = compilerContext;
        this.outputBuffer = outputBuffer;
    }

    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitPreprocessorDirective(FACE_PreprocessParser.PreprocessorDirectiveContext ctx) {
        super.exitPreprocessorDirective(ctx);
        //Output a newline in place of preprocessor directives.
        outputBuffer.append("\n");
    }

    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitSoureceLine(FACE_PreprocessParser.SoureceLineContext ctx) {
        super.exitSoureceLine(ctx);
        if(!isInIgnoreRegion()){
            outputBuffer.append(ctx.LINE_TEXT().getText());
        } else {
            outputBuffer.append("\n");
        }
    }

    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitDefinedVariable(FACE_PreprocessParser.DefinedVariableContext ctx) {
        super.exitDefinedVariable(ctx);
        compilerContext.defineId(ctx.IDENTIFIER().getText());
    }

    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitIfnDefDirective(FACE_PreprocessParser.IfnDefDirectiveContext ctx) {
        super.exitIfnDefDirective(ctx);
        var newId = ctx.IDENTIFIER().getText();
        if(compilerContext.isDefined(newId)){
            failedIfCheck();
        } else {
            passedIfCheck();
        }
    }


    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitEndifDirective(FACE_PreprocessParser.EndifDirectiveContext ctx) {
        super.exitEndifDirective(ctx);

        exitIfCheck();
    }

    /**
     * @param ctx the parse tree 
     */
    @Override
    public void exitIncludeArgs(FACE_PreprocessParser.IncludeArgsContext ctx) {
        super.exitIncludeArgs(ctx);

        var includeFile = ctx.getText().replaceAll("[\"<>]","");
        Path path = Path.of(includeFile);

        try {
            compilerContext.parse(path);
        } catch (Exception e) {
            throw new RuntimeException("%s\nError occurred on line %d, column %d.\n".formatted(e.getMessage(),
                    ctx.start.getLine(),
                    ctx.start.getCharPositionInLine()));
        }
    }

    void passedIfCheck() {
        ifCheckStack.push(true);
    }

    void failedIfCheck() {
        ifCheckStack.push(false);
    }

    boolean isInIgnoreRegion() {
        return !ifCheckStack.isEmpty() && !ifCheckStack.peek();
    }

    void exitIfCheck() {
       if(!ifCheckStack.empty()) {
           ifCheckStack.pop();
       }
    }

    private final CompilerContext compilerContext;
    private final StringBuffer outputBuffer;
}
