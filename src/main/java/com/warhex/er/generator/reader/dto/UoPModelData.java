package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Root DTO for the FACE UoP model section of a {@code .face} XMI file.
 *
 * <p>Holds the full UoP tree plus the deduplicated catalogue of platform data
 * types that appear across all connections.  The type catalogue drives
 * {@code data-model/} IDL generation (one struct per type), while the UoP
 * list drives {@code uop-tss/{UoPName}/} TypedTS IDL generation.
 *
 * <h2>Two-pass use</h2>
 * <ol>
 *   <li>{@link FaceTssReader} populates this DTO from the {@code <um>} subtree
 *       of a {@code .face} file, using object references between
 *       {@link ConnectionData} and the shared {@link #platformTypes} list.</li>
 *   <li>{@link TssToEntityModelAdapter} converts the {@link #platformTypes}
 *       list into an {@link IdlModelData} for the existing
 *       {@link IdlGeneratorPipeline} to consume.</li>
 * </ol>
 */
public class UoPModelData {

    /**
     * Human-readable model name derived from the FACE file,
     * e.g. {@code "SampleModel"}.
     */
    private String modelName;

    /**
     * IDL module to use for generated data-model structs,
     * e.g. {@code "FACE.DM.SampleModel"}.
     */
    private String idlModule;

    /**
     * Ordered list of UoPs parsed from the {@code <um>} subtree.
     * Order follows document order in the source XMI.
     */
    private List<UoPData> uoPs = new ArrayList<>();

    /**
     * Deduplicated catalogue of platform data types referenced by any
     * connection across all UoPs.  Used as the source for data-model IDL
     * struct generation.
     *
     * <p>Entries are added in first-seen order.  Each {@link ConnectionData}
     * holds an object reference into this list rather than a copy, so
     * structural sharing is preserved.
     */
    private List<TssTypeData> platformTypes = new ArrayList<>();

    /**
     * Flat list of integration contexts across all UoPs, populated by
     * {@link com.warhex.er.generator.reader.face.FaceTssReader} Pass D.
     * Empty when the source {@code .face} file has no integration model
     * ({@code <im>}) subtree.
     *
     * <p>Each entry carries a {@link IntegrationContextData#getUopName()}
     * back-reference so callers can group by UoP without a second index.
     */
    private List<IntegrationContextData> integrationContexts = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getIdlModule() { return idlModule; }
    public void setIdlModule(String idlModule) { this.idlModule = idlModule; }

    public List<UoPData> getUoPs() { return uoPs; }
    public void setUoPs(List<UoPData> uoPs) {
        this.uoPs = uoPs != null ? uoPs : new ArrayList<>();
    }

    public List<TssTypeData> getPlatformTypes() { return platformTypes; }
    public List<IntegrationContextData> getIntegrationContexts() { return integrationContexts; }
    public void setIntegrationContexts(List<IntegrationContextData> integrationContexts) {
        this.integrationContexts = integrationContexts != null ? integrationContexts : new ArrayList<>();
    }

    public void setPlatformTypes(List<TssTypeData> platformTypes) {
        this.platformTypes = platformTypes != null ? platformTypes : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "UoPModelData{modelName='" + modelName
                + "', uoPs=" + uoPs.size()
                + ", platformTypes=" + platformTypes.size()
                + ", integrationContexts=" + integrationContexts.size() + "}";
    }
}
