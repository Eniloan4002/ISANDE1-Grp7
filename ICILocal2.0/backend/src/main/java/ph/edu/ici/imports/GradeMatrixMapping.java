package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Parsed form of a stored grade-matrix mapping and its settings. Parsing is tolerant; {@link GradeMatrixMappingService} enforces strictness on save. */
public record GradeMatrixMapping(Map<String,ImportMapping.FieldSpec> fields,String layout,String separator,Set<String> reportedFinalLabels,
                                 Map<String,Map<String,String>> valueMaps,Settings settings) {
    public record Settings(String encoding,char delimiter,int headerRow,char decimalSeparator,Set<String> ignoredColumns) {}

    public static final String LAYOUT="subject_period_flat";
    public static final List<String> FIELDS=List.of("studentNumber","lrn","lastName","firstName","schoolYear","term","gradeLevel","section");
    public static final List<String> VALUE_MAPPED=List.of("subject","period","term","schoolYear","mark");
    public static final Set<String> MARKS=Set.of("INC","NG","DRP");

    public static GradeMatrixMapping parse(JsonNode mapping,JsonNode settings){
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
        Set<String> finals=new LinkedHashSet<>();mapping.path("grid").path("reportedFinalLabels").forEach(l->{if(l.isTextual())finals.add(ImportText.value(l.asText()));});
        Set<String> ignored=new LinkedHashSet<>();settings.path("ignoredColumns").forEach(l->{if(l.isTextual())ignored.add(ImportText.header(l.asText()));});
        String delimiter=settings.path("delimiter").asText(",");String decimal=settings.path("decimalSeparator").asText(".");
        return new GradeMatrixMapping(fields,mapping.path("grid").path("layout").asText(LAYOUT),mapping.path("grid").path("separator").asText(" - "),Set.copyOf(finals),maps,
            new Settings(settings.path("encoding").asText("UTF-8"),delimiter.isEmpty()?',':delimiter.charAt(0),settings.path("headerRow").asInt(1),decimal.isEmpty()?'.':decimal.charAt(0),Set.copyOf(ignored)));
    }
    /** Settings in the shape {@link CsvParser} reads (dates are not used by the grade matrix). */
    ImportMapping.Settings csv(){return new ImportMapping.Settings(settings.encoding(),settings.delimiter(),settings.headerRow(),List.of(),false);}
}
