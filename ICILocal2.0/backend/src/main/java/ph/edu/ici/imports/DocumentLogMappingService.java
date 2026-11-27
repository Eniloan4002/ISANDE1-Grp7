package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.ici.auth.AccessPolicy;

/** Stored document-log mappings (source type document_log): validation on save, Administrator-only edits ({@link TypedMappingService}). */
@Service
public class DocumentLogMappingService extends TypedMappingService {
    public static final String SOURCE="document_log";
    private static final Set<String> SETTING_KEYS=Set.of("encoding","delimiter","headerRow","layout","ignoredColumns","nativeDates","cleaning","sheet","dateFormats","repeatedEntries");

    public DocumentLogMappingService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access){super(db,json,access,SOURCE,"document log");}

    @Override @Transactional(readOnly=true) public List<String> validate(JsonNode mapping,JsonNode settings){
        List<String> problems=new ArrayList<>();
        if(mapping==null||!mapping.isObject()){problems.add("mapping must be an object.");return problems;}
        if(settings==null||!settings.isObject()){problems.add("settings must be an object.");return problems;}
        mapping.fieldNames().forEachRemaining(k->{if(!Set.of("fields","valueMaps").contains(k))problems.add("Unknown mapping key '"+k+"'.");});
        Set<String> aliasKeys=fields(mapping,DocumentLogMapping.FIELDS,Set.of("document","status"),problems);
        if(mapping.path("fields").isObject()&&!mapped(mapping,"studentNumber")&&!mapped(mapping,"lrn")&&!mapped(mapping,"applicationNumber"))
            problems.add("At least one of studentNumber, lrn or applicationNumber must be mapped, because a document row must name its application.");
        JsonNode maps=mapping.path("valueMaps");
        if(!maps.isMissingNode()&&!maps.isObject())problems.add("mapping.valueMaps must be an object.");
        else maps.fieldNames().forEachRemaining(group->{
            if(!DocumentLogMapping.VALUE_MAPPED.contains(group)){problems.add("Unknown value map '"+group+"'.");return;}
            JsonNode entries=maps.get(group);if(!entries.isObject()){problems.add("valueMaps."+group+" must be an object.");return;}
            Set<String> keys=new HashSet<>();
            Set<String> codes="document".equals(group)?new HashSet<>(db.getJdbcTemplate().queryForList("SELECT code FROM requirement_types",String.class)):null;
            Set<String> lower=switch(group){case "term"->existing("SELECT DISTINCT code FROM academic_terms");case "schoolYear"->existing("SELECT label FROM academic_years");default->null;};
            entries.fields().forEachRemaining(e->{
                String key=ImportText.value(e.getKey());
                if(key.isEmpty()||key.length()>100)problems.add("valueMaps."+group+" has an empty or overlong source value.");
                else if(!keys.add(key))problems.add("valueMaps."+group+" lists '"+key+"' more than once.");
                String target=e.getValue().isTextual()?e.getValue().asText().trim():"";
                boolean ok=switch(group){
                    case "document"->codes.contains(target);                    // exact case, as the requirement API takes codes
                    case "status"->DocumentLogMapping.STATES.contains(target);
                    default->lower.contains(target.toLowerCase(Locale.ROOT));
                };
                if(!ok)problems.add("valueMaps."+group+" maps '"+ImportText.excerpt(e.getKey())+"' to '"+ImportText.excerpt(target)+"', which is not "+noun(group)+". Nothing is created by the import.");
            });
        });
        settings(settings,SETTING_KEYS,DocumentLogMapping.LAYOUT,aliasKeys,DocumentLogMapping.FIELDS,maps,problems);
        if(settings.has("repeatedEntries")&&!"reject".equals(settings.path("repeatedEntries").asText("")))problems.add("repeatedEntries must be 'reject' (a document listed twice for one application is refused).");
        if(settings.has("dateFormats")){
            JsonNode f=settings.get("dateFormats");
            if(!f.isArray()||f.isEmpty()||f.size()>5)problems.add("dateFormats must be an array of 1 to 5 patterns.");
            else for(JsonNode p:f)if(!p.isTextual()||!DocumentLogMapping.DATE_FORMATS.contains(p.asText()))problems.add("dateFormats may only list "+DocumentLogMapping.DATE_FORMATS+".");
        }
        return problems;
    }
    private static String noun(String group){
        return switch(group){case "document"->"an existing requirement code (exact case)";case "status"->"one of "+DocumentLogMapping.STATES;case "term"->"an existing term code";default->"an existing academic year label";};
    }
}
