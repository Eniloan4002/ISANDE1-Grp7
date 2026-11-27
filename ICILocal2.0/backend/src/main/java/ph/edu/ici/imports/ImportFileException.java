package ph.edu.ici.imports;

/** A problem with the whole file or its mapping (not one row). {@link FileError#status} is the HTTP status phase 3 will answer. */
public class ImportFileException extends RuntimeException {
    public enum FileError {
        FILE_TOO_LARGE(413,false),TOO_MANY_ROWS(413,false),UNKNOWN_MAPPING(422,false),
        EMPTY_FILE(422,true),INVALID_ENCODING(422,true),MALFORMED_CSV(422,true),TOO_MANY_COLUMNS(422,true),CELL_TOO_LONG(422,true),
        MISSING_COLUMN(422,true),AMBIGUOUS_HEADER(422,true),
        // grade matrix only
        UNMAPPED_COLUMN(422,true),UNKNOWN_SUBJECT(422,true),UNKNOWN_PERIOD(422,true),REPEATED_HEADER(422,true),TOO_MANY_CELLS(413,false),
        // .xlsx files (ICI2-39): limits are 413 with nothing persisted; content the reader refuses to interpret is 422 with a failed batch
        XLSX_LIMIT_EXCEEDED(413,false),UNSUPPORTED_FORMAT(422,true),MALFORMED_XLSX(422,true),MACRO_ENABLED(422,true),EXTERNAL_CONTENT(422,true),
        MULTIPLE_SHEETS(422,true),SHEET_NOT_FOUND(422,true),HIDDEN_CONTENT(422,true),MERGED_CELLS(422,true);
        public final int status;public final boolean recorded;
        FileError(int status,boolean recorded){this.status=status;this.recorded=recorded;}
    }
    /** One named header (or other short label) behind a file error; becomes its own batch-level import_exceptions record. */
    public record Problem(String code,String header,String detail) {}
    private final FileError error;private Long batchId;private final java.util.List<Problem> problems;
    public ImportFileException(FileError error,String message){this(error,message,java.util.List.of());}
    public ImportFileException(FileError error,String message,java.util.List<Problem> problems){super(message);this.error=error;this.problems=java.util.List.copyOf(problems);}
    /** Every individual problem (e.g. each unclassified header); empty for single-cause errors. */
    public java.util.List<Problem> problems(){return problems;}
    public FileError error(){return error;}
    /** Id of the failed batch recorded for 422 errors, otherwise null (nothing is persisted for 413). */
    public Long batchId(){return batchId;}
    void batchId(long id){this.batchId=id;}
}
