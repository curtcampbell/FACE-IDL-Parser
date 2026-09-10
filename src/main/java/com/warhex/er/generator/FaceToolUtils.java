package com.warhex.er.generator;

import com.warhex.er.generator.binding.LanguageMapper;
import com.warhex.er.generator.binding.generic.LanguageDescriptorLoader;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.reader.DefaultModelLoader;
import com.warhex.er.generator.reader.EntityModelMapper;
import com.warhex.er.generator.reader.ModelReaderFactory;
import com.warhex.er.generator.reader.dto.IdlModelData;
import com.warhex.er.generator.reader.face.FaceTemplateEntityReader;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.FileResourceLoader;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Shared CLI utility methods used by both {@link FaceIdlGen} and {@link FaceIdlBinder}.
 *
 * <p>All methods are static; this class is not instantiable.
 */
final class FaceToolUtils {

    private static final Logger LOG = Logger.getLogger(FaceToolUtils.class.getName());

    private FaceToolUtils() {}

    // -----------------------------------------------------------------------
    // Template / framework-IDL root resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the IDL template root directory.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>CLI {@code --templates-dir} flag (if provided)</li>
     *   <li>{@code templates/<subDir>/} sibling to the running JAR's install root</li>
     *   <li>{@code templates/<subDir>/} relative to the current working directory
     *       (development / IDE fallback)</li>
     * </ol>
     *
     * @param override explicit path from CLI, or {@code null}
     * @param subDir   sub-directory name (e.g. {@code "entity-reactor-idl"})
     * @return resolved absolute path; never {@code null}
     * @throws IllegalArgumentException if the directory cannot be located
     */
    static Path resolveTemplateRoot(Path override, String subDir) {
        if (override != null) {
            return override.toAbsolutePath();
        }

        Path jarSibling = jarSiblingTemplateDir(subDir);
        if (jarSibling != null && Files.isDirectory(jarSibling)) {
            LOG.info("Using installed template root: " + jarSibling);
            return jarSibling;
        }

        Path cwd = Paths.get("templates", subDir).toAbsolutePath();
        if (Files.isDirectory(cwd)) {
            LOG.info("Using development template root: " + cwd);
            return cwd;
        }

        throw new IllegalArgumentException(
            "Cannot locate IDL template directory. "
            + "Expected <install>/templates/" + subDir + "/ next to the JAR, "
            + "or ./templates/" + subDir + "/ in the working directory. "
            + "Use --templates-dir to specify the path explicitly.");
    }

    /**
     * Resolves the FACE framework IDL root directory ({@code face-idl/}).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>CLI {@code --face-idl-dir} flag (if provided)</li>
     *   <li>{@code face-idl/} sibling to the running JAR's install root</li>
     *   <li>{@code face-idl/} relative to the current working directory</li>
     * </ol>
     */
    static Path resolveFrameworkIdlDir(Path override) {
        if (override != null) {
            return override.toAbsolutePath();
        }

        Path jarSibling = jarSiblingDir("face-idl");
        if (jarSibling != null && Files.isDirectory(jarSibling)) {
            LOG.info("Using installed face-idl root: " + jarSibling);
            return jarSibling;
        }

        Path cwd = Paths.get("face-idl").toAbsolutePath();
        if (Files.isDirectory(cwd)) {
            LOG.info("Using development face-idl root: " + cwd);
            return cwd;
        }

        throw new IllegalArgumentException(
            "Cannot locate FACE framework IDL directory. "
            + "Expected <install>/face-idl/ next to the JAR, "
            + "or ./face-idl/ in the working directory. "
            + "Use --face-idl-dir to specify the path explicitly.");
    }

    /**
     * Attempts to locate {@code templates/<subDir>/} relative to the running JAR.
     * Returns {@code null} if the JAR location cannot be determined.
     */
    private static Path jarSiblingTemplateDir(String subDir) {
        Path base = jarSiblingDir("templates");
        return base == null ? null : base.resolve(subDir);
    }

    /**
     * Returns {@code <install>/<dirName>/} where {@code <install>} is the parent
     * of the {@code lib/} directory containing the running JAR.
     * Returns {@code null} if the JAR location cannot be determined.
     */
    static Path jarSiblingDir(String dirName) {
        try {
            Path jarPath = Paths.get(
                FaceToolUtils.class.getProtectionDomain()
                                   .getCodeSource()
                                   .getLocation()
                                   .toURI());
            Path parent = jarPath.getParent();          // lib/
            if (parent != null
                    && parent.getFileName() != null
                    && parent.getFileName().toString().equalsIgnoreCase("lib")) {
                parent = parent.getParent();            // install root
            }
            if (parent != null) {
                return parent.resolve(dirName);
            }
        } catch (URISyntaxException | SecurityException e) {
            LOG.fine("Could not resolve JAR location: " + e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // IDL include-path and language-mapper builders
    // -----------------------------------------------------------------------

    /**
     * Builds the ordered list of IDL include search directories.
     *
     * <p>Order: FACE framework IDL root first, then any extra {@code -I} paths.
     */
    static List<Path> buildSearchDirs(Path faceIdlDirOverride,
                                      List<Path> extraIncludePaths) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(resolveFrameworkIdlDir(faceIdlDirOverride));
        dirs.addAll(extraIncludePaths);
        return dirs;
    }

    /**
     * Builds the ordered list of language mappers to activate.
     *
     * <p>Selection rules:
     * <ul>
     *   <li>No flag → all FACE-standard languages (C++, Java).</li>
     *   <li>{@code --all-languages} → C++, Java, Python, C#.</li>
     *   <li>{@code --all-face} → C++ + Java (same as default, but explicit).</li>
     *   <li>Individual flags ({@code --cpp}, {@code --java}, …) → only those languages.</li>
     *   <li>Flags are additive: {@code --all-face --python} → C++ + Java + Python.</li>
     * </ul>
     */
    static List<LanguageMapper> buildMappers(boolean allLanguages,
                                             boolean allFace,
                                             boolean genCpp,
                                             boolean genJava,
                                             boolean genPython,
                                             boolean genCsharp) {
        boolean useDefault = !allLanguages && !allFace && !genCpp
                             && !genJava && !genPython && !genCsharp;

        boolean wantCpp    = allLanguages || allFace || genCpp    || useDefault;
        boolean wantJava   = allLanguages || allFace || genJava   || useDefault;
        boolean wantPython = allLanguages || genPython;
        boolean wantCsharp = allLanguages || genCsharp;

        Set<String> wanted = new HashSet<>();
        if (wantCpp)    wanted.add("C++");
        if (wantJava)   wanted.add("Java");
        if (wantPython) wanted.add("Python");
        if (wantCsharp) wanted.add("C#");

        Path langTmplRoot = resolveTemplateRoot(null, "languages");
        return new LanguageDescriptorLoader().load(langTmplRoot).stream()
                .filter(m -> wanted.contains(m.languageName()))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Model loading
    // -----------------------------------------------------------------------

    /** Loads and maps a YAML, JSON, or .face entity model file to an {@link EntityModel}. */
    static EntityModel loadModel(Path modelFile) throws Exception {
        return new DefaultModelLoader(
                ModelReaderFactory.forFile(modelFile),
                new EntityModelMapper())
            .load(modelFile);
    }

    /**
     * Resolves an {@link EntityModel} respecting the {@code --entities} /
     * {@code --entity-source} flags.
     *
     * <p>When either flag is set the model file <strong>must</strong> be a
     * {@code .face} file; any other extension is rejected with an
     * {@link IllegalArgumentException}.  When neither flag is set this method
     * delegates to {@link #loadModel(Path)} unchanged.
     *
     * @param modelFile         path to the model file
     * @param useTemplates      {@code true} when {@code --entities} flag is present
     * @param entitySourceGroup group name from {@code --entity-source}, or {@code null}
     * @return resolved {@link EntityModel}
     * @throws IllegalArgumentException if template mode is requested on a non-.face file
     * @throws Exception if the file cannot be read or parsed
     */
    static EntityModel resolveEntityModel(Path modelFile,
                                          boolean useTemplates,
                                          String entitySourceGroup) throws Exception {
        boolean templateMode = useTemplates || entitySourceGroup != null;

        if (!templateMode) {
            return loadModel(modelFile);
        }

        String fileName = modelFile.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".face")) {
            throw new IllegalArgumentException(
                    "--entities / --entity-source require a .face model file. Got: "
                    + modelFile.getFileName());
        }

        String group = (entitySourceGroup != null && !entitySourceGroup.isEmpty())
                ? entitySourceGroup
                : FaceTemplateEntityReader.DEFAULT_GROUP_NAME;

        LOG.info("Template entity mode: reading group '" + group
                 + "' from " + modelFile.getFileName());

        IdlModelData idlData = new FaceTemplateEntityReader(group).read(modelFile);
        return new EntityModelMapper().map(idlData);
    }

    // -----------------------------------------------------------------------
    // Velocity engine factory
    // -----------------------------------------------------------------------

    /** Builds a file-system-rooted Velocity engine for Stage 2 language templates. */
    static VelocityEngine buildFilesystemVelocityEngine(Path templateRoot) {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "file");
        ve.setProperty("resource.loader.file.class",
                FileResourceLoader.class.getName());
        ve.setProperty("resource.loader.file.path",
                templateRoot.toAbsolutePath().toString());
        ve.setProperty("resource.loader.file.cache", false);
        ve.setProperty("space.gobbling", "lines");
        ve.init();
        return ve;
    }
}
