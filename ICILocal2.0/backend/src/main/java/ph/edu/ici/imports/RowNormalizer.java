package ph.edu.ici.imports;

import java.time.*;
import java.util.*;
import java.util.regex.*;
import ph.edu.ici.students.StudentService;

/**
 * Turns one raw row into normalized student values plus row problems. Pure: no database access. Text goes through {@link CellCleaner}
 * (so every change is counted or logged in the {@link CleaningLog}); with the default {@link CleaningProfile} the output is exactly
 * what it was before ICI2-39. One instance serves one file and one thread: {@code normalize} keeps the current row's log and cell
 * metadata in fields so the field helpers stay small.
 */
public final class RowNormalizer {
    public record Result(Map<String,Object> values,List<RowProblem> problems,Long fileCampusId) {
        public boolean valid(){return problems.stream().noneMatch(p->RowProblem.ERROR.equals(p.severity()));}
    }
    /** @param monthKind 0 numeric month, 1 three-letter English name, 2 full English name; {@code extended} when the pattern uses a token beyond yyyy/MM/dd */
    private record DateFormat(String pattern,Pattern regex,int[] order,int monthKind,boolean extended) {}
    private static final Pattern LRN=Pattern.compile("[0-9]{12}"),EXCEL=Pattern.compile("\\d+(\\.\\d+)?"),SHORT_YEAR=Pattern.compile("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2}"),ROMAN=Pattern.compile("(?i)II|III|IV|VI|VII|VIII|IX");
    private static final Pattern DATE_TOKEN=Pattern.compile("yyyy|MMMM|MMM|MM|M|dd|d");
    static final List<String> MONTHS=List.of("january","february","march","april","may","june","july","august","september","october","november","december");
    private final ImportMapping mapping;private final ReferenceData ref;private final long campusId;private final LocalDate today;private final List<DateFormat> formats;
    private final int columns;private final List<String> headers;private final CellCleaner cleaner;
    // current row
    private CleaningLog log=new CleaningLog();private Map<Integer,CellMeta> meta=Map.of();

    public RowNormalizer(ImportMapping mapping,ReferenceData ref,long uploadCampusId,LocalDate today,int headerColumns){this(mapping,ref,uploadCampusId,today,headerColumns,null);}
    /** @param headers the header cells, used only to name the column in cleaning log entries */
    public RowNormalizer(ImportMapping mapping,ReferenceData ref,long uploadCampusId,LocalDate today,List<String> headers){this(mapping,ref,uploadCampusId,today,headers.size(),headers);}
    private RowNormalizer(ImportMapping mapping,ReferenceData ref,long uploadCampusId,LocalDate today,int headerColumns,List<String> headers){
        this.mapping=mapping;this.ref=ref;this.campusId=uploadCampusId;this.today=today;this.columns=headerColumns;this.headers=headers;
        this.formats=mapping.settings().dateFormats().stream().map(RowNormalizer::compile).toList();this.cleaner=new CellCleaner(mapping.cleaning());
    }
    /**
     * A date pattern to an exact-match regex. Tokens: yyyy, MM, M, dd, d (numeric) and MMM, MMMM (English month names); everything else
     * is a literal. The classic patterns ("yyyy-MM-dd" with '-', '/' or '.') compile to the same regex as before ICI2-39. Which
     * patterns a mapping may use is decided on save by {@link ImportMappingService#dateProblems}.
     */
    static DateFormat compile(String pattern){
        Matcher m=DATE_TOKEN.matcher(pattern);int[] order=new int[3];int groups=0,at=0,monthKind=0;boolean extended=false;StringBuilder regex=new StringBuilder();Set<Integer> seen=new HashSet<>();
        while(m.find()){
            for(char c:pattern.substring(at,m.start()).toCharArray())regex.append(Pattern.quote(String.valueOf(c)));at=m.end();
            String token=m.group();int part=token.startsWith("y")?0:token.startsWith("M")?1:2;
            if(!seen.add(part)||groups>=3)throw new IllegalArgumentException("Unsupported date format: "+pattern);
            order[groups++]=part;
            switch(token){
                case "yyyy"->regex.append("(\\d{4})");
                case "MM","dd"->regex.append("(\\d{2})");
                case "M","d"->{regex.append("(\\d{1,2})");extended=true;}
                case "MMM"->{regex.append("([A-Za-z]{3})");monthKind=1;extended=true;}
                default->{regex.append("([A-Za-z]{3,9})");monthKind=2;extended=true;}
            }
        }
        for(char c:pattern.substring(at).toCharArray())regex.append(Pattern.quote(String.valueOf(c)));
        if(groups!=3)throw new IllegalArgumentException("Unsupported date format: "+pattern);
        return new DateFormat(pattern,Pattern.compile(regex.toString()),order,monthKind,extended);
    }

    private String cell(List<String> cells,Map<String,Integer> idx,String field){Integer i=idx.get(field);return i==null||i>=cells.size()?null:cells.get(i);}
    private String column(Map<String,Integer> idx,String field){Integer i=idx.get(field);return i==null||headers==null||i>=headers.size()?null:headers.get(i);}
    private CellMeta metaOf(Map<String,Integer> idx,String field){Integer i=idx.get(field);return i==null?null:meta.get(i);}
    /** The cleaned text of one mapped field (null when blank, or when cleaning refused the cell and recorded why). */
    private String clean(List<String> cells,Map<String,Integer> idx,String field){return cleaner.clean(cell(cells,idx,field),field,column(idx,field),metaOf(idx,field),log);}

    public Result normalize(List<String> cells,Map<String,Integer> idx){return normalize(cells,idx,Map.of(),new CleaningLog());}

    /** @param cellMeta metadata of non-text cells by zero-based column (empty for CSV); @param cleaning receives what cleaning changed and any cleaning problems */
    public Result normalize(List<String> cells,Map<String,Integer> idx,Map<Integer,CellMeta> cellMeta,CleaningLog cleaning){
        this.log=cleaning;this.meta=cellMeta==null?Map.of():cellMeta;
        List<RowProblem> problems=new ArrayList<>();Map<String,Object> v=new LinkedHashMap<>();Long fileCampus=null;
        if(cells.size()!=columns){problems.add(RowProblem.error("COLUMN_COUNT_MISMATCH",null,"The row has "+cells.size()+" values but the header has "+columns+"."));return new Result(v,problems,null);}
        for(String field:idx.keySet()){String raw=cell(cells,idx,field);if(raw!=null&&ImportText.hasControl(raw))problems.add(RowProblem.error("INVALID_CHARACTER",field,"The value contains control characters."));}

        v.put("studentNumber",text(cells,idx,"studentNumber",40,problems));
        String lrn=clean(cells,idx,"lrn");
        if(lrn!=null&&!LRN.matcher(lrn).matches()){problems.add(RowProblem.error("INVALID_LRN","lrn","LRN must be exactly 12 digits."));lrn=null;}
        v.put("lrn",lrn);
        v.put("lastName",name(cells,idx,"lastName",problems));v.put("firstName",name(cells,idx,"firstName",problems));v.put("middleName",name(cells,idx,"middleName",problems));
        CellMeta birthMeta=metaOf(idx,"birthDate");
        v.put("birthDate",birthDate(clean(cells,idx,"birthDate"),birthMeta!=null&&birthMeta.kind()==CellMeta.Kind.DATE,column(idx,"birthDate"),problems));
        v.put("sex",sex(clean(cells,idx,"sex"),problems));
        v.put("previousSchool",text(cells,idx,"previousSchool",250,problems));

        var voucher=resolve("voucher",clean(cells,idx,"voucher"),ref.vouchers(),true,"UNMAPPED_VALUE",problems);
        v.put("voucherCode",voucher==null?null:voucher.code());
        var program=resolve("specialization",clean(cells,idx,"specialization"),ref.programs(),true,"UNMAPPED_VALUE",problems);
        v.put("programId",program==null?null:program.id());v.put("programCode",program==null?null:program.code());
        v.put("track",program==null?null:StudentService.trackFor(program.category(),program.code()));
        String campusValue=clean(cells,idx,"campus");
        if(campusValue!=null){
            var campus=resolve("campus",campusValue,ref.campuses(),false,"UNKNOWN_CAMPUS",problems);
            if(campus!=null){fileCampus=campus.id();if(campus.id()!=campusId)problems.add(RowProblem.error("CAMPUS_MISMATCH","campus","The row's campus ("+campus.code()+") differs from the upload's campus."));}
        }
        v.put("campusId",campusId);
        problems.addAll(log.problems());
        return new Result(v,problems,fileCampus);
    }

    private String text(List<String> cells,Map<String,Integer> idx,String field,int max,List<RowProblem> problems){
        String s=clean(cells,idx,field);var spec=mapping.fields().get(field);boolean required=ImportMapping.MANDATORY.contains(field)||spec!=null&&spec.required();
        if(s==null){if(required&&!log.hasError(field))problems.add(RowProblem.error("REQUIRED_MISSING",field,"A value is required."));return null;}
        if(s.length()>max){problems.add(RowProblem.error("TOO_LONG",field,"The value is longer than "+max+" characters."));return null;}
        return s;
    }
    private String name(List<String> cells,Map<String,Integer> idx,String field,List<RowProblem> problems){
        String s=text(cells,idx,field,150,problems);if(s==null)return null;
        return mapping.settings().normalizeNameCase()?cleaner.nameCase(s,field,column(idx,field),log):s;
    }
    /** Re-cases only names that are entirely upper-case or entirely lower-case; mixed-case names are left as written. */
    static String fixCase(String s){return fixCase(s,Set.of());}
    /** As {@link #fixCase(String)}, keeping the listed lower-case particles (e.g. "de", "dela") in lower case. */
    static String fixCase(String s,Set<String> particles){
        if(!s.chars().anyMatch(Character::isLetter)||!(s.equals(s.toUpperCase(Locale.ROOT))||s.equals(s.toLowerCase(Locale.ROOT))))return s;
        StringBuilder out=new StringBuilder();
        for(String token:s.split("(?<=[ \\-'’])|(?=[ \\-'’])")){
            if(token.length()==1&&" -'’".contains(token)){out.append(token);continue;}
            if(particles.contains(token.toLowerCase(Locale.ROOT))){out.append(token.toLowerCase(Locale.ROOT));continue;}
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
        if(raw==null){if(required&&!log.hasError(field))problems.add(RowProblem.error("REQUIRED_MISSING",field,"A value is required."));return null;}
        String mapped=mapping.valueMaps().getOrDefault(field,Map.of()).get(ImportText.value(raw));
        var found=ReferenceData.find(canonical,mapped!=null?mapped:raw);
        if(found==null)problems.add(RowProblem.error(unmappedCode,field,"The value '"+ImportText.excerpt(raw)+"' is not a known code and has no entry in the "+field+" value map."));
        return found;
    }

    private String birthDate(String raw,boolean nativeDate,String column,List<RowProblem> problems){
        if(raw==null){if(!log.hasError("birthDate"))problems.add(RowProblem.error("REQUIRED_MISSING","birthDate","A value is required."));return null;}
        LocalDate date=null;
        if(nativeDate){   // a spreadsheet date cell has no day/month ambiguity: it bypasses dateFormats (cleaning already logged it)
            try{date=LocalDate.parse(raw);}catch(DateTimeException e){problems.add(RowProblem.error("INVALID_DATE","birthDate","The date cell could not be read."));return null;}
        }else{
            Map<LocalDate,DateFormat> parsed=new LinkedHashMap<>();boolean impossible=false;
            for(var f:formats){
                Matcher m=f.regex().matcher(raw);if(!m.matches())continue;
                int[] part=new int[3];boolean ok=true;
                for(int g=0;g<3;g++){
                    String group=m.group(g+1);
                    if(f.order()[g]==1&&f.monthKind()>0){int month=monthNumber(group,f.monthKind());if(month<0){ok=false;break;}part[1]=month;}
                    else part[f.order()[g]]=Integer.parseInt(group);
                }
                if(!ok)continue;
                try{parsed.putIfAbsent(LocalDate.of(part[0],part[1],part[2]),f);}catch(DateTimeException e){impossible=true;}
            }
            if(parsed.size()>1){problems.add(RowProblem.error("AMBIGUOUS_DATE","birthDate","The date can be read in more than one configured format."));return null;}
            if(parsed.isEmpty()){
                String hint=impossible?"The date does not exist.":SHORT_YEAR.matcher(raw).matches()?"Two-digit years are not accepted.":EXCEL.matcher(raw).matches()?"Spreadsheet serial numbers are not accepted.":raw.matches(".*\\p{L}.*")?"Month names are not accepted.":"The value does not match an accepted format.";
                problems.add(RowProblem.error("INVALID_DATE","birthDate",hint+" Accepted formats: "+String.join(", ",formats.stream().map(DateFormat::pattern).toList())+"."));return null;
            }
            var entry=parsed.entrySet().iterator().next();date=entry.getKey();
            if(entry.getValue().extended())log.changed("birthDate",column,"date_parsed:"+entry.getValue().pattern(),raw,date.toString());
        }
        if(date.getYear()<1900){problems.add(RowProblem.error("INVALID_DATE","birthDate","Dates before 1900 are not accepted."));return null;}
        if(!date.isBefore(today)){problems.add(RowProblem.error("FUTURE_DATE","birthDate","The birth date must be in the past."));return null;}
        return date.toString();
    }
    /** 1 to 12 for an English month name (three letters when {@code kind} is 1, the full name when 2), otherwise -1. Case-insensitive. */
    static int monthNumber(String name,int kind){
        String s=name.toLowerCase(Locale.ROOT);
        for(int i=0;i<12;i++)if(kind==1?MONTHS.get(i).substring(0,3).equals(s):MONTHS.get(i).equals(s))return i+1;
        return -1;
    }
}
