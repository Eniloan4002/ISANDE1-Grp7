package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import ph.edu.ici.imports.ImportMappingService.StoredMapping;

/**
 * Storage of the mappings of one ICI2-39 import type (section allocation, document log): list, read, the upload default, snapshots and
 * Administrator-only create and update. Each type supplies only its save validation. Moved unchanged out of the section allocation mapping
 * service so the document log type does not add a second copy.
 */
abstract class TypedMappingService {
    protected final NamedParameterJdbcTemplate db;protected final ObjectMapper json;protected final AccessPolicy access;private final String source,noun;

    /** @param noun e.g. "section allocation", used in the "no default mapping" message */
    TypedMappingService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,String source,String noun){this.db=db;this.json=json;this.access=access;this.source=source;this.noun=noun;}

    /** @return human-readable problems; empty when the mapping and settings can be saved. */
    public abstract List<String> validate(JsonNode mapping,JsonNode settings);

    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    protected ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private void administrator(){if(!access.hasPermission("imports.mappings.manage"))throw error(HttpStatus.FORBIDDEN,"Only administrators can change import mappings.");}
    private void reader(){if(!access.hasPermission("imports.read"))throw error(HttpStatus.FORBIDDEN,"Your account cannot view import mappings.");}
    private String encode(JsonNode node){try{return json.writeValueAsString(node);}catch(JsonProcessingException e){throw error(HttpStatus.BAD_REQUEST,"Invalid JSON.");}}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("Stored mapping is invalid",e);}}
    private StoredMapping row(Map<String,Object> r){return new StoredMapping(((Number)r.get("id")).longValue(),((Number)r.get("version")).intValue(),(String)r.get("name"),(Boolean)r.get("active"),(Boolean)r.get("is_default"),(Boolean)r.get("real_file_validated"),decode(r.get("mapping")),decode(r.get("settings")));}

    @Transactional(readOnly=true) public List<StoredMapping> list(){reader();return db.queryForList("SELECT * FROM import_column_mappings WHERE source_type=:s ORDER BY is_default DESC,lower(name),id",Map.of("s",source)).stream().map(this::row).toList();}
    @Transactional(readOnly=true) public StoredMapping get(long id){
        reader();var rows=db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s",Map.of("id",id,"s",source));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Mapping not found.");return row(rows.getFirst());
    }
    /** The requested mapping, or the active default. Used by staging; the caller has already been authorized. */
    @Transactional(readOnly=true) public StoredMapping forUpload(Long id){
        var rows=id==null?db.queryForList("SELECT * FROM import_column_mappings WHERE source_type=:s AND is_default",Map.of("s",source)):db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s",Map.of("id",id,"s",source));
        if(rows.isEmpty())throw new ImportFileException(ImportFileException.FileError.UNKNOWN_MAPPING,id==null?"No default "+noun+" mapping is configured.":"Mapping not found.");
        var mapping=row(rows.getFirst());if(!mapping.active())throw new ImportFileException(ImportFileException.FileError.UNKNOWN_MAPPING,"The selected mapping is not active.");return mapping;
    }
    /** What a batch keeps, so later edits never change staged data. */
    public JsonNode snapshot(StoredMapping m){
        var node=json.createObjectNode();node.put("mappingId",m.id());node.put("mappingVersion",m.version());node.put("name",m.name());node.put("realFileValidated",m.realFileValidated());node.set("mapping",m.mapping());node.set("settings",m.settings());return node;
    }

    private void checkValid(MappingInput in){
        if(in==null||in.name()==null||in.name().isBlank()||in.name().trim().length()>120)throw error(HttpStatus.BAD_REQUEST,"Name is required (120 characters at most).");
        var problems=validate(in.mapping(),in.settings());if(!problems.isEmpty())throw error(HttpStatus.BAD_REQUEST,"Mapping is invalid: "+String.join(" ",problems));
    }
    @Transactional public StoredMapping create(MappingInput in){
        administrator();checkValid(in);actor();
        if(in.isDefault()&&!in.active())throw error(HttpStatus.BAD_REQUEST,"A default mapping must be active.");
        try{
            if(in.isDefault())db.update("UPDATE import_column_mappings SET is_default=false WHERE source_type=:s AND is_default",Map.of("s",source));
            long id=db.queryForObject("INSERT INTO import_column_mappings(source_type,name,mapping,settings,is_default,active,real_file_validated) VALUES (:s,:name,CAST(:mapping AS jsonb),CAST(:settings AS jsonb),:def,:active,:real) RETURNING id",
                Map.of("s",source,"name",in.name().trim(),"mapping",encode(in.mapping()),"settings",encode(in.settings()),"def",in.isDefault(),"active",in.active(),"real",in.realFileValidated()),Long.class);
            return get(id);
        }catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"A mapping with this name already exists.");}
    }
    @Transactional public StoredMapping update(long id,int version,MappingInput in){
        administrator();checkValid(in);actor();
        var rows=db.queryForList("SELECT * FROM import_column_mappings WHERE id=:id AND source_type=:s FOR UPDATE",Map.of("id",id,"s",source));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Mapping not found.");var current=rows.getFirst();
        if(((Number)current.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"Mapping changed. Reload before saving.");
        if(Boolean.TRUE.equals(current.get("is_default"))&&(!in.isDefault()||!in.active()))throw error(HttpStatus.CONFLICT,"This is the default mapping; make another mapping the default instead of unsetting or deactivating it.");
        if(in.isDefault()&&!in.active())throw error(HttpStatus.BAD_REQUEST,"A default mapping must be active.");
        if(!in.active()&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM import_batches WHERE mapping_id=:id AND status='validated')",Map.of("id",id),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This mapping is used by a batch that has not been committed.");
        try{
            if(in.isDefault())db.update("UPDATE import_column_mappings SET is_default=false WHERE source_type=:s AND is_default AND id<>:id",Map.of("s",source,"id",id));
            db.update("UPDATE import_column_mappings SET name=:name,mapping=CAST(:mapping AS jsonb),settings=CAST(:settings AS jsonb),is_default=:def,active=:active,real_file_validated=:real,version=version+1 WHERE id=:id",
                Map.of("id",id,"name",in.name().trim(),"mapping",encode(in.mapping()),"settings",encode(in.settings()),"def",in.isDefault(),"active",in.active(),"real",in.realFileValidated()));
            return get(id);
        }catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"A mapping with this name already exists.");}
    }

    // ---- validation helpers shared by the types ----

    /** Validates mapping.fields: known fields, required ones present and not optional, 1-20 aliases each, no alias owned by two fields. Returns the alias keys. */
    protected Set<String> fields(JsonNode mapping,List<String> known,Set<String> required,List<String> problems){
        Set<String> aliasKeys=new HashSet<>();
        JsonNode fields=mapping.path("fields");
        if(!fields.isObject()){problems.add("mapping.fields must be an object.");return aliasKeys;}
        Map<String,String> owner=new HashMap<>();
        fields.fieldNames().forEachRemaining(k->{if(!known.contains(k))problems.add("Unknown field '"+k+"'.");});
        for(String name:known){
            JsonNode spec=fields.path(name);
            if(spec.isMissingNode()){if(required.contains(name))problems.add("Required field '"+name+"' is missing.");continue;}
            if(!spec.isObject()){problems.add("Field '"+name+"' must be an object.");continue;}
            spec.fieldNames().forEachRemaining(k->{if(!Set.of("required","aliases").contains(k))problems.add("Unknown key '"+k+"' in field '"+name+"'.");});
            if(spec.has("required")&&!spec.get("required").isBoolean())problems.add("Field '"+name+"'.required must be true or false.");
            if(required.contains(name)&&!spec.path("required").asBoolean(false))problems.add("Field '"+name+"' cannot be made optional.");
            JsonNode aliases=spec.path("aliases");int usable=0;
            if(!aliases.isArray()||aliases.size()>20)problems.add("Field '"+name+"' needs an aliases array of at most 20 entries.");
            else for(JsonNode a:aliases){
                String key=a.isTextual()&&a.asText().length()<=100?ImportText.header(a.asText()):"";
                if(key.isEmpty()){problems.add("Field '"+name+"' has an empty, non-text or overlong alias.");continue;}
                String previous=owner.putIfAbsent(key,name);if(previous!=null&&!previous.equals(name))problems.add("Alias '"+key+"' is used by both '"+previous+"' and '"+name+"'.");
                aliasKeys.add(key);usable++;
            }
            if(usable==0)problems.add("Field '"+name+"' needs at least one alias.");
        }
        return aliasKeys;
    }
    /** True when mapping.fields names this field with at least one alias. */
    protected static boolean mapped(JsonNode mapping,String field){
        JsonNode a=mapping.path("fields").path(field).path("aliases");if(!a.isArray())return false;
        for(JsonNode x:a)if(x.isTextual()&&!ImportText.header(x.asText()).isEmpty())return true;return false;
    }
    /** Validates the shared settings: allowed keys, layout, encoding, delimiter, headerRow, ignoredColumns (never a field alias) and the cleaning keys. */
    protected void settings(JsonNode settings,Set<String> allowed,String layout,Set<String> aliasKeys,List<String> fieldNames,JsonNode valueMaps,List<String> problems){
        settings.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))problems.add("Unknown setting '"+k+"'.");});
        if(settings.has("layout")&&!layout.equals(settings.path("layout").asText("")))problems.add("layout must be '"+layout+"' (other layouts are not supported).");
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
        CleaningProfile.validate(settings,fieldNames,valueMaps,problems);
    }
    /** Lower-case values of one reference column (codes or labels) that value-map targets must name. */
    protected Set<String> existing(String sql){Set<String> values=new HashSet<>();db.getJdbcTemplate().queryForList(sql,String.class).forEach(c->values.add(c.toLowerCase(Locale.ROOT)));return values;}
}
