package com.warhex.er.generator.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves IDL {@code #include} directives before handing source to the
 * ANTLR4 parser.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Scan each IDL file's lines for {@code #include "file"} or
 *       {@code #include <file>} directives (regex-based, no macro evaluation).</li>
 *   <li>Locate the referenced file — first relative to the including file's
 *       directory, then in each configured search directory in order.</li>
 *   <li>Recurse into the included file before adding the including file, so the
 *       returned list is in dependency order (leaves first).</li>
 *   <li>Track canonical paths to suppress duplicate inclusions — this is
 *       equivalent to {@code #pragma once} without requiring the directive.</li>
 * </ol>
 *
 * <h2>Missing includes</h2>
 * If an {@code #include} target cannot be found on the search path it is
 * silently skipped.  This handles FACE platform IDL files (e.g.
 * {@code FACE/Common.idl}) that are inputs to the FACE tool suite but are not
 * part of this generation run.  The parser will fail later if their declarations
 * are actually referenced.
 */
public class IdlIncludeResolver {

    /** Matches {@code #include "file"} and {@code #include <file>}. */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\"'>]+)[\">]");

    private final List<Path> searchDirs;

    /**
     * @param searchDirs directories searched when resolving include paths,
     *                   in priority order; the including file's own directory
     *                   is always tried first, before this list.
     */
    public IdlIncludeResolver(List<Path> searchDirs) {
        this.searchDirs = new ArrayList<>(searchDirs);
    }

    /**
     * Resolves all IDL translation units reachable from {@code root}.
     *
     * @param root the top-level IDL file to start from
     * @return ordered list of canonical paths — leaves (deepest dependencies)
     *         first, {@code root} last; each file appears at most once
     * @throws IOException   on file-read failure
     * @throws ParseException if {@code root} does not exist
     */
    public List<Path> resolve(Path root) throws IOException {
        if (!Files.isRegularFile(root)) {
            throw new ParseException("IDL root file not found: " + root);
        }
        LinkedHashSet<Path> visited = new LinkedHashSet<>();
        visit(root.toRealPath(), visited);
        return new ArrayList<>(visited);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void visit(Path file, LinkedHashSet<Path> visited) throws IOException {
        if (visited.contains(file)) {
            return;  // already queued — include-guard equivalent
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            Matcher m = INCLUDE_PATTERN.matcher(line);
            if (m.find()) {
                String included = m.group(1);
                Path resolved = locate(included, file.getParent());
                if (resolved != null) {
                    visit(resolved, visited);
                }
                // Not found → silently skip (platform / external IDL)
            }
        }

        visited.add(file);
    }

    /**
     * Locates {@code filename} by checking the including file's directory first,
     * then each configured search directory.
     *
     * @return canonical path if found, {@code null} otherwise
     */
    private Path locate(String filename, Path includingDir) throws IOException {
        // 1. Relative to the file that contains the #include
        Path candidate = includingDir.resolve(filename);
        if (Files.isRegularFile(candidate)) {
            return candidate.toRealPath();
        }

        // 2. Configured search directories
        for (Path dir : searchDirs) {
            candidate = dir.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                return candidate.toRealPath();
            }
        }

        return null;
    }
}
