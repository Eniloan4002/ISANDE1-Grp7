package ph.edu.ici.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Grade-matrix limits that differ from the student import: wide files (subjects x periods) and a cap on grade cells. File size, rows and cell length stay in {@link ImportLimits}. */
public record GradeImportLimits(int maxColumns,int maxCells) {
    public static final GradeImportLimits DEFAULTS=new GradeImportLimits(300,250_000);
    /** The student-import limits with this record's column cap, for reusing {@link CsvParser}. */
    ImportLimits parserLimits(ImportLimits base){return new ImportLimits(base.maxFileBytes(),base.maxRows(),maxColumns,base.maxCellChars());}
    @Configuration static class Config {
        @Bean GradeImportLimits gradeImportLimits(@Value("${ici.import.grades.max-columns:300}") int columns,@Value("${ici.import.grades.max-cells:250000}") int cells){return new GradeImportLimits(columns,cells);}
    }
}
