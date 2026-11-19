package ph.edu.ici.imports;

import java.util.*;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportFileException.Problem;

/**
 * Classifies every column of a grade matrix as identity, context, grade ("Subject - Period"), reported final, ignored or unclassified.
 * Header text is normalized exactly as in ICI2-37 ({@link ImportText#header}); nothing is matched by position or similarity.
 */
final class GradeMatrixHeaders {
    private GradeMatrixHeaders(){}

    /** A grade column. {@code periodKey} is the lower-case grading period code, null for a reported final. */
    record GradeColumn(int index,String header,String subjectCode,long subjectId,String periodKey,boolean reportedFinal) {}
    record Layout(Map<String,Integer> fields,List<GradeColumn> grades) {}

    /** The upload's fixed context, as far as it narrows which periods a header may name. */
    record Fixed(Long termId,Long yearId,boolean hasGradeLevel) {}

    static String label(String header,int index){String s=ImportText.clean(header==null?null:header.replaceAll("\\p{Cc}",""));return s==null?"(column "+(index+1)+", no header)":s.length()>100?s.substring(0,100):s;}

    /** @param blankColumns indexes of columns whose header is blank and whose every cell is blank (harmless trailing columns) */
    static Layout classify(List<String> headers,GradeMatrixMapping mapping,GradeReference ref,Fixed fixed,Set<Integer> blankColumns){
        Map<String,List<String>> aliasOwners=new HashMap<>();
        for(String field:GradeMatrixMapping.FIELDS){
            var spec=mapping.fields().get(field);if(spec==null)continue;
            for(String alias:spec.aliases()){String k=ImportText.header(alias);if(!k.isEmpty())aliasOwners.computeIfAbsent(k,x->new ArrayList<>()).add(field);}
        }
        Map<String,Integer> fields=new LinkedHashMap<>();List<GradeColumn> grades=new ArrayList<>();
        List<Problem> unmapped=new ArrayList<>(),badSubject=new ArrayList<>(),badPeriod=new ArrayList<>();
        Map<String,Integer> targets=new HashMap<>();
        var terms=ref.candidateTerms(fixed.termId(),fixed.yearId());
        for(int i=0;i<headers.size();i++){
            String header=headers.get(i);String key=ImportText.header(header);String shown=label(header,i);
            if(key.isEmpty()){if(!blankColumns.contains(i))unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column has data but no header, so it cannot be classified."));continue;}
            List<String> owners=aliasOwners.getOrDefault(key,List.of());boolean ignored=mapping.settings().ignoredColumns().contains(key);
            if(owners.size()>1||(!owners.isEmpty()&&ignored))
                throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"Column '"+shown+"' is claimed by more than one classification: "+(owners.size()>1?owners:List.of(owners.getFirst(),"ignoredColumns"))+".");
            if(ignored)continue;
            if(!owners.isEmpty()){
                String field=owners.getFirst();Integer previous=fields.putIfAbsent(field,i);
                if(previous!=null)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"More than one column matches "+field+": '"+label(headers.get(previous),previous)+"' and '"+shown+"'.");
                continue;
            }
            String cleaned=ImportText.clean(header);int at=cleaned.lastIndexOf(mapping.separator());
            String subject=at<0?"":cleaned.substring(0,at).trim(),period=at<0?"":cleaned.substring(at+mapping.separator().length()).trim();
            if(subject.isEmpty()||period.isEmpty()){unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column is not an identity or context column, is not listed in ignoredColumns, and is not shaped like 'Subject"+mapping.separator()+"Period'."));continue;}
            String mapped=mapping.valueMaps().get("subject").get(ImportText.value(subject));
            var found=ref.subjects().get(GradeReference.key(mapped!=null?mapped:subject));
            if(found==null){badSubject.add(new Problem("UNKNOWN_SUBJECT",shown,"The subject '"+ImportText.excerpt(subject)+"' is not an existing subject code and is not in the mapping's subject value map."));continue;}
            String periodValue=ImportText.value(period);boolean reportedFinal=mapping.reportedFinalLabels().contains(periodValue);String periodKey=null;
            if(!reportedFinal){
                String code=mapping.valueMaps().get("period").get(periodValue);periodKey=GradeReference.key(code!=null?code:period);
                if(!ref.periodExists(periodKey,terms)){badPeriod.add(new Problem("UNKNOWN_PERIOD",shown,"The grading period '"+ImportText.excerpt(period)+"' does not exist in the term(s) this upload can refer to and is not in the mapping's period value map."));continue;}
            }
            String target=found.id()+"|"+(reportedFinal?"final":periodKey);Integer other=targets.putIfAbsent(target,i);
            if(other!=null)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"Columns '"+label(headers.get(other),other)+"' and '"+shown+"' both classify as subject "+found.code()+" "+(reportedFinal?"reported final":"period "+periodKey)+".");
            grades.add(new GradeColumn(i,shown,found.code(),found.id(),periodKey,reportedFinal));
        }
        if(!unmapped.isEmpty()||!badSubject.isEmpty()||!badPeriod.isEmpty()){
            List<Problem> all=new ArrayList<>();all.addAll(unmapped);all.addAll(badSubject);all.addAll(badPeriod);
            FileError error=!unmapped.isEmpty()?FileError.UNMAPPED_COLUMN:!badSubject.isEmpty()?FileError.UNKNOWN_SUBJECT:FileError.UNKNOWN_PERIOD;
            StringBuilder text=new StringBuilder();
            if(!unmapped.isEmpty())text.append("Columns the mapping neither classifies nor lists in ignoredColumns (classify or ignore each): ").append(names(unmapped)).append(". ");
            if(!badSubject.isEmpty())text.append("Columns naming an unknown subject: ").append(names(badSubject)).append(". ");
            if(!badPeriod.isEmpty())text.append("Columns naming an unknown grading period: ").append(names(badPeriod)).append(". ");
            throw new ImportFileException(error,text.toString().trim(),all);
        }
        return new Layout(fields,grades);
    }
    private static String names(List<Problem> problems){return problems.stream().map(p->"'"+p.header()+"'").toList().toString();}
}
