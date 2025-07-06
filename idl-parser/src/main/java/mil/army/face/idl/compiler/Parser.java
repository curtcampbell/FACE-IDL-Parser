package mil.army.face.idl.compiler;

import mil.army.face.idl.FACE_IDLLexer;
import mil.army.face.idl.FACE_IDLParser;
import mil.army.face.idl.preprocessor.FACE_PreprocessLexer;
import mil.army.face.idl.preprocessor.FACE_PreprocessParser;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 *
 */
public class Parser {

    private final CompilerContext compilerContext;

    public Parser(CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public boolean parse(Path inputFile) throws IOException {

        CharStream input =  CharStreams.fromPath(inputFile);
//        System.out.printf("Parsing: %s%n", inputFile.getFileName());

        Optional<String> outputBuffer = Optional.empty();
        try {

            outputBuffer = preprocess(input);
            if(outputBuffer.isEmpty()) {
                System.err.println("Errors detected during parsing.");
                return false;
            }
        } catch(Exception e){
            System.err.printf("Error parsing file %s.\nCause: %s%n", inputFile.toString(), e.getMessage());
            return false;
        }

        var preprocessed = CharStreams.fromString(outputBuffer.get());
        if(!parseIdl(inputFile, preprocessed)) {
            System.err.printf("Errors parsing: %s.%n", inputFile.getFileName());
            return false;
        };

        return true;
    }


     Optional<String> preprocess(CharStream input){

        FACE_PreprocessLexer lexer = new FACE_PreprocessLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        FACE_PreprocessParser parser = new FACE_PreprocessParser(tokens);
        ParseTree tree = parser.source(); // begin parsing at source rule

        if(parser.getNumberOfSyntaxErrors() != 0) {
            //Don't walk the parse tree if we have errors.
            return Optional.empty();
        }

        // Create a generic parse tree walker to begin filling our buffer with preprocess idl source.
        ParseTreeWalker walker = new ParseTreeWalker();

        StringBuffer retVal = new StringBuffer();
        // Walk the tree created during the parse, trigger callbacks
        walker.walk(new FaceIdlPreprocessor(compilerContext, retVal), tree);

        return Optional.of(retVal.toString());
    }

     boolean parseIdl(Path fileToBeParsed, CharStream input) {
        // create a lexer that feeds off of input CharStream
        var lexer = new FACE_IDLLexer(input);

        // create a buffer of tokens pulled from the lexer
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // create a parser that feeds off the tokens buffer
        var parser = new FACE_IDLParser(tokens);
        ParseTree tree = parser.specification(); // Begin parsing at the specification rule.

        if(parser.getNumberOfSyntaxErrors() != 0) {
            return false;
        }

        //Now walk the tree to create our IDL object model.
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(new FaceDataModelBuilder(compilerContext, fileToBeParsed), tree);

        return true;
    }

}
