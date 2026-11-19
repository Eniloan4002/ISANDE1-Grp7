package ph.edu.ici.imports;

/** One validation problem; becomes one import_exceptions record. Detail never echoes personal values. */
public record RowProblem(String code,String field,String severity,String detail) {
    public static final String ERROR="error",WARNING="warning";
    public RowProblem {if(detail==null||detail.isBlank())detail=code;if(detail.length()>1000)detail=detail.substring(0,1000);}
    public static RowProblem error(String code,String field,String detail){return new RowProblem(code,field,ERROR,detail);}
    public static RowProblem warning(String code,String field,String detail){return new RowProblem(code,field,WARNING,detail);}
}
