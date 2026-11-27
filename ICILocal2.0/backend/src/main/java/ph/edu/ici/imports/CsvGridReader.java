package ph.edu.ici.imports;

/** {@link GridReader} for CSV files: a thin wrapper that keeps {@link CsvParser} (and its Record and Parsed types) source-compatible. */
public final class CsvGridReader implements GridReader {
    private final CsvParser parser;
    public CsvGridReader(ImportLimits limits){this.parser=new CsvParser(limits);}
    @Override public CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings){return parser.parse(bytes,settings);}
}
