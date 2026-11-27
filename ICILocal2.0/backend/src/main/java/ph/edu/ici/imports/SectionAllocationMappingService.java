package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import ph.edu.ici.imports.ImportMappingService.StoredMapping;

/** Stored section-allocation mappings (source type section_allocation): validation on save, Administrator-only edits. */
@Service
public class SectionAllocationMappingService {
    public static final String SOURCE="section_allocation";
    private static final Set<String> SETTING_KEYS=Set.of("encoding","delimiter","headerRow","layout","ignoredColumns","nativeDates","cleaning","sheet");
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;

    public SectionAllocationMappingService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access){this.db=db;this.json=json;this.access=access;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private void administrator(){if(!access.hasPermission("imports.mappings.manage"))throw error(HttpStatus.FORBIDDEN,"Only administrators can change import mappings.");}
    private void reader(){if(!access.hasPermission("imports.read"))throw error(HttpStatus.FORBIDDEN,"Your account cannot view import mappings.");}
    private String encode(JsonNode node){try{return json.writeValueAsString(node);}catch(JsonProcessingException e){throw error(HttpStatus.BAD_REQUEST,"Invalid JSON.");}}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("Stored mapping is invalid",e);}}
    private StoredMapping row(Map<String,Object> r){return new StoredMapping(((Number)r.get("id")).longValue(),((Number)r.get("version")).intValue(),(String)r.get("name"),(Boolean)r.get("active"),(Boolean)r.get("is_default"),(Boolean)r.get("real_file_validated"),decode(r.get("mapping")),decode(r.get("settings")));}

    @Transactional(readOnly=true) public List<StoredMapping> list(){reader();return db.queryForList("SELECT * FROM import_column_mappings WHERE source_type=:s ORDER BY is_default DESC,lower(name),id",Map.of("s",SOURCE)).stream().map(this::row).toList();}
    @Transactional(readOnly=true) public StoredMapping get(long id){
        reader();var rows=db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s",Map.of("id",id,"s",SOURCE));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Mapping not found.");return row(rows.getFirst());
    }
    /** The requested mapping, or the active default. Used by staging; the caller has already been authorized. */
    @Transactional(readOnly=true) public StoredMapping forUpload(Long id){
        var rows=id==null?db.queryForList("SELECT * FROM import_column_mappings WHERE source_type=:s AND is_default",Map.of("s",SOURCE)):db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s",Map.of("id",id,"s",SOURCE));
        if(rows.isEmpty())throw new ImportFileException(ImportFileException.FileError.UNKNOWN_MAPPING,id==null?"No default section allocation mapping is configured.":"Mapping not found.");
        var mapping=row(rows.getFirst());if(!mapping.active())throw new ImportFileException(ImportFileException.FileError.UNKNOWN_MAPPING,"The selected mapping is not active.");return mapping;
    }
    /** What a batch keeps, so later edits never change staged data. */
    public JsonNode snapshot(StoredMapping m){
        var node=json.createObjectNode();node.put("mappingId",m.id());node.put("mappingVersion",m.version());node.put("name",m.name());node.put("realFileValidated",m.realFileValidated());node.set("mapping",m.mapping());node.set("settings",m.settings());return node;
    }

    /** @return human-readable problems; empty when the mapping and settings can be saved. */
    @Transactional(readOnly=true) public List<String> validate(JsonNode mapping,JsonNode settings){
        List<String> problems=new ArrayList<>();
        if(mapping==null||!mapping.isObject()){problems.add("mapping must be an object.");return problems;}
        if(settings==null||!settings.isObject()){problems.add("settings must be an object.");return problems;}
        mapping.fieldNames().forEachRemaining(k->{if(!Set.of("fields","valueMaps").contains(k))problems.add("Unknown mapping key '"+k+"'.");});
        Set<String> aliasKeys=new HashSet<>();
        JsonNode fields=mapping.path("fields");
        if(!fields.isObject())problems.add("mapping.fields must be an object.");
        else{
            Map<String,String> owner=new HashMap<>();boolean identifier=false;
            fields.fieldNames().forEachRemaining(k->{if(!SectionAllocationMapping.FIELDS.contains(k))problems.add("Unknown field '"+k+"'.");});
            for(String name:SectionAllocationMapping.FIELDS){
                JsonNode spec=fields.path(name);
                if(spec.isMissingNode()){if(name.equals("section"))problems.add("Required field 'section' is missing.");continue;}
                if(!spec.isObject()){problems.add("Field '"+name+"' must be an object.");continue;}
                spec.fieldNames().forEachRemaining(k->{if(!Set.of("required","aliases").contains(k))problems.add("Unknown key '"+k+"' in field '"+name+"'.");});
                if(spec.has("required")&&!spec.get("required").isBoolean())problems.add("Field '"+name+"'.required must be true or false.");
                if(name.equals("section")&&!spec.path("required").asBoolean(false))problems.add("Field 'section' cannot be made optional.");
                JsonNode aliases=spec.path("aliases");int usable=0;
                if(!aliases.isArray()||aliases.size()>20)problems.add("Field '"+name+"' needs an aliases array of at most 20 entries.");
                else for(JsonNode a:aliases){
                    String key=a.isTextual()&&a.asText().length()<=100?ImportText.header(a.asText()):"";
                    if(key.isEmpty()){problems.add("Field '"+name+"' has an empty, non-text or overlong alias.");continue;}
                    String previous=owner.putIfAbsent(key,name);if(previous!=null&&!previous.equals(name))problems.add("Alias '"+key+"' is used by both '"+previous+"' and '"+name+"'.");
                    aliasKeys.add(key);usable++;
                }
                if(usable==0)problems.add("Field '"+name+"' needs at least one alias.");
                if(usable>0&&(name.equals("studentNumber")||name.equals("lrn")))identifier=true;
            }
            if(!identifier)problems.add("At least one of studentNumber or lrn must be mapped, because an allocation row must name its student.");
        }
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
        settings.fieldNames().forEachRemaining(k->{if(!SETTING_KEYS.contains(k))problems.add("Unknown setting '"+k+"'.");});
        if(settings.has("layout")&&!SectionAllocationMapping.LAYOUT.equals(settings.path("layout").asText("")))problems.add("layout must be '"+SectionAllocationMapping.LAYOUT+"' (other layouts are not supported).");
        if(!ImportMappingService.ENCODINGS.contains(settings.path("encoding").asText("")))problems.add("encoding must be one of "+ImportMappingService.ENCODINGS+".");
        if(!ImportMappingService.DELIMITERS.contains(settings.path("delimiter").asText("")))problems.add("delimiter must be a comma, semicolon or tab.");
        if(!settings.path("headerRow").isInt()||settings.get("headerRow").asInt()<1||settings.get("headerRow").asInt()>20)problems.add("headerRow must be a whole number from 1 to 20.");
        JsonNode ignored=settings.path("ignoredColumns");
        if(!ignored.isArray()||ignored.size()>100)problems.add("ignoredColumns must be an array of at most 100 header names.");
        else{
            Set<String> seen=new HashSet<>();
            for(JsonNode h:ignored){
                String key=h.isTextual()&&h.asText().length()<=200?ImportText.header(h.asText()):"";
                if(key.isEmpty())problems.add("ignoredColumns has an empty, non-text or overlong header name.");
                else if(!seen.add(key))problems.add("ignoredColumns lists '"+key+"' more than once.");
                else if(aliasKeys.contains(key))problems.add("'"+key+"' is both an ignored column and a field alias; a column can have only one classification.");
            }
        }
        CleaningProfile.validate(settings,SectionAllocationMapping.FIELDS,maps,problems);
        return problems;
    }
    private static String noun(String group){return switch(group){case "program"->"program code";case "term"->"term code";default->"academic year label";};}
    /** Lower-case valid targets for a reference-backed group, from existing rows only; null for section names (they belong to terms and are checked at staging). */
    private Set<String> targets(String group){
        String sql=switch(group){case "program"->"SELECT code FROM programs";case "term"->"SELECT DISTINCT code FROM academic_terms";case "schoolYear"->"SELECT label FROM academic_years";default->null;};
        if(sql==null)return null;
        Set<String> values=new HashSet<>();db.getJdbcTemplate().queryForList(sql,String.class).forEach(c->values.add(c.toLowerCase(Locale.ROOT)));return values;
    }

    private void checkValid(MappingInput in){
        if(in==null||in.name()==null||in.name().isBlank()||in.name().trim().length()>120)throw error(HttpStatus.BAD_REQUEST,"Name is required (120 characters at most).");
        var problems=validate(in.mapping(),in.settings());if(!problems.isEmpty())throw error(HttpStatus.BAD_REQUEST,"Mapping is invalid: "+String.join(" ",problems));
    }
    @Transactional public StoredMapping create(MappingInput in){
        administrator();checkValid(in);actor();
        if(in.isDefault()&&!in.active())throw error(HttpStatus.BAD_REQUEST,"A default mapping must be active.");
        try{
            if(in.isDefault())db.update("UPDATE import_column_mappings SET is_default=false WHERE source_type=:s AND is_default",Map.of("s",SOURCE));
            long id=db.queryForObject("INSERT INTO import_column_mappings(source_type,name,mapping,settings,is_default,active,real_file_validated) VALUES (:s,:name,CAST(:mapping AS jsonb),CAST(:settings AS jsonb),:def,:active,:real) RETURNING id",
                Map.of("s",SOURCE,"name",in.name().trim(),"mapping",encode(in.mapping()),"settings",encode(in.settings()),"def",in.isDefault(),"active",in.active(),"real",in.realFileValidated()),Long.class);
            return get(id);
        }catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"A mapping with this name already exists.");}
    }
    @Transactional public StoredMapping update(long id,int version,MappingInput in){
        administrator();checkValid(in);actor();
        var rows=db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s FOR UPDATE",Map.of("id",id,"s",SOURCE));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Mapping not found.");var current=rows.getFirst();
        if(((Number)current.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"Mapping changed. Reload before saving.");
        if(Boolean.TRUE.equals(current.get("is_default"))&&(!in.isDefault()||!in.active()))throw error(HttpStatus.CONFLICT,"This is the default mapping; make another mapping the default instead of unsetting or deactivating it.");
        if(in.isDefault()&&!in.active())throw error(HttpStatus.BAD_REQUEST,"A default mapping must be active.");
        if(!in.active()&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM import_batches WHERE mapping_id=:id AND status='validated')",Map.of("id",id),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This mapping is used by a batch that has not been committed.");
        try{
            if(in.isDefault())db.update("UPDATE import_column_mappings SET is_default=false WHERE source_type=:s AND is_default AND id<>:id",Map.of("s",SOURCE,"id",id));
            db.update("UPDATE import_column_mappings SET name=:name,mapping=CAST(:mapping AS jsonb),settings=CAST(:settings AS jsonb),is_default=:def,active=:active,real_file_validated=:real,version=version+1 WHERE id=:id",
                Map.of("id",id,"name",in.name().trim(),"mapping",encode(in.mapping()),"settings",encode(in.settings()),"def",in.isDefault(),"active",in.active(),"real",in.realFileValidated()));
            return get(id);
        }catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"A mapping with this name already exists.");}
    }
}
