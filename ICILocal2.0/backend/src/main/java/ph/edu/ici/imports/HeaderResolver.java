package ph.edu.ici.imports;

import java.util.*;
import ph.edu.ici.imports.ImportFileException.FileError;

/** Maps target fields to source column positions by normalized header equality only: no position or fuzzy matching. */
public final class HeaderResolver {
    private HeaderResolver(){}

    /** @return target field to zero-based column index; optional fields without a column are absent. */
    public static Map<String,Integer> resolve(List<String> headers,ImportMapping mapping){
        List<String> normalized=headers.stream().map(ImportText::header).toList();
        Map<String,Integer> result=new LinkedHashMap<>();Map<Integer,String> owner=new HashMap<>();List<String> missing=new ArrayList<>();
        for(String field:ImportMapping.FIELDS){
            var spec=mapping.fields().get(field);if(spec==null)continue;
            Set<String> aliases=new HashSet<>();for(String alias:spec.aliases()){String key=ImportText.header(alias);if(!key.isEmpty())aliases.add(key);}
            List<Integer> hits=new ArrayList<>();
            for(int i=0;i<normalized.size();i++)if(!normalized.get(i).isEmpty()&&aliases.contains(normalized.get(i)))hits.add(i);
            if(hits.size()>1)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"More than one column matches "+field+": "+hits.stream().map(i->"'"+ImportText.excerpt(headers.get(i))+"'").toList()+".");
            if(hits.isEmpty()){if(spec.required())missing.add(field);continue;}
            int index=hits.getFirst();
            if(owner.containsKey(index))throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"Column '"+ImportText.excerpt(headers.get(index))+"' matches both "+owner.get(index)+" and "+field+".");
            owner.put(index,field);result.put(field,index);
        }
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns not found: "+missing+". Check the file's header row or the mapping's aliases.");
        return result;
    }
}
