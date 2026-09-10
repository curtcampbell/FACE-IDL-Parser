package com.warhex.er.generator.codegen.defaults;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Loads a {@link CodegenDefaults} from a {@code codegen-defaults.yaml} file
 * co-located with a template set.
 *
 * <p>The file is optional.  If it does not exist an empty {@link CodegenDefaults}
 * is returned and the pipeline continues without defaults — no error is thrown.
 *
 * <h2>Usage</h2>
 * <pre>
 *   CodegenDefaults defaults = CodegenDefaultsLoader.load(templateDir);
 * </pre>
 */
public final class CodegenDefaultsLoader {

    private static final Logger LOG =
            Logger.getLogger(CodegenDefaultsLoader.class.getName());

    /** Filename expected in the template directory alongside the {@code .vm} files. */
    public static final String DEFAULTS_FILENAME = "codegen-defaults.yaml";

    private CodegenDefaultsLoader() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Loads defaults from {@code templateDir/codegen-defaults.yaml}.
     *
     * @param templateDir root directory of the template set (same directory
     *                    passed to {@code --template-dir})
     * @return loaded defaults, or an empty instance if the file is absent
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public static CodegenDefaults load(Path templateDir) {
        Path file = templateDir.resolve(DEFAULTS_FILENAME);
        if (!Files.isRegularFile(file)) {
            LOG.fine("No " + DEFAULTS_FILENAME + " found in " + templateDir
                    + "; proceeding without template-set defaults.");
            return new CodegenDefaults();
        }

        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);

        Yaml yaml = new Yaml(new Constructor(CodegenDefaults.class, opts));

        try (InputStream in = Files.newInputStream(file)) {
            CodegenDefaults defaults = yaml.load(in);
            if (defaults == null) {
                LOG.warning(DEFAULTS_FILENAME + " is empty: " + file
                        + "; proceeding without template-set defaults.");
                return new CodegenDefaults();
            }
            int varCount = defaults.variables != null ? defaults.variables.size() : 0;
            int helperCount = defaults.helpers != null ? defaults.helpers.size() : 0;
            LOG.info("Loaded " + DEFAULTS_FILENAME + " from " + templateDir
                    + " (" + varCount + " variable(s), " + helperCount + " helper(s)).");
            return defaults;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
