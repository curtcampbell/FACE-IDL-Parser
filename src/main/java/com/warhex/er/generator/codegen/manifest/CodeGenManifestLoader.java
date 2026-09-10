package com.warhex.er.generator.codegen.manifest;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Loads and validates a {@link CodeGenManifest} from a {@code codegen.yaml} file.
 *
 * <h2>Usage</h2>
 * <pre>
 *   CodeGenManifest manifest = CodeGenManifestLoader.load(templateDir.resolve("codegen.yaml"));
 * </pre>
 *
 * <p>The loader uses SnakeYAML with a typed constructor so YAML fields map
 * directly onto {@link CodeGenManifest} and {@link GenerationEntry} fields.
 * Unknown YAML keys are ignored (lenient loading).
 */
public final class CodeGenManifestLoader {

    private static final Logger LOG =
            Logger.getLogger(CodeGenManifestLoader.class.getName());

    private CodeGenManifestLoader() {}

    /** Filename expected in the user's template directory. */
    public static final String MANIFEST_FILENAME = "codegen.yaml";

    /**
     * Returns an empty manifest with no variables, helpers, or generation entries.
     * Used when {@code --manifest} is omitted and no {@code codegen.yaml} is present.
     * All generation is driven entirely by {@code ##!} template directives.
     */
    public static CodeGenManifest empty() {
        CodeGenManifest m = new CodeGenManifest();
        m.validate(); // logs the "no generations" warning — expected and harmless
        return m;
    }

    /**
     * Loads and validates the manifest at {@code path} if the file exists;
     * otherwise returns {@link #empty()}.
     *
     * <p>Use this when {@code --manifest} was not explicitly supplied: a missing
     * manifest is normal in directive-only mode.
     *
     * @param path candidate path to {@code codegen.yaml}
     * @return validated manifest, or an empty manifest if the file is absent
     * @throws IOException if the file exists but cannot be read
     */
    public static CodeGenManifest loadOrEmpty(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            LOG.info("No manifest found at " + path.toAbsolutePath()
                    + "; running in directive-only mode (all generation via ##! directives).");
            return empty();
        }
        return load(path);
    }

    /**
     * Loads and validates the manifest at {@code path}.
     *
     * @param path absolute or relative path to {@code codegen.yaml}
     * @return validated manifest
     * @throws IOException           if the file cannot be read
     * @throws IllegalStateException if the manifest fails structural validation
     */
    public static CodeGenManifest load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("codegen.yaml not found at: " + path.toAbsolutePath());
        }

        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);

        Yaml yaml = new Yaml(new Constructor(CodeGenManifest.class, opts));

        try (InputStream in = Files.newInputStream(path)) {
            CodeGenManifest manifest = yaml.load(in);
            if (manifest == null) {
                throw new IllegalStateException("codegen.yaml is empty: " + path);
            }
            manifest.validate();
            return manifest;
        }
    }
}
