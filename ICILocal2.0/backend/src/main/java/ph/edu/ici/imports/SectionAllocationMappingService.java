package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.ici.auth.AccessPolicy;

/** Stored section-allocation mappings (source type section_allocation): validation on save, Administrator-only edits ({@link TypedMappingService}). */
@Service
public class SectionAllocationMappingService extends TypedMappingService {
    public static final String SOURCE="section_allocation";
    private static final Set<String> SETTING_KEYS=Set.of("encoding","delimiter","headerRow","layout","ignoredColumns","nativeDates","cleaning","sheet");

    public SectionAllocationMappingService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access){super(db,json,access,SOURCE,"section allocation");}

    @Override @Transactional(readOnly=true) public List<String> validate(JsonNode mapping,JsonNode settings){
        List<String> problems=new ArrayList<>();
        if(mapping==null||!mapping.isObject()){problems.add("mapping must be an object.");return problems;}
        if(settings==null||!settings.isObject()){problems.add("settings must be an object.");return problems;}
        mapping.fieldNames().forEachRemaining(k->{if(!Set.of("fields","valueMaps").contains(k))problems.add("Unknown mapping key '"+k+"'.");});
        Set<String> aliasKeys=fields(mapping,SectionAllocationMapping.FIELDS,Set.of("section"),problems);
        if(mapping.path("fields").isObject()&&!mapped(mapping,"studentNumber")&&!mapped(mapping,"lrn"))
            problems.add("At least one of studentNumber or lrn must be mapped, because an allocation row must name its student.");
        JsonNode maps=mapping.path("valueMaps");
        if(!maps.isMissingNode()&&!maps.isObject())problems.add("mapping.valueMaps must be an object.");
        else maps.fieldNames().forEachRemaining(group->{
            if(!SectionAllocationMapping.VALUE_MAPPED.contains(group)){problems.add("Unknown value map '"+group+"'.");return;}
            JsonNode entries=maps.get(group);if(!entries.isObject()){problems.add("valueMaps."+group+" must be an object.");return;}
            Set<String> keys=new HashSet<>();Set<String> valid=targets(group);
            entries.fields().forEachRemaining(e->{
                String key=ImportText.value(e.getKey());
                if(key.isEmpty()||key.length()>100)problems.add("valueMaps."+group+" has an empty or overlong source value.");
                else if(!keys.add(key))problems.add("valueMaps."+group+" lists '"+key+"' more than once.");
                String target=e.getValue().isTextual()?e.getValue().asText().trim():"";
                if(valid==null){if(target.isEmpty()||target.length()>150||ImportText.hasControl(target))problems.add("valueMaps.section targets must be section names of 1 to 150 characters.");}
                else if(!valid.contains(target.toLowerCase(Locale.ROOT)))problems.add("valueMaps."+group+" maps '"+ImportText.excerpt(e.getKey())+"' to '"+ImportText.excerpt(target)+"', which is not an existing "+noun(group)+". Nothing is created by the import.");
            });
        });
        settings(settings,SETTING_KEYS,SectionAllocationMapping.LAYOUT,aliasKeys,SectionAllocationMapping.FIELDS,maps,problems);
        return problems;
    }
    private static String noun(String group){return switch(group){case "program"->"program code";case "term"->"term code";default->"academic year label";};}
    /** Lower-case valid targets for a reference-backed group, from existing rows only; null for section names (they belong to terms and are checked at staging). */
    private Set<String> targets(String group){
        return switch(group){
            case "program"->existing("SELECT code FROM programs");case "term"->existing("SELECT DISTINCT code FROM academic_terms");case "schoolYear"->existing("SELECT label FROM academic_years");default->null;
        };
    }
}
