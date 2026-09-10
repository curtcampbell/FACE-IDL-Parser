package com.warhex.er.generator.binding.generic;

import com.warhex.er.generator.binding.LanguageMapper;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.FileResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Scans {@code templates/languages/} for subdirectories containing a
 * {@code language.yaml} descriptor file and constructs one
 * {@link GenericLanguageMapper} per discovered language.
 *
 * <h2>Discovery algorithm</h2>
 * <ol>
 *   <li>List direct children of {@code templateLanguagesRoot}.</li>
 *   <li>For each child directory that contains a {@code language.yaml} file,
 *       deserialise it into a {@link LanguageDescriptor} via SnakeYAML 2.2.</li>
 *   <li>Build a per-language {@link VelocityEngine} pointed at that language's
 *       template directory (so templates in {@code language.yaml} use bare
 *       filenames like {@code "struct.java.vm"} rather than
 *       {@code "java/struct.java.vm"}).</li>
 *   <li>Load the macro library ({@code macro_library} from the descriptor) if present.</li>
 *   <li>Construct a {@link GenericLanguageMapper} from the descriptor and engine.</li>
 * </ol>
 *
 * <p>If a subdirectory does not contain {@code language.yaml} it is silently ignored,
 * allowing template helper files to coexist alongside descriptor-driven languages.
 *
 * <p>Errors loading a specific descriptor are logged and that language is skipped;
 * other languages are still loaded.
 */
public class LanguageDescriptorLoader {

    private static final Logger LOG =
            Logger.getLogger(LanguageDescriptorLoader.class.getName());

    private static final String DESCRIPTOR_FILENAME = "language.yaml";

    /**
     * Scans {@code templateLanguagesRoot} for language descriptors and returns
     * one {@link GenericLanguageMapper} per discovered language.
     *
     * @param templateLanguagesRoot root of the {@code templates/languages/} directory
     *                              (must exist and be a directory)
     * @return possibly-empty, unmodifiable list of mappers in discovery order
     */
    public List<LanguageMapper> load(Path templateLanguagesRoot) {
        List<LanguageMapper> result = new ArrayList<>();

        if (!Files.isDirectory(templateLanguagesRoot)) {
            LOG.warning("LanguageDescriptorLoader: templates/languages/ not found at "
                    + templateLanguagesRoot + " — no generic mappers will be loaded.");
            return List.of();
        }

        try {
            Files.list(templateLanguagesRoot)
                 .filter(Files::isDirectory)
                 .sorted()
                 .forEach(langDir -> {
                     Path descriptorFile = langDir.resolve(DESCRIPTOR_FILENAME);
                     if (!Files.exists(descriptorFile)) {
                         LOG.fine("No language.yaml in " + langDir + " — skipping.");
                         return;
                     }
                     try {
                         GenericLanguageMapper mapper = loadOne(langDir, descriptorFile);
                         result.add(mapper);
                         LOG.info("Loaded generic mapper: " + mapper.languageName()
                                 + " from " + langDir);
                     } catch (Exception e) {
                         LOG.warning("Failed to load language descriptor from "
                                 + descriptorFile + ": " + e.getMessage());
                     }
                 });
        } catch (IOException e) {
            LOG.warning("LanguageDescriptorLoader: error listing "
                    + templateLanguagesRoot + ": " + e.getMessage());
        }

        return List.copyOf(result);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private GenericLanguageMapper loadOne(Path langDir, Path descriptorFile) throws Exception {
        LanguageDescriptor descriptor = deserialise(descriptorFile);
        VelocityEngine ve = buildVelocityEngine(langDir, descriptor);
        return new GenericLanguageMapper(descriptor, ve);
    }

    /**
     * Deserialises a {@code language.yaml} file into a {@link LanguageDescriptor}
     * using SnakeYAML 2.2's {@code loadAs()} with explicit {@link Constructor}.
     */
    private LanguageDescriptor deserialise(Path descriptorFile) throws Exception {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Constructor constructor = new Constructor(LanguageDescriptor.class, opts);
        Yaml yaml = new Yaml(constructor);

        try (InputStream in = Files.newInputStream(descriptorFile)) {
            return yaml.loadAs(in, LanguageDescriptor.class);
        }
    }

    /**
     * Builds a {@link VelocityEngine} for the given language directory.
     *
     * <p>The engine is pointed at {@code langDir} so that templates named in
     * {@code language.yaml} (e.g. {@code "struct.java.vm"}) are resolved relative
     * to that directory rather than relative to {@code templates/languages/}.
     *
     * <p>If the descriptor specifies a {@code macro_library}, it is registered as
     * Velocity's VM library so all macros in that file are available globally
     * within this language's templates.
     */
    private VelocityEngine buildVelocityEngine(Path langDir,
                                               LanguageDescriptor descriptor) {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "file");
        ve.setProperty("resource.loader.file.class",
                FileResourceLoader.class.getName());
        ve.setProperty("resource.loader.file.path",
                langDir.toAbsolutePath().toString());
        ve.setProperty("resource.loader.file.cache", false);
        ve.setProperty("space.gobbling", "lines");

        // Load the macro library if specified.
        // Velocity silently ignores the library if the file is not found, so this
        // is safe to set unconditionally when a filename is provided.
        if (descriptor.macro_library != null && !descriptor.macro_library.isBlank()) {
            ve.setProperty(RuntimeConstants.VM_LIBRARY, descriptor.macro_library);
        }

        ve.init();
        return ve;
    }
}
