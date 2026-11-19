package ph.edu.ici.imports;

/**
 * Turns an uploaded file into the "grid of strings" the staging services consume: the header row, then one {@link CsvParser.Record}
 * per non-blank row with its 1-based row number. {@link CsvGridReader} reads CSV; a spreadsheet reader plugs in here later.
 * Implementations enforce the shared limits and throw {@link ImportFileException}; they never evaluate content.
 */
public interface GridReader {
    CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings);
}
