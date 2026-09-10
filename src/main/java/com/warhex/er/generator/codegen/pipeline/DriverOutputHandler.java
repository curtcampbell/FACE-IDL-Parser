package com.warhex.er.generator.codegen.pipeline;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.IncludeEventHandler;
import org.apache.velocity.context.Context;

import java.io.BufferedOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Velocity {@link IncludeEventHandler} that intercepts {@code #parse} calls when
 * {@code $outFile} is set in a driver template's context.
 *
 * <h2>Driver template contract</h2>
 * <pre>
 * ##! for_each: TEMPLATE_INST
 * ##! filter:   SomeAlias
 * ##! driver:   true
 *
 * #set($outFile = "${project_namespace}/${inst.alias}TS.hpp")
 * #parse("leaf_header.hpp.vm")
 * #set($outFile = "${inst.alias}TS.cpp")
 * #parse("leaf_source.cpp.vm")
 * </pre>
 *
 * When {@code $outFile} is set and {@code #parse} fires, this handler:
 * <ol>
 *   <li>Resolves the output path relative to {@code outputDir}.</li>
 *   <li>Renders the named leaf template into the resolved file.</li>
 *   <li>Returns {@code null} to suppress Velocity's own include (the leaf's
 *       content was already written; no output should go to the driver's writer).</li>
 * </ol>
 *
 * When {@code $outFile} is <em>not</em> set, the handler returns the original
 * resource path unchanged (normal {@code #parse} behaviour).
 *
 * <p>{@code #include} (raw-copy) directives are never intercepted.
 */
final class DriverOutputHandler implements IncludeEventHandler {

    private static final Logger LOG =
            Logger.getLogger(DriverOutputHandler.class.getName());

    private final VelocityEngine velocity;
    private final Path           outputDir;
    private final AtomicInteger  filesWritten;

    DriverOutputHandler(VelocityEngine velocity,
                        Path outputDir,
                        AtomicInteger filesWritten) {
        this.velocity     = velocity;
        this.outputDir    = outputDir;
        this.filesWritten = filesWritten;
    }

    /**
     * Intercepts a {@code #parse} or {@code #include} directive.
     *
     * @param context             the current Velocity context (driver's live context)
     * @param includeResourcePath the resource name passed to {@code #parse}
     * @param currentResourcePath the template that contains the {@code #parse} call
     * @param directiveName       {@code "parse"} or {@code "include"} (lowercase, no {@code #})
     * @return modified resource path for Velocity to use, or {@code null} to suppress the include
     */
    @Override
    public String includeEvent(Context context,
                               String includeResourcePath,
                               String currentResourcePath,
                               String directiveName) {
        // Only intercept #parse; let #include pass through unchanged
        if (!"parse".equalsIgnoreCase(directiveName)) {
            return includeResourcePath;
        }

        Object outFileObj = context.get("outFile");
        if (outFileObj == null) {
            // $outFile not set: behave as a normal #parse
            return includeResourcePath;
        }

        String relative = outFileObj.toString().trim();
        if (relative.isEmpty()) {
            LOG.warning("Driver #parse(\"" + includeResourcePath
                    + "\"): $outFile is set but empty. Skipping.");
            return null;
        }

        Path target = outputDir.resolve(relative).normalize();

        // Security: reject path traversal outside the output tree
        if (!target.startsWith(outputDir)) {
            throw new SecurityException(
                    "$outFile path traversal detected: '"
                    + relative + "' resolves outside output directory.");
        }

        try {
            Files.createDirectories(target.getParent());

            Template leaf = velocity.getTemplate(
                    includeResourcePath, StandardCharsets.UTF_8.name());

            // Build a leaf context that inherits all variables from the driver's
            // live context (including $inst, $resolved, $er, manifest variables …).
            // VelocityContext(Context) delegates unresolved lookups to the parent.
            VelocityContext leafCtx = new VelocityContext(context);

            try (Writer w = new OutputStreamWriter(
                    new BufferedOutputStream(Files.newOutputStream(target)),
                    StandardCharsets.UTF_8)) {
                leaf.merge(leafCtx, w);
            }

            filesWritten.incrementAndGet();
            LOG.info("  Written (driver): " + target);

        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Driver #parse(\"" + includeResourcePath
                    + "\") → \"" + relative + "\" failed: " + e.getMessage(), e);
        }

        // Return null: Velocity suppresses its own rendering of the leaf.
        // The content was already written by the merge() call above.
        return null;
    }
}
