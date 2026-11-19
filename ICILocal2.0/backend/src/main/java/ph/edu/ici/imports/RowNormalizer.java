package ph.edu.ici.imports;

import java.time.*;
import java.util.*;
import java.util.regex.*;
import ph.edu.ici.students.StudentService;

/** Turns one raw CSV row into normalized student values plus row problems. Pure: no database access. */
public final class RowNormalizer {
    public record Result(Map<String,Object> values,List<RowProblem> problems,Long fileCampusId) {
        public boolean valid(){return problems.stream().noneMatch(p->RowProblem.ERROR.equals(p.severity()));}
    }
    private record DateFormat(String pattern,Pattern regex,int[] order) {}
    private static final Pattern LRN=Pattern.compile("[0-9]{12}"),EXCEL=Pattern.compile("\\d+(\\.\\d+)?"),SHORT_YEAR=Pattern.compile("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2}"),ROMAN=Pattern.compile("(?i)II|III|IV|VI|VII|VIII|IX");
    private final ImportMapping mapping;private final ReferenceData ref;private final long campusId;private final LocalDate today;private final List<DateFormat> formats;
    private final int columns;

    public RowNormalizer(ImportMapping mapping,ReferenceData ref,long uploadCampusId,LocalDate today,int headerColumns){
        this.mapping=mapping;this.ref=ref;this.campusId=uploadCampusId;this.today=today;this.columns=headerColumns;
        this.formats=mapping.settings().dateFormats().stream().map(RowNormalizer::compile).toList();
    }
    /** "yyyy-MM-dd" style pattern (each of yyyy, MM, dd once; '-', '/' or '.' separators) to an exact-match regex. */
    static DateFormat compile(String pattern){
        Matcher m=Pattern.compile("(yyyy|MM|dd)([-/.])(yyyy|MM|dd)\\2(yyyy|MM|dd)").matcher(pattern);
        if(!m.matches())throw new IllegalArgumentException("Unsupported date format: "+pattern);
        String sep=Pattern.quote(m.group(2));int[] order=new int[3];StringBuilder regex=new StringBuilder();
        for(int g=0;g<3;g++){String token=m.group(g==0?1:g==1?3:4);order[g]=token.equals("yyyy")?0:token.equals("MM")?1:2;regex.append(token.equals("yyyy")?"(\\d{4})":"(\\d{2})");if(g<2)regex.append(sep);}
        return new DateFormat(pattern,Pattern.compile(regex.toString()),order);
    }

    private String cell(List<String> cells,Map<String,Integer> idx,String field){Integer i=idx.get(field);return i==null||i>=cells.size()?null:cells.get(i);}

    public Result normalize(List<String> cells,Map<String,Integer> idx){
        List<RowProblem> problems=new ArrayList<>();Map<String,Object> v=new LinkedHashMap<>();Long fileCampus=null;
        if(cells.size()!=columns){problems.add(RowProblem.error("COLUMN_COUNT_MISMATCH",null,"The row has "+cells.size()+" values but the header has "+columns+"."));return new Result(v,problems,null);}
        for(String field:idx.keySet()){String raw=cell(cells,idx,field);if(raw!=null&&ImportText.hasControl(raw))problems.add(RowProblem.error("INVALID_CHARACTER",field,"The value contains control characters."));}

        v.put("studentNumber",text(cells,idx,"studentNumber",40,problems));
        String lrn=ImportText.clean(cell(cells,idx,"lrn"));
        if(lrn!=null&&!LRN.matcher(lrn).matches()){problems.add(RowProblem.error("INVALID_LRN","lrn","LRN must be exactly 12 digits."));lrn=null;}
        v.put("lrn",lrn);
        v.put("lastName",name(cells,idx,"lastName",problems));v.put("firstName",name(cells,idx,"firstName",problems));v.put("middleName",name(cells,idx,"middleName",problems));
        v.put("birthDate",birthDate(ImportText.clean(cell(cells,idx,"birthDate")),problems));
        v.put("sex",sex(ImportText.clean(cell(cells,idx,"sex")),problems));
        v.put("previousSchool",text(cells,idx,"previousSchool",250,problems));

        var voucher=resolve("voucher",ImportText.clean(cell(cells,idx,"voucher")),ref.vouchers(),true,"UNMAPPED_VALUE",problems);
        v.put("voucherCode",voucher==null?null:voucher.code());
        var program=resolve("specialization",ImportText.clean(cell(cells,idx,"specialization")),ref.programs(),true,"UNMAPPED_VALUE",problems);
        v.put("programId",program==null?null:program.id());v.put("programCode",program==null?null:program.code());
        v.put("track",program==null?null:StudentService.trackFor(program.category(),program.code()));
        String campusValue=ImportText.clean(cell(cells,idx,"campus"));
        if(campusValue!=null){
            var campus=resolve("campus",campusValue,ref.campuses(),false,"UNKNOWN_CAMPUS",problems);
            if(campus!=null){fileCampus=campus.id();if(campus.id()!=campusId)problems.add(RowProblem.error("CAMPUS_MISMATCH","campus","The row's campus ("+campus.code()+") differs from the upload's campus."));}
        }
        v.put("campusId",campusId);
        return new Result(v,problems,fileCampus);
    }

    private String text(List<String> cells,Map<String,Integer> idx,String field,int max,List<RowProblem> problems){
        String s=ImportText.clean(cell(cells,idx,field));var spec=mapping.fields().get(field);boolean required=ImportMapping.MANDATORY.contains(field)||spec!=null&&spec.required();
        if(s==null){if(required)problems.add(RowProblem.error("REQUIRED_MISSING",field,"A value is required."));return null;}
        if(s.length()>max){problems.add(RowProblem.error("TOO_LONG",field,"The value is longer than "+max+" characters."));return null;}
        return s;
    }
    private String name(List<String> cells,Map<String,Integer> idx,String field,List<RowProblem> problems){
        String s=text(cells,idx,field,150,problems);if(s==null)return null;
        return mapping.settings().normalizeNameCase()?fixCase(s):s;
    }
    /** Re-cases only names that are entirely upper-case or entirely lower-case; mixed-case names are left as written. */
    static String fixCase(String s){
        if(!s.chars().anyMatch(Character::isLetter)||!(s.equals(s.toUpperCase(Locale.ROOT))||s.equals(s.toLowerCase(Locale.ROOT))))return s;
        StringBuilder out=new StringBuilder();
        for(String token:s.split("(?<=[ \\-'’])|(?=[ \\-'’])")){
            if(token.length()==1&&" -'’".contains(token)){out.append(token);continue;}
            out.append(ROMAN.matcher(token).matches()?token.toUpperCase(Locale.ROOT):token.substring(0,1).toUpperCase(Locale.ROOT)+token.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
    private String sex(String raw,List<RowProblem> problems){
        if(raw==null)return null;var map=mapping.valueMaps().getOrDefault("sex",Map.of());
        String value=map.isEmpty()?raw:map.get(ImportText.value(raw));
        if(value==null){problems.add(RowProblem.error("UNMAPPED_VALUE","sex","The value '"+ImportText.excerpt(raw)+"' is not in the sex value map."));return null;}
        if(value.length()>30){problems.add(RowProblem.error("TOO_LONG","sex","The value is longer than 30 characters."));return null;}
        return value;
    }
    private ReferenceData.Ref resolve(String field,String raw,Map<String,ReferenceData.Ref> canonical,boolean required,String unmappedCode,List<RowProblem> problems){
        if(raw==null){if(required)problems.add(RowProblem.error("REQUIRED_MISSING",field,"A value is required."));return null;}
        String mapped=mapping.valueMaps().getOrDefault(field,Map.of()).get(ImportText.value(raw));
        var found=ReferenceData.find(canonical,mapped!=null?mapped:raw);
        if(found==null)problems.add(RowProblem.error(unmappedCode,field,"The value '"+ImportText.excerpt(raw)+"' is not a known code and has no entry in the "+field+" value map."));
        return found;
    }

    private String birthDate(String raw,List<RowProblem> problems){
        if(raw==null){problems.add(RowProblem.error("REQUIRED_MISSING","birthDate","A value is required."));return null;}
        Set<LocalDate> parsed=new LinkedHashSet<>();boolean impossible=false;
        for(var f:formats){
            Matcher m=f.regex().matcher(raw);if(!m.matches())continue;
            int[] part=new int[3];for(int g=0;g<3;g++)part[f.order()[g]]=Integer.parseInt(m.group(g+1));
            try{parsed.add(LocalDate.of(part[0],part[1],part[2]));}catch(DateTimeException e){impossible=true;}
        }
        if(parsed.size()>1){problems.add(RowProblem.error("AMBIGUOUS_DATE","birthDate","The date can be read in more than one configured format."));return null;}
        if(parsed.isEmpty()){
            String hint=impossible?"The date does not exist.":SHORT_YEAR.matcher(raw).matches()?"Two-digit years are not accepted.":EXCEL.matcher(raw).matches()?"Spreadsheet serial numbers are not accepted.":raw.matches(".*\\p{L}.*")?"Month names are not accepted.":"The value does not match an accepted format.";
            problems.add(RowProblem.error("INVALID_DATE","birthDate",hint+" Accepted formats: "+String.join(", ",formats.stream().map(DateFormat::pattern).toList())+"."));return null;
        }
        LocalDate date=parsed.iterator().next();
        if(date.getYear()<1900){problems.add(RowProblem.error("INVALID_DATE","birthDate","Dates before 1900 are not accepted."));return null;}
        if(!date.isBefore(today)){problems.add(RowProblem.error("FUTURE_DATE","birthDate","The birth date must be in the past."));return null;}
        return date.toString();
    }
}
