package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

/** Parsed form of a stored document-log mapping (ICI2-39 type B) and its settings. Parsing is tolerant; {@link DocumentLogMappingService} enforces strictness on save. */
public record DocumentLogMapping(Map<String,ImportMapping.FieldSpec> fields,Map<String,Map<String,String>> valueMaps,Settings settings,CleaningProfile cleaning) {
    /** @param sheet the .xlsx sheet to read, or null; @param dateFormats patterns for text dates (native date cells need none) */
    public record Settings(String encoding,char delimiter,int headerRow,Set<String> ignoredColumns,String sheet,List<String> dateFormats) {}

    public static final String LAYOUT="long";
    public static final List<String> FIELDS=List.of("studentNumber","lrn","applicationNumber","lastName","firstName","document","status","date","note","term","schoolYear");
    /** document: legacy label to requirement_types.code; status: legacy label to one of {@link #STATES}; term and school year as in type A. */
    public static final List<String> VALUE_MAPPED=List.of("document","status","term","schoolYear");
    /** The checklist states of RequirementService; a file can only ever express one of these. */
    public static final List<String> STATES=List.of("submitted","missing","pending","invalid");
    /** Text date patterns a mapping may list. Every one has a four-digit year; ambiguity between listed patterns is refused at staging. */
    public static final List<String> DATE_FORMATS=List.of("yyyy-MM-dd","yyyy/MM/dd","MM/dd/yyyy","dd/MM/yyyy","M/d/yyyy","d/M/yyyy","MM-dd-yyyy","dd-MM-yyyy");

    public static DocumentLogMapping parse(JsonNode mapping,JsonNode settings){
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
        List<String> formats=new ArrayList<>();settings.path("dateFormats").forEach(f->{if(f.isTextual()&&DATE_FORMATS.contains(f.asText()))formats.add(f.asText());});
        if(formats.isEmpty())formats.add("yyyy-MM-dd");
        String delimiter=settings.path("delimiter").asText(",");
        String sheet=settings.path("sheet").isTextual()&&!settings.path("sheet").asText().isBlank()?settings.path("sheet").asText().trim():null;
        return new DocumentLogMapping(fields,maps,new Settings(settings.path("encoding").asText("UTF-8"),delimiter.isEmpty()?',':delimiter.charAt(0),settings.path("headerRow").asInt(1),Set.copyOf(ignored),sheet,List.copyOf(formats)),CleaningProfile.parse(settings));
    }
    ImportMapping.Settings csv(){return new ImportMapping.Settings(settings.encoding(),settings.delimiter(),settings.headerRow(),List.of(),false);}

    /** Every date the text can be under the configured patterns (strict: no rollover, four-digit years). More than one is ambiguous; none is invalid. */
    Set<LocalDate> dates(String text){
        Set<LocalDate> out=new LinkedHashSet<>();
        for(String p:settings.dateFormats()){
            try{out.add(LocalDate.parse(text,DateTimeFormatter.ofPattern(p.replace("yyyy","uuuu"),Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)));}catch(DateTimeParseException ignored){}
        }
        return out;
    }
}
