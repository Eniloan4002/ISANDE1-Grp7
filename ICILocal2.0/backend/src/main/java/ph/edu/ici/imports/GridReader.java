package ph.edu.ici.imports;

/**
 * Turns an uploaded file into the "grid of strings" the staging services consume: the header row, then one {@link CsvParser.Record}
 * per non-blank row with its 1-based row number. {@link CsvGridReader} reads CSV; a spreadsheet reader plugs in here later.
 * Implementations enforce the shared limits and throw {@link ImportFileException}; they never evaluate content.
 */
public interface GridReader {
    CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings);
    /** Reads the named sheet (the mapping's {@code settings.sheet}, or null). Formats without sheets ignore it. */
    default CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings,String sheet){return read(bytes,settings);}
    /** The value stored in {@code import_batches.source_format} for files this reader parsed ({@code csv} or {@code xlsx}). */
    default String format(){return "csv";}
}
