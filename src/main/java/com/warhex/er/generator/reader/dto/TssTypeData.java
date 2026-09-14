package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing a platform data type (typically a {@code platform:Struct}
 * from a FACE XMI file) that will be generated as an IDL struct in the
 * {@code data-model/} output directory.
 *
 * <p>Each {@code TssTypeData} corresponds to one FACE Template or
 * CompositeTemplate; its fields are sourced from the referenced
 * {@code platform:Struct} members.
 */
public class TssTypeData {

    /**
     * Simple (unqualified) name of the IDL struct, e.g. {@code "M1"}.
     * Derived from the FACE Template or platform:Struct name.
     */
    private String name;

    /**
     * Fully-qualified XMI UUID of the originating FACE element.
     * Retained for cross-reference resolution during parsing; not emitted
     * in generated output.
     */
    private String uuid;

    /**
     * IDL module in which this struct will be declared,
     * e.g. {@code "FACE.DM.SampleModel"}.  Populated by the adapter from
     * the containing {@link UoPModelData}.
     */
    private String idlModule;

    /**
     * Ordered list of struct fields.  Each {@link FieldData} carries at
     * minimum {@code name} and {@code idlType}; {@code attributeValueType}
     * and {@code comment} are populated when available from the source file.
     */
    private List<FieldData> fields = new ArrayList<>();

    /**
     * When {@code true} this type should be generated as a {@code union}
     * rather than a {@code struct}.  Corresponds to a FACE CompositeTemplate
     * with {@code isUnion=true}.
     */
    private boolean union;

    /**
     * When {@code true} this type originated from a {@code uop:CompositeTemplate}
     * element.  When {@code false} it is a plain {@code uop:Template} and must
     * receive the IDL {@code module T_<name>} wrapper and trailing typedef
     * (FACE Technical Standard 3.2 §J.8, IDL-1).
     */
    private boolean compositeTemplate;

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getIdlModule() { return idlModule; }
    public void setIdlModule(String idlModule) { this.idlModule = idlModule; }

    public List<FieldData> getFields() { return fields; }
    public void setFields(List<FieldData> fields) {
        this.fields = fields != null ? fields : new ArrayList<>();
    }

    public boolean isUnion() { return union; }
    public void setUnion(boolean union) { this.union = union; }

    public boolean isCompositeTemplate() { return compositeTemplate; }
    public void setCompositeTemplate(boolean compositeTemplate) { this.compositeTemplate = compositeTemplate; }

    /**
     * Returns the simple model-namespace token from {@link #idlModule} —
     * the last dot-separated segment, e.g. {@code "CheckoutGateway_Templates"}
     * from {@code "FACE.DM.CheckoutGateway_Templates"}.  Returns the full
     * {@code idlModule} string unchanged when it contains no dot.
     *
     * <p>This bare/short form is only safe for building a path or filename
     * that mirrors it (e.g. {@code FACE/DM/<ns>/<ns>.hpp}'s aggregate-header
     * convention) — a bare token like {@code "CheckoutGateway_Templates"} is
     * not itself a reachable C++ name at global scope. Use {@link
     * #getModelNamespaceQualified()} for an actual type reference (e.g.
     * {@code ::<qualified>::<Name>}).
     */
    public String getModelNamespace() {
        if (idlModule == null) return "";
        int dot = idlModule.lastIndexOf('.');
        return dot < 0 ? idlModule : idlModule.substring(dot + 1);
    }

    /**
     * Returns the fully-qualified C++ namespace for this type, e.g.
     * {@code "FACE::DM::CheckoutGateway_Templates"} from {@link #idlModule}
     * {@code "FACE.DM.CheckoutGateway_Templates"}. Unlike {@link
     * #getModelNamespace()}'s bare last segment, this is a real, reachable
     * name usable directly in a type reference — no {@code T_<Name>}-wrapper
     * expansion is needed even for a {@code uop:Template} (non-composite)
     * type, because its outer convenience-alias typedef (FACE TS 3.2 §J.8,
     * "Rule: Template") is declared at exactly this qualified scope.
     */
    public String getModelNamespaceQualified() {
        return idlModule == null ? "" : idlModule.replace(".", "::");
    }

    @Override
    public String toString() {
        return "TssTypeData{name='" + name + "', fields=" + fields.size() + "}";
    }
}
