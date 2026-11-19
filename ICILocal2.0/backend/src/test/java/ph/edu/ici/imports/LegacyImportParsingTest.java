package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import ph.edu.ici.imports.ImportFileException.FileError;
import static org.assertj.core.api.Assertions.*;

/** Pure parser/normalizer tests on synthetic in-memory CSV text; no database. */
class LegacyImportParsingTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String MAPPING="""
        {"fields":{"studentNumber":{"required":true,"aliases":["student_no"]},"lrn":{"required":false,"aliases":["lrn","legacy_lrn"]},
         "lastName":{"required":true,"aliases":["last_name"]},"firstName":{"required":true,"aliases":["first_name"]},"middleName":{"required":false,"aliases":["middle_name"]},
         "birthDate":{"required":true,"aliases":["birth_date"]},"sex":{"required":false,"aliases":["sex"]},"previousSchool":{"required":false,"aliases":["previous_school"]},
         "voucher":{"required":true,"aliases":["voucher"]},"specialization":{"required":true,"aliases":["strand"]},"campus":{"required":false,"aliases":["campus"]}},
         "valueMaps":{"voucher":{"Q":"QVR"},"specialization":{},"campus":{},"sex":{"M":"Male","F":"Female"}}}""";
    static final String SETTINGS="{\"encoding\":\"UTF-8\",\"delimiter\":\",\",\"headerRow\":1,\"dateFormats\":[\"yyyy-MM-dd\",\"MM/dd/yyyy\"],\"normalizeNameCase\":true}";
    static ImportMapping mapping(String settings)throws Exception{return ImportMapping.parse(JSON.readTree(MAPPING),JSON.readTree(settings));}
    static final ReferenceData REF=new ReferenceData(
        Map.of("iligan",new ReferenceData.Ref(1,"ILIGAN",null),"bugo",new ReferenceData.Ref(3,"BUGO",null)),
        Map.of("stem",new ReferenceData.Ref(10,"STEM","SHS"),"tvl_ict",new ReferenceData.Ref(11,"TVL_ICT","SHS"),"tvet_x",new ReferenceData.Ref(12,"TVET_X","TVET")),
        Map.of("qvr",new ReferenceData.Ref(0,"QVR",null),"esc",new ReferenceData.Ref(0,"ESC",null)));
    CsvParser parser(){return new CsvParser(ImportLimits.DEFAULTS);}
    ImportMapping.Settings defaults()throws Exception{return mapping(SETTINGS).settings();}
    CsvParser.Parsed parse(String text)throws Exception{return parser().parse(text.getBytes(StandardCharsets.UTF_8),defaults());}
    ImportFileException fails(byte[] bytes,ImportMapping.Settings s,ImportLimits limits){return catchThrowableOfType(ImportFileException.class,()->new CsvParser(limits).parse(bytes,s));}

    // ---- CSV parsing --------------------------------------------------------------------------------------------------
    @Test void readsQuotedFieldsDoubledQuotesEmbeddedNewlinesCrlfAndSkipsBlankLinesKeepingRecordNumbers()throws Exception{
        var parsed=parse("a,b,c\r\n1,\"x,y\",\"say \"\"hi\"\"\"\r\n\r\n2,\"line1\nline2\",\r\n3,,\r\n");
        assertThat(parsed.headers()).containsExactly("a","b","c");
        assertThat(parsed.rows()).extracting(CsvParser.Record::number).containsExactly(2,4,5);
        assertThat(parsed.rows().get(0).cells()).containsExactly("1","x,y","say \"hi\"");
        assertThat(parsed.rows().get(1).cells()).containsExactly("2","line1\nline2","");
        assertThat(parsed.rows().get(2).cells()).containsExactly("3","","");
    }
    @Test void honoursConfiguredDelimiterAndHeaderRow()throws Exception{
        var s=mapping("{\"encoding\":\"UTF-8\",\"delimiter\":\";\",\"headerRow\":2,\"dateFormats\":[\"yyyy-MM-dd\"]}").settings();
        var parsed=parser().parse("SYNTHETIC EXPORT\na;b\n1;\"x;y\"".getBytes(StandardCharsets.UTF_8),s);
        assertThat(parsed.headers()).containsExactly("a","b");assertThat(parsed.rows().getFirst().number()).isEqualTo(3);assertThat(parsed.rows().getFirst().cells()).containsExactly("1","x;y");
        var tab=mapping("{\"encoding\":\"UTF-8\",\"delimiter\":\"\\t\",\"headerRow\":1}").settings();
        assertThat(parser().parse("a\tb\n1\t2".getBytes(StandardCharsets.UTF_8),tab).rows().getFirst().cells()).containsExactly("1","2");
    }
    @Test void toleratesUtf8ByteOrderMarkSoTheFirstHeaderStillMatches()throws Exception{
        byte[] body="Student No,LRN\nSYN-1,\n".getBytes(StandardCharsets.UTF_8);byte[] bytes=new byte[body.length+3];bytes[0]=(byte)0xEF;bytes[1]=(byte)0xBB;bytes[2]=(byte)0xBF;System.arraycopy(body,0,bytes,3,body.length);
        var parsed=parser().parse(bytes,defaults());assertThat(parsed.headers().getFirst()).isEqualTo("Student No");
        assertThat(HeaderResolver.resolve(parsed.headers(),mapping(SETTINGS).fields().containsKey("lrn")?withOptionalOnly():null)).containsKey("studentNumber");
    }
    ImportMapping withOptionalOnly()throws Exception{
        return ImportMapping.parse(JSON.readTree("{\"fields\":{\"studentNumber\":{\"required\":true,\"aliases\":[\"student_no\"]},\"lrn\":{\"aliases\":[\"lrn\"]}}}"),JSON.readTree(SETTINGS));
    }
    @Test void invalidBytesForTheConfiguredEncodingFailTheFileWhileTheRightEncodingReadsIt()throws Exception{
        byte[] latin="a,b\nJos\u00e9,x\n".getBytes(Charset.forName("windows-1252"));
        assertThat(fails(latin,defaults(),ImportLimits.DEFAULTS).error()).isEqualTo(FileError.INVALID_ENCODING);
        var cp=mapping("{\"encoding\":\"windows-1252\",\"delimiter\":\",\",\"headerRow\":1}").settings();
        assertThat(parser().parse(latin,cp).rows().getFirst().cells().getFirst()).isEqualTo("Jos\u00e9");
        assertThat(fails(new byte[]{(byte)0xFF,(byte)0xFE,'a',0},defaults(),ImportLimits.DEFAULTS).error()).isEqualTo(FileError.INVALID_ENCODING);
    }
    @Test void enforcesFileRowColumnAndCellLimitsWithTheRightStatus()throws Exception{
        var s=defaults();var tiny=new ImportLimits(50,3,4,10);
        var big=fails(new byte[51],s,tiny);assertThat(big.error()).isEqualTo(FileError.FILE_TOO_LARGE);assertThat(big.error().status).isEqualTo(413);assertThat(big.error().recorded).isFalse();
        var rows=fails("a\n1\n2\n3\n4\n".getBytes(StandardCharsets.UTF_8),s,tiny);assertThat(rows.error()).isEqualTo(FileError.TOO_MANY_ROWS);assertThat(rows.error().status).isEqualTo(413);
        assertThat(new CsvParser(tiny).parse("a\n1\n2\n3\n".getBytes(StandardCharsets.UTF_8),s).rows()).hasSize(3);   // exactly at the limit is accepted
        assertThat(fails("a,b,c,d,e\n1,2,3,4,5\n".getBytes(StandardCharsets.UTF_8),s,tiny).error()).isEqualTo(FileError.TOO_MANY_COLUMNS);
        assertThat(fails("a\n12345678901\n".getBytes(StandardCharsets.UTF_8),s,tiny).error()).isEqualTo(FileError.CELL_TOO_LONG);
        assertThat(fails(new byte[0],s,tiny).error()).isEqualTo(FileError.EMPTY_FILE);
        assertThat(fails("a,b\n".getBytes(StandardCharsets.UTF_8),s,tiny).error()).isEqualTo(FileError.EMPTY_FILE);
    }
    @Test void malformedQuotingFailsTheFile()throws Exception{
        assertThat(fails("a\n\"never closed\n".getBytes(StandardCharsets.UTF_8),defaults(),ImportLimits.DEFAULTS).error()).isEqualTo(FileError.MALFORMED_CSV);
        assertThat(fails("a\n\"x\"y\n".getBytes(StandardCharsets.UTF_8),defaults(),ImportLimits.DEFAULTS).error()).isEqualTo(FileError.MALFORMED_CSV);
    }

    // ---- header resolution -------------------------------------------------------------------------------------------
    @Test void headersMatchAfterNfkcLowercaseTrimAndUnderscoreCollapsingButNeverByPositionOrSimilarity()throws Exception{
        var m=mapping(SETTINGS);
        var idx=HeaderResolver.resolve(List.of("  STUDENT   NO. ","Ｌａｓｔ-Name","last_name_x","First_Name","Birth.Date","Voucher","STRAND","LRN"),ImportMapping.parse(JSON.readTree(MAPPING.replace("[\"last_name\"]","[\"last-name\"]")),JSON.readTree(SETTINGS)));
        assertThat(idx).containsEntry("studentNumber",0).containsEntry("lastName",1).containsEntry("firstName",3).containsEntry("birthDate",4).containsEntry("lrn",7).doesNotContainKey("sex");
        assertThat(ImportText.header("  Ｓtudent   No. ")).isEqualTo("student_no");
        assertThatThrownBy(()->HeaderResolver.resolve(List.of("Student Number","Last Name","First Name","Birth Date","Voucher","Strand"),m)).isInstanceOfSatisfying(ImportFileException.class,e->assertThat(e.error()).isEqualTo(FileError.MISSING_COLUMN));
    }
    @Test void missingRequiredColumnsAreListedAndAmbiguousHeadersRefused()throws Exception{
        var m=mapping(SETTINGS);
        var missing=catchThrowableOfType(ImportFileException.class,()->HeaderResolver.resolve(List.of("Student No","Last Name"),m));
        assertThat(missing.error()).isEqualTo(FileError.MISSING_COLUMN);assertThat(missing.getMessage()).contains("firstName","birthDate","voucher","specialization").doesNotContain("lastName");
        var two=catchThrowableOfType(ImportFileException.class,()->HeaderResolver.resolve(List.of("Student No","LRN","Legacy LRN","Last Name","First Name","Birth Date","Voucher","Strand"),m));
        assertThat(two.error()).isEqualTo(FileError.AMBIGUOUS_HEADER);
        var shared=ImportMapping.parse(JSON.readTree(MAPPING.replace("[\"first_name\"]","[\"last_name\"]")),JSON.readTree(SETTINGS));
        assertThat(catchThrowableOfType(ImportFileException.class,()->HeaderResolver.resolve(List.of("Student No","Last Name","Birth Date","Voucher","Strand"),shared)).error()).isEqualTo(FileError.AMBIGUOUS_HEADER);
    }

    // ---- row normalization -------------------------------------------------------------------------------------------
    static final List<String> HEADERS=List.of("Student No","LRN","Last Name","First Name","Middle Name","Birth Date","Sex","Previous School","Voucher","Strand","Campus");
    RowNormalizer normalizer(String settings,long campus)throws Exception{return new RowNormalizer(mapping(settings),REF,campus,LocalDate.of(2026,11,19),HEADERS.size());}
    Map<String,Integer> columns()throws Exception{return HeaderResolver.resolve(HEADERS,mapping(SETTINGS));}
    RowNormalizer.Result row(String... cells)throws Exception{return normalizer(SETTINGS,1).normalize(List.of(cells),columns());}
    String[] cells(String birth){return new String[]{"SYN-1","123456789012","Santos","Ana","","%s".formatted(birth),"","","QVR","STEM",""};}
    List<String> codes(RowNormalizer.Result r){return r.problems().stream().map(RowProblem::code).toList();}

    @Test void acceptsOnlyConfiguredDateFormatsAndRejectsLookalikes()throws Exception{
        assertThat(row(cells("2009-03-14")).values()).containsEntry("birthDate","2009-03-14");
        assertThat(row(cells("03/14/2009")).values()).containsEntry("birthDate","2009-03-14");
        for(String bad:List.of("14/03/09","03/14/09","40251","March 14 2009","14 Mar 2009","3/4/2009","2009-3-4","2009/03/14","2009-03-14 00:00:00","14/03/2009","02/30/2009"))
            assertThat(codes(row(cells(bad)))).as(bad).containsExactly("INVALID_DATE");
        assertThat(codes(row(cells("2999-01-01")))).containsExactly("FUTURE_DATE");
        assertThat(codes(row(cells("2026-11-19")))).as("today is not in the past").containsExactly("FUTURE_DATE");
        assertThat(row(cells("2026-11-18")).valid()).isTrue();
        assertThat(codes(row(cells("1899-12-31")))).containsExactly("INVALID_DATE");assertThat(row(cells("1900-01-01")).valid()).isTrue();
        assertThat(codes(row(cells("")))).containsExactly("REQUIRED_MISSING");
    }
    @Test void errorDetailsNeverEchoTheDateOrLrn()throws Exception{
        var c=cells("14/03/09");c[1]="9876543210";var r=row(c);
        assertThat(r.problems()).extracting(RowProblem::detail).allSatisfy(d->assertThat(d).doesNotContain("14/03/09").doesNotContain("9876543210"));
        assertThat(codes(r)).containsExactlyInAnyOrder("INVALID_LRN","INVALID_DATE");
    }
    @Test void cleansWhitespaceAndFixesOnlyUniformlyCasedNamesKeepingAccents()throws Exception{
        var c=cells("2009-03-14");c[2]="  DELA   CRUZ ";c[3]="JOSÉ  MARIA";c[4]="o'neil";c[7]="  Escuela   Synthetic ";
        var v=row(c).values();
        assertThat(v).containsEntry("lastName","Dela Cruz").containsEntry("firstName","José Maria").containsEntry("middleName","O'Neil").containsEntry("previousSchool","Escuela Synthetic");
        c[2]="McDonald-dela Cruz";c[3]="peña";assertThat(row(c).values()).containsEntry("lastName","McDonald-dela Cruz").containsEntry("firstName","Peña");
        c[2]="Reyes III";c[3]="JUAN IV";assertThat(row(c).values()).containsEntry("lastName","Reyes III").containsEntry("firstName","Juan IV");
        var off=normalizer("{\"encoding\":\"UTF-8\",\"delimiter\":\",\",\"headerRow\":1,\"normalizeNameCase\":false}",1).normalize(List.of("SYN-1","","DELA CRUZ","ANA","","2009-03-14","","","QVR","STEM",""),columns());
        assertThat(off.values()).containsEntry("lastName","DELA CRUZ");
    }
    @Test void mapsCanonicalCodesCaseInsensitivelyAndConfiguredValueMapsButNothingElse()throws Exception{
        var c=cells("2009-03-14");c[8]="q";c[9]="tvet_x";c[6]="f";var r=row(c);
        assertThat(r.values()).containsEntry("voucherCode","QVR").containsEntry("programCode","TVET_X").containsEntry("track","TVET").containsEntry("sex","Female");
        c[8]="esc";c[9]="Tvl_Ict";assertThat(row(c).values()).containsEntry("voucherCode","ESC").containsEntry("track","TVL");
        c[9]="stem";assertThat(row(c).values()).containsEntry("track","ACADEMIC");
        c[8]="Qualified Voucher Recipient";assertThat(codes(row(c))).containsExactly("UNMAPPED_VALUE");
        c[8]="QVR";c[6]="other";assertThat(codes(row(c))).containsExactly("UNMAPPED_VALUE");
        c[6]="";c[9]="";c[8]="";assertThat(codes(row(c))).containsExactly("REQUIRED_MISSING","REQUIRED_MISSING");
    }
    @Test void campusColumnIsOptionalAndAMismatchWithTheUploadCampusIsARowError()throws Exception{
        var c=cells("2009-03-14");assertThat(row(c).valid()).as("blank campus uses the upload's campus").isTrue();
        c[10]="iligan";assertThat(row(c).valid()).isTrue();
        c[10]="BUGO";var mismatch=row(c);assertThat(codes(mismatch)).containsExactly("CAMPUS_MISMATCH");assertThat(mismatch.fileCampusId()).isEqualTo(3L);
        c[10]="Nowhere";assertThat(codes(row(c))).containsExactly("UNKNOWN_CAMPUS");
        assertThat(row(c).values()).containsEntry("campusId",1L);
    }
    @Test void rejectsColumnCountMismatchTooLongValuesAndControlCharacters()throws Exception{
        assertThat(codes(normalizer(SETTINGS,1).normalize(List.of("SYN-1","x"),columns()))).containsExactly("COLUMN_COUNT_MISMATCH");
        var c=cells("2009-03-14");c[7]="x".repeat(251);c[3]="a".repeat(151);assertThat(codes(row(c))).containsExactly("TOO_LONG","TOO_LONG");
        c=cells("2009-03-14");c[2]="San\u0007tos";assertThat(codes(row(c))).contains("INVALID_CHARACTER");
        c=cells("2009-03-14");c[0]="N".repeat(41);assertThat(codes(row(c))).containsExactly("TOO_LONG");
    }
    @Test void dateFormatListRulesAreEnforcedOnSave()throws Exception{
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"yyyy-MM-dd\",\"MM/dd/yyyy\"]"))).isEmpty();
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"MM/dd/yyyy\",\"dd/MM/yyyy\"]"))).anyMatch(p->p.contains("ambiguous"));
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"MM/dd/yyyy\",\"dd-MM-yyyy\"]"))).as("different separators cannot collide").isEmpty();
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"yyyy-MM-dd\",\"yyyy-dd-MM\"]"))).anyMatch(p->p.contains("ambiguous"));
        for(String bad:List.of("[]","[\"MM/dd/yy\"]","[\"dd MMM yyyy\"]","[\"yyyy-MM-dd\",\"yyyy-MM-dd\"]","[\"yyyy-yyyy-dd\"]","[1]","\"yyyy-MM-dd\""))assertThat(ImportMappingService.dateProblems(JSON.readTree(bad))).as(bad).isNotEmpty();
    }
}
