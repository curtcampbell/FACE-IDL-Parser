package com.warhex.er.generator.idl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Recursively scans a template root directory for {@code .vtl} files and
 * returns a parsed {@link TemplateMetadata} for each one found.
 *
 * <h2>Discovery rules</h2>
 * <ul>
 *   <li>Only files whose names end with {@code .vtl} are collected.</li>
 *   <li>All subdirectories are traversed recursively.</li>
 *   <li>Directory structure relative to the root is preserved in
 *       {@link TemplateMetadata#getRelativeTemplatePath()} so the IDL
 *       pipeline can mirror it in the output tree.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   List&lt;TemplateMetadata&gt; templates =
 *       new TemplateScanner(templateRoot).scan();
 * </pre>
 */
public class TemplateScanner {

    private static final Logger LOG = Logger.getLogger(TemplateScanner.class.getName());

    private final Path templateRoot;

    /**
     * @param templateRoot root directory to scan; must exist and be a directory.
     */
    public TemplateScanner(Path templateRoot) {
        this.templateRoot = templateRoot;
    }

    /**
     * Walks the template root recursively and returns metadata for every
     * {@code .vtl} file discovered.
     *
     * @return ordered list of {@link TemplateMetadata} (breadth-first within
     *         each directory, directories in file-system iteration order)
     * @throws IOException if the root directory cannot be read or a template
     *                     file cannot be parsed
     */
    public List<TemplateMetadata> scan() throws IOException {
        if (!Files.isDirectory(templateRoot)) {
            throw new IOException("Template root is not a directory: " + templateRoot);
        }

        List<TemplateMetadata> result = new ArrayList<>();

        Files.walkFileTree(templateRoot, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {

                String name = file.getFileName().toString();
                if (!name.endsWith(".vtl")) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = templateRoot.relativize(file);
                TemplateMetadata meta = TemplateMetadata.parse(file, relative);
                LOG.fine(() -> "Found template: " + meta);
                result.add(meta);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.warning("Could not access " + file + ": " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        LOG.info("Template scan of " + templateRoot + " found " + result.size() + " template(s).");
        return result;
    }
}
