package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * The opt-in cleaning rules of one stored mapping (settings.cleaning plus settings.nativeDates). Every key is optional and an absent
 * key means the behavior of ICI2-37/38, so mappings stored before ICI2-39 keep producing identical output.
 */
public record CleaningProfile(boolean unicodeSpaces,boolean stripInvisible,boolean extendedDateFormats,boolean lrnStripSeparators,
                              Set<String> blankValues,Map<String,Set<String>> fieldBlankValues,Set<String> nameParticles,Map<String,Integer> padNumeric,
                              boolean nativeDatesAccepted) {
    public static final CleaningProfile DEFAULT=new CleaningProfile(false,false,false,false,Set.of(),Map.of(),Set.of(),Map.of(),true);
    static final Set<String> KEYS=Set.of("unicodeSpaces","stripInvisible","extendedDateFormats","lrnStripSeparators","blankValues","fieldBlankValues","nameParticles","padNumeric");
    /** Identifier fields: numeric cells are rendered as plain digits, and only these may be zero-padded. */
    static final Set<String> ID_FIELDS=Set.of("studentNumber","lrn");

    /** True when {@code value} (any raw text) is a configured placeholder for {@code field}; compared like value-map keys. */
    boolean placeholder(String field,String value){
        String key=ImportText.value(value);
        return blankValues.contains(key)||fieldBlankValues.getOrDefault(field,Set.of()).contains(key);
    }

    /** Tolerant parse; {@link #validate} enforces strictness on save. */
    public static CleaningProfile parse(JsonNode settings){
        JsonNode c=settings.path("cleaning");
        Map<String,Set<String>> perField=new LinkedHashMap<>();
        c.path("fieldBlankValues").fields().forEachRemaining(e->perField.put(e.getKey(),normalized(e.getValue())));
        Map<String,Integer> pad=new LinkedHashMap<>();
        c.path("padNumeric").fields().forEachRemaining(e->{if(e.getValue().isInt())pad.put(e.getKey(),e.getValue().asInt());});
        Set<String> particles=new LinkedHashSet<>();c.path("nameParticles").forEach(p->{if(p.isTextual())particles.add(p.asText().trim().toLowerCase(Locale.ROOT));});
        return new CleaningProfile(c.path("unicodeSpaces").asBoolean(false),c.path("stripInvisible").asBoolean(false),c.path("extendedDateFormats").asBoolean(false),
            c.path("lrnStripSeparators").asBoolean(false),normalized(c.path("blankValues")),Map.copyOf(perField),Set.copyOf(particles),Map.copyOf(pad),
            !"reject".equals(settings.path("nativeDates").asText("accept")));
    }
    private static Set<String> normalized(JsonNode array){
        Set<String> out=new LinkedHashSet<>();array.forEach(v->{if(v.isTextual())out.add(ImportText.value(v.asText()));});return Set.copyOf(out);
    }

    /**
     * Save-time validation of the ICI2-39 settings keys, shared by every mapping service. Appends every problem found.
     * @param fields the field names of the source type (for per-field keys)
     * @param valueMaps the mapping's value maps, so a placeholder can never also be a translation key
     */
    static void validate(JsonNode settings,Collection<String> fields,JsonNode valueMaps,List<String> problems){
        if(settings.has("nativeDates")&&!List.of("accept","reject").contains(settings.path("nativeDates").asText("")))problems.add("nativeDates must be 'accept' or 'reject'.");
        if(settings.has("sheet")){
            JsonNode s=settings.get("sheet");
            if(!s.isTextual()||s.asText().isBlank()||s.asText().trim().length()>255||ImportText.hasControl(s.asText()))problems.add("sheet must be a sheet name of 1 to 255 characters.");
        }
        if(!settings.has("cleaning"))return;
        JsonNode c=settings.get("cleaning");
        if(!c.isObject()){problems.add("cleaning must be an object.");return;}
        c.fieldNames().forEachRemaining(k->{if(!KEYS.contains(k))problems.add("Unknown cleaning key '"+k+"'.");});
        for(String flag:List.of("unicodeSpaces","stripInvisible","extendedDateFormats","lrnStripSeparators"))
            if(c.has(flag)&&!c.get(flag).isBoolean())problems.add("cleaning."+flag+" must be true or false.");
        Set<String> mapKeys=new HashSet<>();Map<String,Set<String>> keysByGroup=new HashMap<>();
        if(valueMaps!=null&&valueMaps.isObject())valueMaps.fields().forEachRemaining(g->{
            Set<String> keys=new HashSet<>();if(g.getValue().isObject())g.getValue().fieldNames().forEachRemaining(k->keys.add(ImportText.value(k)));
            keysByGroup.put(g.getKey(),keys);mapKeys.addAll(keys);
        });
        if(c.has("blankValues"))list(c.get("blankValues"),"cleaning.blankValues",problems).forEach(v->{if(mapKeys.contains(v))problems.add("Placeholder '"+ImportText.excerpt(v)+"' is also a key of a value map; a value cannot be both a placeholder and a translation.");});
        if(c.has("fieldBlankValues")){
            JsonNode f=c.get("fieldBlankValues");
            if(!f.isObject())problems.add("cleaning.fieldBlankValues must be an object of field name to value list.");
            else f.fields().forEachRemaining(e->{
                if(!fields.contains(e.getKey())){problems.add("cleaning.fieldBlankValues has unknown field '"+ImportText.excerpt(e.getKey())+"'.");return;}
                list(e.getValue(),"cleaning.fieldBlankValues."+e.getKey(),problems).forEach(v->{
                    if(keysByGroup.getOrDefault(e.getKey(),Set.of()).contains(v))problems.add("Placeholder '"+ImportText.excerpt(v)+"' is also a key of valueMaps."+e.getKey()+".");
                });
            });
        }
        if(c.has("nameParticles")){
            JsonNode p=c.get("nameParticles");
            if(!p.isArray()||p.size()>50)problems.add("cleaning.nameParticles must list at most 50 particles.");
            else for(JsonNode v:p)if(!v.isTextual()||!v.asText().trim().matches("\\p{L}{1,30}"))problems.add("cleaning.nameParticles entries must be 1 to 30 letters.");
        }
        if(c.has("padNumeric")){
            JsonNode p=c.get("padNumeric");
            if(!p.isObject())problems.add("cleaning.padNumeric must be an object of field name to width.");
            else p.fields().forEachRemaining(e->{
                if(!ID_FIELDS.contains(e.getKey())||!fields.contains(e.getKey()))problems.add("cleaning.padNumeric can only name an identifier field of this import (studentNumber, lrn).");
                else if(!e.getValue().isInt()||e.getValue().asInt()<1||e.getValue().asInt()>40)problems.add("cleaning.padNumeric."+e.getKey()+" must be a whole number from 1 to 40.");
            });
        }
    }
    /** Normalized values of a placeholder list, or an empty list (after recording a problem) when it is malformed. */
    private static List<String> list(JsonNode node,String name,List<String> problems){
        if(!node.isArray()||node.size()>50){problems.add(name+" must list at most 50 values.");return List.of();}
        List<String> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode v:node){
            String key=v.isTextual()&&v.asText().length()<=100?ImportText.value(v.asText()):"";
            if(key.isEmpty()){problems.add(name+" has an empty, non-text or overlong value.");continue;}
            if(!seen.add(key))problems.add(name+" lists '"+ImportText.excerpt(key)+"' more than once.");
            out.add(key);
        }
        return out;
    }
}
