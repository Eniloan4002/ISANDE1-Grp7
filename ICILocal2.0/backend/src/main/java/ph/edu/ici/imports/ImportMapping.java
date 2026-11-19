package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Parsed form of a stored mapping plus its parse settings. Parsing is tolerant; {@link ImportMappingService} enforces strictness on save. */
public record ImportMapping(Map<String,FieldSpec> fields,Map<String,Map<String,String>> valueMaps,Settings settings,CleaningProfile cleaning) {
    public record FieldSpec(boolean required,List<String> aliases) {}
    public record Settings(String encoding,char delimiter,int headerRow,List<String> dateFormats,boolean normalizeNameCase) {}

    public static final List<String> FIELDS=List.of("studentNumber","lrn","lastName","firstName","middleName","birthDate","sex","previousSchool","voucher","specialization","campus");
    /** Fields without which no student can be created; a mapping cannot make them optional. */
    public static final Set<String> MANDATORY=Set.of("studentNumber","lastName","firstName","birthDate","voucher","specialization");
    public static final List<String> VALUE_MAPPED=List.of("voucher","specialization","campus","sex");

    public static ImportMapping parse(JsonNode mapping,JsonNode settings){
        Map<String,FieldSpec> fields=new LinkedHashMap<>();
        JsonNode f=mapping.path("fields");
        for(String name:FIELDS){
            JsonNode node=f.path(name);if(!node.isObject())continue;
            List<String> aliases=new ArrayList<>();node.path("aliases").forEach(a->{if(a.isTextual())aliases.add(a.asText());});
            fields.put(name,new FieldSpec(node.path("required").asBoolean(false),List.copyOf(aliases)));
        }
        Map<String,Map<String,String>> maps=new LinkedHashMap<>();
        for(String name:VALUE_MAPPED){
            Map<String,String> entries=new LinkedHashMap<>();
            mapping.path("valueMaps").path(name).fields().forEachRemaining(e->{if(e.getValue().isTextual())entries.put(ImportText.value(e.getKey()),e.getValue().asText().trim());});
            maps.put(name,entries);
        }
        String delimiter=settings.path("delimiter").asText(",");
        List<String> dates=new ArrayList<>();settings.path("dateFormats").forEach(d->dates.add(d.asText()));
        if(!settings.has("dateFormats"))dates.addAll(List.of("yyyy-MM-dd","MM/dd/yyyy"));
        return new ImportMapping(fields,maps,new Settings(settings.path("encoding").asText("UTF-8"),delimiter.isEmpty()?',':delimiter.charAt(0),
            settings.path("headerRow").asInt(1),List.copyOf(dates),settings.path("normalizeNameCase").asBoolean(false)),CleaningProfile.parse(settings));
    }
}
