package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Parsed form of a stored section-allocation mapping (ICI2-39 type A) and its settings. Parsing is tolerant; {@link SectionAllocationMappingService} enforces strictness on save. */
public record SectionAllocationMapping(Map<String,ImportMapping.FieldSpec> fields,Map<String,Map<String,String>> valueMaps,Settings settings,CleaningProfile cleaning) {
    /** @param sheet the .xlsx sheet to read (settings.sheet), or null when the workbook must have exactly one sheet */
    public record Settings(String encoding,char delimiter,int headerRow,Set<String> ignoredColumns,String sheet) {}

    public static final String LAYOUT="student_list";
    public static final List<String> FIELDS=List.of("studentNumber","lrn","lastName","firstName","section","gradeLevel","program","term","schoolYear");
    /** Value-map groups: legacy section label to section name; program, term and school year labels to existing codes and labels. */
    public static final List<String> VALUE_MAPPED=List.of("section","program","term","schoolYear");

    public static SectionAllocationMapping parse(JsonNode mapping,JsonNode settings){
        Map<String,ImportMapping.FieldSpec> fields=new LinkedHashMap<>();
        for(String name:FIELDS){
            JsonNode node=mapping.path("fields").path(name);if(!node.isObject())continue;
            List<String> aliases=new ArrayList<>();node.path("aliases").forEach(a->{if(a.isTextual())aliases.add(a.asText());});
            fields.put(name,new ImportMapping.FieldSpec(node.path("required").asBoolean(false),List.copyOf(aliases)));
        }
        Map<String,Map<String,String>> maps=new LinkedHashMap<>();
        for(String name:VALUE_MAPPED){
            Map<String,String> entries=new LinkedHashMap<>();
            mapping.path("valueMaps").path(name).fields().forEachRemaining(e->{if(e.getValue().isTextual())entries.put(ImportText.value(e.getKey()),e.getValue().asText().trim());});
            maps.put(name,entries);
        }
        Set<String> ignored=new LinkedHashSet<>();settings.path("ignoredColumns").forEach(l->{if(l.isTextual())ignored.add(ImportText.header(l.asText()));});
        String delimiter=settings.path("delimiter").asText(",");
        return new SectionAllocationMapping(fields,maps,new Settings(settings.path("encoding").asText("UTF-8"),delimiter.isEmpty()?',':delimiter.charAt(0),settings.path("headerRow").asInt(1),Set.copyOf(ignored),
            settings.path("sheet").isTextual()&&!settings.path("sheet").asText().isBlank()?settings.path("sheet").asText().trim():null),CleaningProfile.parse(settings));
    }
    /** Settings in the shape {@link CsvParser} reads; a spreadsheet reader takes its own settings (the sheet name) from the stored settings instead. */
    ImportMapping.Settings csv(){return new ImportMapping.Settings(settings.encoding(),settings.delimiter(),settings.headerRow(),List.of(),false);}
}
