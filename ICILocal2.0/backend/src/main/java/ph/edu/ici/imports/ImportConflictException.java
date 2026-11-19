package ph.edu.ici.imports;

/** The same file bytes are already staged or imported (HTTP 409 in phase 3). */
public class ImportConflictException extends RuntimeException {
    private final long batchId;private final String status;
    public ImportConflictException(long batchId,String status){super("This exact file was already uploaded as batch "+batchId+" ("+status+").");this.batchId=batchId;this.status=status;}
    public long batchId(){return batchId;}
    public String status(){return status;}
}
