package com.warhex.er.generator.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Entry point for FACE IDL parsing.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>{@link IdlIncludeResolver} — collect all translation units reachable
 *       from the root IDL file, in dependency order.</li>
 *   <li>Concatenate their source text into a single string.</li>
 *   <li>Feed the concatenated source to the ANTLR4-generated
 *       {@code FACE_IDLLexer} and {@code FACE_IDLParser}.</li>
 *   <li>Return the {@code SpecificationContext} parse tree root for further
 *       processing by {@link IdlAstBuilder}.</li>
 * </ol>
 *
 * <h2>Preprocessor directives</h2>
 * The grammar's {@code PREPROCESSOR} lexer rule sends all {@code #...} lines
 * to hidden channel 1, so {@code #pragma once}, {@code #ifndef} guards, and
 * any remaining (unresolved) {@code #include} lines are invisible to the
 * parser.  Include resolution happens before the lexer runs.
 *
 * <h2>Search path</h2>
 * Construct with a list of directories to search when resolving
 * {@code #include} targets.  An empty list restricts resolution to files in
 * the same directory as the including file.
 */
public class IdlParser {

    private static final Logger LOG = Logger.getLogger(IdlParser.class.getName());

    private final List<Path> searchDirs;

    /**
     * @param searchDirs directories searched when resolving {@code #include}
     *                   directives; the including file's own directory is
     *                   always tried first.  Pass an empty list to restrict
     *                   resolution to same-directory includes only.
     */
    public IdlParser(List<Path> searchDirs) {
        this.searchDirs = new ArrayList<>(searchDirs);
    }

    /** Convenience constructor — no additional search directories. */
    public IdlParser() {
        this(Collections.emptyList());
    }

    /**
     * Parses {@code rootIdl} and all reachable {@code #include}d files.
     *
     * @param rootIdl path to the top-level IDL file
     * @return parse tree root ({@code specification} rule)
     * @throws IOException    on file-read failure
     * @throws ParseException on a syntax error or missing root file
     */
    public FACE_IDLParser.SpecificationContext parse(Path rootIdl)
            throws IOException {

        // ------------------------------------------------------------------
        // Step 1 — resolve includes → ordered list of translation units
        // ------------------------------------------------------------------
        IdlIncludeResolver resolver = new IdlIncludeResolver(searchDirs);
        List<Path> units = resolver.resolve(rootIdl);
        LOG.fine(() -> "IDL translation units (" + units.size() + "): " + units);

        // ------------------------------------------------------------------
        // Step 2 — concatenate sources
        //
        // A blank line is inserted between files so that the last line of one
        // file does not run into the first line of the next.  The PREPROCESSOR
        // rule already handles any residual #pragma / #include lines.
        // ------------------------------------------------------------------
        StringBuilder sb = new StringBuilder();
        for (Path unit : units) {
            sb.append(Files.readString(unit, StandardCharsets.UTF_8));
            sb.append('\n');
        }

        // ------------------------------------------------------------------
        // Step 3 — lex and parse
        // ------------------------------------------------------------------
        CharStream stream = CharStreams.fromString(sb.toString());

        FACE_IDLLexer lexer = new FACE_IDLLexer(stream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        FACE_IDLParser parser = new FACE_IDLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        FACE_IDLParser.SpecificationContext tree = parser.specification();

        if (parser.getNumberOfSyntaxErrors() > 0) {
            // ThrowingErrorListener should have thrown already; defensive check.
            throw new ParseException(
                    "IDL parse failed with " + parser.getNumberOfSyntaxErrors()
                    + " syntax error(s) in: " + rootIdl);
        }

        LOG.fine(() -> "IDL parse complete: " + rootIdl);
        return tree;
    }

    /**
     * Parses a pre-assembled IDL source string (already concatenated by the
     * caller) and returns a fully-built {@link com.warhex.er.generator.ast.IdlSpecification}.
     *
     * <p>This entry point is used by {@link IdlDirectoryParser}, which handles
     * multi-root include resolution externally and passes the combined source
     * text here for lexing + parsing + AST construction.
     *
     * @param source combined IDL source text (preprocessor directives are
     *               silently hidden by the grammar's {@code PREPROCESSOR} rule)
     * @return fully populated {@link com.warhex.er.generator.ast.IdlSpecification}
     * @throws ParseException if the source contains syntax errors
     */
    public com.warhex.er.generator.ast.IdlSpecification parseSource(String source) {
        CharStream stream = CharStreams.fromString(source);

        FACE_IDLLexer lexer = new FACE_IDLLexer(stream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        FACE_IDLParser antlrParser = new FACE_IDLParser(tokens);
        antlrParser.removeErrorListeners();
        antlrParser.addErrorListener(ThrowingErrorListener.INSTANCE);

        FACE_IDLParser.SpecificationContext tree = antlrParser.specification();

        if (antlrParser.getNumberOfSyntaxErrors() > 0) {
            throw new ParseException(
                    "IDL parse failed with " + antlrParser.getNumberOfSyntaxErrors()
                    + " syntax error(s) in combined source.");
        }

        IdlAstBuilder builder = new IdlAstBuilder();
        return builder.visitSpecification(tree);
    }

    // -------------------------------------------------------------------------
    // Error listener — converts ANTLR4 diagnostic callbacks into exceptions
    // -------------------------------------------------------------------------

    private static final class ThrowingErrorListener extends BaseErrorListener {

        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            throw new ParseException(
                    "IDL syntax error at line " + line + ":" + charPositionInLine
                    + " — " + msg, e);
        }
    }
}
