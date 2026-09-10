package com.warhex.er.generator.parser;

import com.warhex.er.generator.ast.IdlDefinition;
import com.warhex.er.generator.ast.IdlSpecification;
import com.warhex.er.generator.ast.ModuleNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// IdlFileUnit and IdlParseResult are in the same package — no import needed

/**
 * Parses all {@code .idl} files found under a directory tree into a single,
 * unified {@link IdlSpecification}.
 *
 * <h2>Why a single merged spec?</h2>
 * The {@link com.warhex.er.generator.binding.TemplateInstantiator} needs every
 * template module <em>definition</em> (e.g. {@code ::FACE::TSS::Typed<D>} from
 * {@code FACE/TSS/TypedTS.idl}) and every template module <em>instantiation</em>
 * (e.g. {@code module ::FACE::TSS::Typed<EntityEvent_t> EntityEvent}) to be in
 * the same {@link IdlSpecification}.  Parsing each IDL file independently would
 * produce separate specs and break the registry lookup inside the instantiator.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Walk the IDL root directory and collect all {@code .idl} paths in
 *       lexicographic order (deterministic output).</li>
 *   <li>For each root IDL file, use {@link IdlIncludeResolver} (with the
 *       FACE framework IDL directory on its search path) to resolve the
 *       transitive set of translation units in dependency order.</li>
 *   <li>Merge all translation units across all root files into a single
 *       {@link LinkedHashSet} — deduplication means each physical file
 *       contributes its source text exactly once, even if multiple IDL files
 *       {@code #include} the same framework header.</li>
 *   <li>Concatenate the source of all translation units in dependency order
 *       and feed the combined text to the ANTLR4 parser.</li>
 *   <li>Return the resulting {@link IdlSpecification}.</li>
 * </ol>
 *
 * <h2>Error reporting</h2>
 * Because all sources are concatenated before parsing, ANTLR reports line numbers
 * relative to the combined string.  On a {@link ParseException} this class maps
 * the combined-source line number back to the originating file, computes the
 * local line within that file, and re-throws an enriched exception that includes:
 * the file name, local line:column, the offending source line, and a caret (^)
 * pointing to the error column.
 *
 * <h2>Include search path</h2>
 * One or more search directories must be provided.  The first entry is
 * typically the FACE framework IDL root ({@code face-idl/}); additional
 * entries are user-supplied {@code -I} paths.  Directories are searched
 * in the order given, after the including file's own directory.
 */
public class IdlDirectoryParser {

    private static final Logger LOG =
            Logger.getLogger(IdlDirectoryParser.class.getName());

    /**
     * Matches ANTLR error messages of the form:
     * {@code IDL syntax error at line 333:1 — extraneous input ...}
     * Groups: (1) line, (2) column, (3) detail message.
     * The em-dash comes from {@link IdlParser}'s ThrowingErrorListener.
     */
    private static final Pattern ANTLR_LINE_PATTERN =
            Pattern.compile("at line (\\d+):(\\d+)\\s*—\\s*(.+)", Pattern.DOTALL);

    private final List<Path> searchDirs;

    /**
     * @param searchDirs ordered list of directories searched when resolving
     *                   {@code #include} directives.  The including file's own
     *                   directory is always tried first; these directories are
     *                   tried next, in order.  The list must contain at least
     *                   the FACE framework IDL root so that directives such as
     *                   {@code #include <FACE/TSS/TypedTS.idl>} resolve correctly.
     */
    public IdlDirectoryParser(List<Path> searchDirs) {
        this.searchDirs = List.copyOf(searchDirs);
    }

    /**
     * Walks {@code idlRootDir} recursively, collects every {@code .idl} file,
     * resolves their transitive translation units (including FACE framework IDL),
     * and parses the merged source into a single {@link IdlSpecification}.
     *
     * @param idlRootDir directory containing generated IDL files
     * @return {@link IdlParseResult} containing the merged spec and per-file units
     * @throws IOException    if any IDL file or include cannot be read
     * @throws ParseException if the combined IDL source contains syntax errors;
     *                        the message identifies the originating file and line
     */
    public IdlParseResult parse(Path idlRootDir) throws IOException {
        if (!Files.isDirectory(idlRootDir)) {
            throw new IOException("IDL root is not a directory: " + idlRootDir);
        }

        // Collect all .idl root files (sorted for deterministic ordering)
        List<Path> rootFiles = collectIdlFiles(idlRootDir);
        LOG.info("IdlDirectoryParser: found " + rootFiles.size()
                 + " IDL root files under " + idlRootDir);

        // Resolve transitive translation units, deduplicating across root files
        IdlIncludeResolver resolver = new IdlIncludeResolver(searchDirs);
        LinkedHashSet<Path> allUnits = new LinkedHashSet<>();
        for (Path root : rootFiles) {
            allUnits.addAll(resolver.resolve(root));
        }

        LOG.info("IdlDirectoryParser: " + allUnits.size()
                 + " unique translation units (including FACE framework IDL)");

        // ------------------------------------------------------------------
        // Concatenate all sources, tracking each file's starting line number
        // in the combined string (1-based, matching ANTLR's line counter).
        //
        // Line-offset accounting:
        //   A file with N newlines in its content, plus the one separator '\n'
        //   we append, contributes N+1 newlines to the combined source.
        //   The next file therefore starts at  startLine + N + 1.
        // ------------------------------------------------------------------
        List<FileEntry> fileEntries = new ArrayList<>(allUnits.size());
        StringBuilder combined = new StringBuilder();
        int combinedLine = 1;          // 1-based, mirrors ANTLR's line counter

        for (Path unit : allUnits) {
            String content = Files.readString(unit, StandardCharsets.UTF_8);
            fileEntries.add(new FileEntry(unit, combinedLine));
            combined.append(content).append('\n');
            long newlines = content.chars().filter(c -> c == '\n').count();
            combinedLine += (int) newlines + 1;   // +1 for the separator '\n'
        }

        // ------------------------------------------------------------------
        // Parse — on failure, map the combined line back to the source file
        // and re-throw with actionable context.
        // ------------------------------------------------------------------
        IdlParser parser = new IdlParser(searchDirs);
        IdlSpecification spec;
        try {
            spec = parser.parseSource(combined.toString());
            LOG.info("IdlDirectoryParser: parse complete — "
                     + spec.definitions().size() + " top-level definitions.");
        } catch (ParseException e) {
            throw enrichException(e, fileEntries);
        }

        // ------------------------------------------------------------------
        // Build per-file units: parse each root IDL file individually to
        // obtain only the definitions directly in that file (not from includes).
        // #include lines are on the hidden ANTLR channel so they don't cause
        // errors during single-file direct-content parsing.
        // ------------------------------------------------------------------
        List<IdlFileUnit> fileUnits = buildFileUnits(rootFiles, idlRootDir);
        LOG.info("IdlDirectoryParser: " + fileUnits.size() + " file units built.");

        return new IdlParseResult(spec, fileUnits);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Parses each root IDL file individually (direct content only) to extract
     * the definitions declared directly in that file, without following
     * {@code #include} directives.
     *
     * <p>Because {@code #include} / {@code #pragma} / {@code #ifndef} lines are
     * sent to the hidden ANTLR channel during ANTLR's lexer phase, parsing a
     * file's raw content produces only the definitions directly in that file.
     *
     * <p>Errors for individual files are logged and skipped — the merged parse
     * already validated the combined source, so individual file errors should
     * not occur in practice.
     */
    private List<IdlFileUnit> buildFileUnits(List<Path> rootFiles,
                                             Path idlRootDir) {
        IdlParser singleParser = new IdlParser(searchDirs);
        List<IdlFileUnit> units = new ArrayList<>(rootFiles.size());
        for (Path root : rootFiles) {
            try {
                String content = Files.readString(root, StandardCharsets.UTF_8);
                IdlSpecification fileSpc = singleParser.parseSource(content);
                Path relative = idlRootDir.relativize(root);
                // Phase 8: tag every definition in this file unit with its source path
                tagSourceFile(fileSpc.definitions(), root);
                units.add(new IdlFileUnit(root, relative, fileSpc.definitions()));
                LOG.fine("File unit: " + relative
                         + " → " + fileSpc.definitions().size() + " top-level defs");
            } catch (IOException | ParseException e) {
                LOG.warning("Could not build file unit for " + root
                        + ": " + e.getMessage());
            }
        }
        return units;
    }

    /**
     * Collects all {@code .idl} files under {@code dir}, sorted by
     * path string for deterministic ordering.
     */
    private List<Path> collectIdlFiles(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".idl"))
                .sorted()
                .forEach(result::add);
        }
        return result;
    }

    /**
     * Maps the combined-source line in a {@link ParseException} back to the
     * originating file and re-throws an enriched exception that shows:
     * <ul>
     *   <li>The file name and full path</li>
     *   <li>The local line:column within that file</li>
     *   <li>The offending source line (read from the file)</li>
     *   <li>A caret (^) pointing to the error column</li>
     * </ul>
     * Returns the original exception unchanged if the line number cannot be
     * extracted or the file cannot be identified.
     */
    private static ParseException enrichException(ParseException original,
                                                   List<FileEntry> entries) {
        String msg = original.getMessage();
        if (msg == null || entries.isEmpty()) return original;

        Matcher m = ANTLR_LINE_PATTERN.matcher(msg);
        if (!m.find()) return original;

        int combinedLine = Integer.parseInt(m.group(1));
        int col          = Integer.parseInt(m.group(2));   // 0-based (ANTLR)
        String detail    = m.group(3).trim();

        // Binary-search: find the last FileEntry whose startLine <= combinedLine
        FileEntry owner = entries.get(0);
        for (FileEntry entry : entries) {
            if (entry.startLine <= combinedLine) owner = entry;
            else break;
        }

        int localLine = combinedLine - owner.startLine + 1;

        // Attempt to read the offending source line for the caret indicator
        String sourceLine = readSourceLine(owner.path, localLine);

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append(String.format("IDL syntax error in '%s' at line %d, column %d%n",
                owner.path.getFileName(), localLine, col + 1));
        sb.append(String.format("  File  : %s%n", owner.path.toAbsolutePath()));
        sb.append(String.format("  Error : %s%n", detail));
        if (sourceLine != null) {
            String linePrefix = "  " + localLine + " | ";
            sb.append(linePrefix).append(sourceLine).append(System.lineSeparator());
            // Caret under the offending column (col is 0-based)
            int caretPad = linePrefix.length() + Math.max(0, col);
            sb.append(" ".repeat(caretPad)).append("^").append(System.lineSeparator());
        }
        sb.append(String.format("  (combined-source line %d)", combinedLine));

        return new ParseException(sb.toString().trim(), original);
    }

    /**
     * Reads the Nth line (1-based) from {@code file}.
     * Returns {@code null} if the file cannot be read or the line does not exist.
     */
    private static String readSourceLine(Path file, int lineNumber) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int i = 1; i < lineNumber; i++) {
                if (reader.readLine() == null) return null;
            }
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Phase 8: source-file tagging
    // -----------------------------------------------------------------------

    /**
     * Walks {@code defs} recursively and sets {@link IdlDefinition#setSourceFile}
     * to {@code sourceFile} on every node, including those nested inside
     * {@link ModuleNode}s.
     *
     * <p>Only called for per-file unit nodes (from {@link #buildFileUnits}), not
     * for the merged-spec nodes produced by the combined parse.
     */
    private static void tagSourceFile(List<IdlDefinition> defs, Path sourceFile) {
        for (IdlDefinition def : defs) {
            def.setSourceFile(sourceFile);
            if (def instanceof ModuleNode m) {
                tagSourceFile(m.definitions(), sourceFile);
            }
        }
    }

    // -----------------------------------------------------------------------
    // File-offset table entry
    // -----------------------------------------------------------------------

    /**
     * Associates a source file with the line number at which its content
     * begins in the combined parse input (1-based, matching ANTLR).
     */
    private static final class FileEntry {
        final Path path;
        final int  startLine;

        FileEntry(Path path, int startLine) {
            this.path      = path;
            this.startLine = startLine;
        }

        @Override
        public String toString() {
            return path.getFileName() + "@line" + startLine;
        }
    }
}
