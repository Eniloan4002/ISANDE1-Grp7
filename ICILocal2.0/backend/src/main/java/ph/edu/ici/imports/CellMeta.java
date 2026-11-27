package ph.edu.ici.imports;

/**
 * What a spreadsheet cell was before it became text. A CSV grid never carries this (every cell is plain text); the xlsx reader
 * supplies it for cells that were not text, so the cleaner can refuse to guess (percent-formatted or date-formatted grade cells,
 * error cells, formulas without a cached value) instead of reading display text.
 *
 * @param kind         the stored cell type
 * @param format       number-format class of a numeric cell
 * @param formula      the cell holds a formula; its cached value, never the formula, is the cell text
 * @param cachedMissing a formula cell that carries no cached value
 */
public record CellMeta(Kind kind,Format format,boolean formula,boolean cachedMissing) {
    public enum Kind {TEXT,NUMBER,DATE,BOOLEAN,ERROR}
    public enum Format {NONE,PERCENT,DATE,OTHER}
    public static CellMeta number(){return new CellMeta(Kind.NUMBER,Format.NONE,false,false);}
    public static CellMeta number(Format format){return new CellMeta(Kind.NUMBER,format,false,false);}
    public static CellMeta date(){return new CellMeta(Kind.DATE,Format.DATE,false,false);}
    public static CellMeta error(){return new CellMeta(Kind.ERROR,Format.NONE,false,false);}
    public CellMeta asFormula(boolean cachedMissing){return new CellMeta(kind,format,true,cachedMissing);}
}
