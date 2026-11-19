package ph.edu.ici.imports;

/** A problem with the whole file or its mapping (not one row). {@link FileError#status} is the HTTP status phase 3 will answer. */
public class ImportFileException extends RuntimeException {
    public enum FileError {
        FILE_TOO_LARGE(413,false),TOO_MANY_ROWS(413,false),UNKNOWN_MAPPING(422,false),
        EMPTY_FILE(422,true),INVALID_ENCODING(422,true),MALFORMED_CSV(422,true),TOO_MANY_COLUMNS(422,true),CELL_TOO_LONG(422,true),
        MISSING_COLUMN(422,true),AMBIGUOUS_HEADER(422,true);
        public final int status;public final boolean recorded;
        FileError(int status,boolean recorded){this.status=status;this.recorded=recorded;}
    }
    private final FileError error;private Long batchId;
    public ImportFileException(FileError error,String message){super(message);this.error=error;}
    public FileError error(){return error;}
    /** Id of the failed batch recorded for 422 errors, otherwise null (nothing is persisted for 413). */
    public Long batchId(){return batchId;}
    void batchId(long id){this.batchId=id;}
}
