package org.helex.loinc.delta;

import java.util.List;
import java.util.Map;

/**
 * The columns that identify a row in each LOINC file, so a changed row is reported as MODIFIED
 * rather than as one REMOVED plus one ADDED.
 *
 * <p>Each key was checked for uniqueness against the 2.78 and 2.82 releases. A few files still repeat
 * a key (ComponentHierarchyBySystem and LoincRsnaRadiologyPlaybook even hold identical rows); the
 * diff numbers repeated keys by occurrence, so those rows are still matched one to one.
 *
 * <p>A file not listed here is compared as whole rows: any change shows as REMOVED plus ADDED.
 */
final class FileKeys {

    private static final List<String> LOINC_NUM = List.of("LOINC_NUM");
    private static final List<String> PART_LINK = List.of("LoincNumber", "PartNumber", "LinkTypeName", "Property");
    private static final List<String> ANSWER_LIST = List.of("AnswerListId", "AnswerStringId");
    private static final List<String> ANSWER_LIST_LINK =
            List.of("LoincNumber", "AnswerListId", "AnswerListLinkType", "ApplicableContext");

    /** Keyed by file name; the directory does not matter (PanelsAndForms repeats several files). */
    private static final Map<String, List<String>> BY_FILE_NAME = Map.ofEntries(
            Map.entry("Loinc.csv", LOINC_NUM),
            Map.entry("LoincTableCore.csv", LOINC_NUM),
            Map.entry("MapTo.csv", List.of("LOINC", "MAP_TO")),
            Map.entry("SourceOrganization.csv", List.of("ID")),
            Map.entry("AnswerList.csv", ANSWER_LIST),
            Map.entry("LoincAnswerListLink.csv", ANSWER_LIST_LINK),
            Map.entry("ComponentHierarchyBySystem.csv", List.of("PATH_TO_ROOT", "CODE")),
            Map.entry("ConsumerName.csv", List.of("LoincNumber")),
            Map.entry("DocumentOntology.csv", List.of("LoincNumber", "PartNumber", "PartTypeName")),
            Map.entry("Group.csv", List.of("GroupId")),
            Map.entry("GroupAttributes.csv", List.of("GroupId", "Type")),
            Map.entry("GroupLoincTerms.csv", List.of("GroupId", "LoincNumber")),
            Map.entry("ParentGroup.csv", List.of("ParentGroupId")),
            Map.entry("ParentGroupAttributes.csv", List.of("ParentGroupId", "Type")),
            Map.entry("ImagingDocumentCodes.csv", LOINC_NUM),
            Map.entry("LinguisticVariants.csv", List.of("ID")),
            Map.entry("LoincIeeeMedicalDeviceCodeMappingTable.csv", List.of("LOINC_NUM", "IEEE_REFID")),
            Map.entry("LoincRsnaRadiologyPlaybook.csv",
                    List.of("LoincNumber", "PartNumber", "PartTypeName", "PartSequenceOrder", "RID", "RPID")),
            Map.entry("LoincUniversalLabOrdersValueSet.csv", LOINC_NUM),
            // ID is a row id that LOINC keeps stable across releases (90,299 of 90,386 shared ids
            // between 2.78 and 2.82 point at the same parent and child).
            Map.entry("PanelsAndForms.csv", List.of("ParentLoinc", "ID")),
            Map.entry("LoincPartLink_Primary.csv", PART_LINK),
            Map.entry("LoincPartLink_Supplementary.csv", PART_LINK),
            Map.entry("Part.csv", List.of("PartNumber")),
            Map.entry("PartRelatedCodeMapping.csv", List.of("PartNumber", "ExtCodeSystem", "ExtCodeId")));

    private FileKeys() {
    }

    /**
     * The key columns for a zip entry, or an empty list to compare whole rows. The per-language
     * files ({@code nlNL22LinguisticVariant.csv}, ...) are keyed by LOINC_NUM.
     */
    static List<String> forPath(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith("LinguisticVariant.csv")) {
            return LOINC_NUM;
        }
        return BY_FILE_NAME.getOrDefault(name, List.of());
    }
}
