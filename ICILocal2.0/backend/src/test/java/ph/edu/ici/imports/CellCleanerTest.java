package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** ICI2-39 phase 2: every cleaning rule and every never-guess error, as pure unit tests (no database). */
class CellCleanerTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static CleaningProfile profile(String settings)throws Exception{return CleaningProfile.parse(JSON.readTree(settings));}
    static CellCleaner cleaner(String cleaning)throws Exception{return new CellCleaner(profile("{\"cleaning\":"+cleaning+"}"));}
    CleaningLog log=new CleaningLog();
    String clean(CellCleaner c,String raw,String field){return c.clean(raw,field,"Col "+field,null,log);}
    List<String> codes(){return log.problems().stream().map(RowProblem::code).toList();}
    List<String> rules(){return log.entries().stream().map(e->(String)e.get("rule")).toList();}

    // ---- default profile reproduces ImportText.clean exactly ------------------------------------------------------------------
    @Test void defaultProfileReturnsExactlyWhatImportTextCleanReturned()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);
        for(String s:new String[]{"","   ","a"," a ","a  b","a\tb","a \t\r\n b","é"," x","x​","　x","Ｆｕｌｌ","  Escuela   Synthetic ","ÁÉÍ  Ñ"," ","a  b"}){
            assertThat(c.clean(s,"lastName","Last",null,new CleaningLog())).as("[%s]".formatted(s.replace(" ","<nbsp>").replace("​","<zwsp>"))).isEqualTo(ImportText.clean(s));
        }
        assertThat(c.clean(null,"lastName","Last",null,log)).isNull();
    }

    // ---- trivial rules: counted, not logged -----------------------------------------------------------------------------------
    @Test void trivialChangesAreCountedPerRuleAndNeverLoggedPerCell()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);
        assertThat(clean(c,"  Ana   Maria ","firstName")).isEqualTo("Ana Maria");
        assertThat(clean(c,"é","firstName")).isEqualTo("é");
        assertThat(log.counts()).containsEntry("trim",1).containsEntry("collapse_spaces",1).containsEntry("unicode_nfc",1);
        assertThat(log.entries()).as("trivial rules are only counted").isEmpty();
        assertThat(CleaningLog.TRIVIAL).containsExactlyInAnyOrder("unicode_nfc","trim","collapse_spaces","unicode_spaces","strip_invisible");
    }
    @Test void aBlankCellCountsNothingAndAnUnchangedCellCountsNothing()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);
        assertThat(clean(c,"   ","x")).isNull();assertThat(clean(c,"","x")).isNull();assertThat(clean(c,"Plain","x")).isEqualTo("Plain");
        assertThat(log.counts()).isEmpty();
    }
    @Test void unicodeSpacesAreOptInAndRecordedAsATrivialRule()throws Exception{
        String raw="DELA CRUZ Jr";
        assertThat(clean(new CellCleaner(CleaningProfile.DEFAULT),raw,"lastName")).as("off: today's behavior keeps U+00A0").isEqualTo(raw);
        assertThat(log.counts()).doesNotContainKey("unicode_spaces");
        assertThat(clean(cleaner("{\"unicodeSpaces\":true}"),raw,"lastName")).isEqualTo("DELA CRUZ Jr");
        assertThat(log.counts()).containsEntry("unicode_spaces",1);assertThat(log.entries()).isEmpty();
        assertThat(clean(cleaner("{\"unicodeSpaces\":true}")," x ","lastName")).as("edge spaces are then trimmed").isEqualTo("x");
    }
    @Test void invisibleCharactersAreOptInAndRecordedAsATrivialRule()throws Exception{
        String raw="Ana​﻿⁠";
        assertThat(clean(new CellCleaner(CleaningProfile.DEFAULT),raw,"firstName")).as("off: zero-width characters survive today").isEqualTo(raw);
        assertThat(clean(cleaner("{\"stripInvisible\":true}"),raw,"firstName")).isEqualTo("Ana");
        assertThat(log.counts()).containsEntry("strip_invisible",1);assertThat(log.entries()).isEmpty();
        assertThat(clean(cleaner("{\"stripInvisible\":true,\"unicodeSpaces\":true}"),"​ ","firstName")).as("only invisible or space characters is blank").isNull();
    }

    // ---- non-trivial rules: logged with raw value, cleaned value and rule -----------------------------------------------------
    @Test void placeholdersBecomeBlankAreLoggedAndWarnButOnlyWhenConfigured()throws Exception{
        assertThat(clean(new CellCleaner(CleaningProfile.DEFAULT),"N/A","previousSchool")).as("off by default").isEqualTo("N/A");
        var c=cleaner("{\"blankValues\":[\"n/a\",\"-\"],\"fieldBlankValues\":{\"lrn\":[\"000000000000\"]}}");
        assertThat(clean(c,"  N/A ","previousSchool")).isNull();
        assertThat(log.entries()).singleElement().satisfies(e->{
            assertThat(e).containsEntry("field","previousSchool").containsEntry("column","Col previousSchool").containsEntry("rule","placeholder_to_blank").containsEntry("before","N/A").containsEntry("after",null);});
        assertThat(codes()).containsExactly("VALUE_CLEANED");assertThat(log.problems().getFirst().severity()).isEqualTo(RowProblem.WARNING);
        assertThat(log.problems().getFirst().detail()).as("messages never echo the value").doesNotContain("N/A");
        assertThat(clean(c,"000000000000","lrn")).as("per-field placeholder").isNull();
        assertThat(clean(c,"000000000000","studentNumber")).as("a per-field placeholder applies to that field only").isEqualTo("000000000000");
        assertThat(clean(c,"n/a school","previousSchool")).as("whole value only, never a substring").isEqualTo("n/a school");
        assertThat(clean(c,"\uFF0D","previousSchool")).as("compared after NFKC like value-map keys, so a full-width hyphen is the placeholder '-'").isNull();
    }
    @Test void aPlaceholderInAGradeCellIsBlankNeverAMark()throws Exception{
        var c=cleaner("{\"blankValues\":[\"n/a\"]}");
        var cell=c.gradeCell("N/A","SYNMATH - Q1",null,log);
        assertThat(cell.value()).isNull();assertThat(cell.rejected()).as("blank means no record, not an error").isFalse();
        assertThat(codes()).containsExactly("VALUE_CLEANED");
        var off=new CellCleaner(CleaningProfile.DEFAULT).gradeCell("N/A","SYNMATH - Q1",null,new CleaningLog());assertThat(off.value()).isEqualTo("N/A");
    }
    @Test void nameCaseAndParticlesAreLoggedAsTwoSteps()throws Exception{
        var c=cleaner("{\"nameParticles\":[\"de\",\"la\"]}");
        assertThat(c.nameCase("DE LA CRUZ","lastName","Last",log)).isEqualTo("de la Cruz");
        assertThat(rules()).containsExactly("name_case","name_particles");
        assertThat(log.entries().get(0)).containsEntry("before","DE LA CRUZ").containsEntry("after","De La Cruz");
        assertThat(log.entries().get(1)).containsEntry("before","De La Cruz").containsEntry("after","de la Cruz");
        log=new CleaningLog();assertThat(c.nameCase("McDonald","lastName","Last",log)).as("mixed case is never touched").isEqualTo("McDonald");assertThat(log.entries()).isEmpty();
        log=new CleaningLog();assertThat(new CellCleaner(CleaningProfile.DEFAULT).nameCase("DE LA CRUZ","lastName","Last",log)).as("no particles configured").isEqualTo("De La Cruz");assertThat(rules()).containsExactly("name_case");
    }
    @Test void lrnSeparatorsAreStrippedOnlyWhenConfiguredAndOnlyForTheLrn()throws Exception{
        assertThat(clean(new CellCleaner(CleaningProfile.DEFAULT),"9971 0000-0101","lrn")).isEqualTo("9971 0000-0101");
        var c=cleaner("{\"lrnStripSeparators\":true}");
        assertThat(clean(c,"9971 0000-0101","lrn")).isEqualTo("997100000101");
        assertThat(log.entries()).singleElement().satisfies(e->assertThat(e).containsEntry("rule","lrn_strip_separators").containsEntry("before","9971 0000-0101").containsEntry("after","997100000101"));
        assertThat(clean(c,"SYN 1-2","studentNumber")).isEqualTo("SYN 1-2");
    }
    @Test void numericIdentifiersArePaddedOnlyWhenTheCellWasANumberAndAWidthIsConfigured()throws Exception{
        var c=cleaner("{\"padNumeric\":{\"studentNumber\":8}}");
        assertThat(c.clean("123","studentNumber","No",CellMeta.number(),log)).isEqualTo("00000123");
        assertThat(log.entries()).singleElement().satisfies(e->assertThat(e).containsEntry("rule","pad_numeric_id").containsEntry("before","123").containsEntry("after","00000123"));
        assertThat(codes()).containsExactly("VALUE_PADDED");
        log=new CleaningLog();assertThat(c.clean("123","studentNumber","No",null,log)).as("text cells are never padded: that would be a guess").isEqualTo("123");
        assertThat(c.clean("123","lrn","LRN",CellMeta.number(),log)).as("no width configured for lrn").isEqualTo("123");
        assertThat(c.clean("123456789","studentNumber","No",CellMeta.number(),log)).as("already wide enough").isEqualTo("123456789");
    }

    // ---- spreadsheet cells: render plainly or refuse, never guess -------------------------------------------------------------
    @Test void integralNumericIdentifiersBecomePlainDigitsAndFractionsAreRefused()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);
        assertThat(c.clean("1.23456789012E11","lrn","LRN",CellMeta.number(),log)).isEqualTo("123456789012");
        assertThat(c.clean("2019001.0","studentNumber","No",CellMeta.number(),log)).isEqualTo("2019001");
        assertThat(rules()).containsExactly("xlsx_number_to_text","xlsx_number_to_text");
        log=new CleaningLog();assertThat(c.clean("12.5","studentNumber","No",CellMeta.number(),log)).isNull();assertThat(codes()).containsExactly("NUMERIC_NOT_INTEGRAL");
        log=new CleaningLog();assertThat(c.clean("-5","studentNumber","No",CellMeta.number(),log)).isNull();assertThat(codes()).containsExactly("NUMERIC_NOT_INTEGRAL");
        log=new CleaningLog();assertThat(c.clean("12.5","previousSchool","School",CellMeta.number(),log)).as("only identifiers are converted").isEqualTo("12.5");assertThat(codes()).isEmpty();
    }
    @Test void formulaCellsAreReadAsCachedValuesWithAWarningAndNeverWithoutOne()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);
        assertThat(c.clean("Cruz","lastName","Last",new CellMeta(CellMeta.Kind.TEXT,CellMeta.Format.NONE,true,false),log)).isEqualTo("Cruz");
        assertThat(codes()).containsExactly("FORMULA_CACHED");assertThat(log.problems().getFirst().severity()).isEqualTo(RowProblem.WARNING);
        assertThat(rules()).containsExactly("formula_cached");
        log=new CleaningLog();assertThat(c.clean("","lastName","Last",new CellMeta(CellMeta.Kind.TEXT,CellMeta.Format.NONE,true,true),log)).isNull();
        assertThat(codes()).containsExactly("FORMULA_NO_CACHED_VALUE");assertThat(log.problems().getFirst().severity()).isEqualTo(RowProblem.ERROR);
    }
    @Test void spreadsheetErrorCellsAreErrorsNotBlanks()throws Exception{
        assertThat(new CellCleaner(CleaningProfile.DEFAULT).clean("#N/A","lastName","Last",CellMeta.error(),log)).isNull();
        assertThat(codes()).containsExactly("ERROR_CELL");assertThat(log.problems().getFirst().severity()).isEqualTo(RowProblem.ERROR);
        assertThat(log.hasError("lastName")).isTrue();assertThat(log.hasError("firstName")).isFalse();
    }
    @Test void nativeDateCellsAreAcceptedAndLoggedUnlessTheMappingRejectsThem()throws Exception{
        assertThat(new CellCleaner(CleaningProfile.DEFAULT).clean("2009-03-14","birthDate","Birth",CellMeta.date(),log)).isEqualTo("2009-03-14");
        assertThat(rules()).containsExactly("xlsx_native_date");
        log=new CleaningLog();var reject=new CellCleaner(profile("{\"nativeDates\":\"reject\"}"));
        assertThat(reject.clean("2009-03-14","birthDate","Birth",CellMeta.date(),log)).isNull();assertThat(codes()).containsExactly("NATIVE_DATE_REJECTED");
    }
    @Test void gradeCellsRefusePercentAndDateFormatsErrorsAndMissingFormulaResults()throws Exception{
        var c=new CellCleaner(CleaningProfile.DEFAULT);String col="SYNMATH - Q1";
        var pct=c.gradeCell("0.85",col,CellMeta.number(CellMeta.Format.PERCENT),log);assertThat(pct.rejected()).isTrue();assertThat(pct.value()).isNull();assertThat(codes()).containsExactly("PERCENT_FORMAT");
        log=new CleaningLog();assertThat(c.gradeCell("43905",col,CellMeta.number(CellMeta.Format.DATE),log).rejected()).isTrue();assertThat(codes()).containsExactly("DATE_IN_GRADE_CELL");
        log=new CleaningLog();assertThat(c.gradeCell("2020-03-14",col,CellMeta.date(),log).rejected()).isTrue();assertThat(codes()).containsExactly("DATE_IN_GRADE_CELL");
        log=new CleaningLog();assertThat(c.gradeCell("#DIV/0!",col,CellMeta.error(),log).rejected()).isTrue();assertThat(codes()).containsExactly("ERROR_CELL");
        log=new CleaningLog();assertThat(c.gradeCell("",col,CellMeta.number().asFormula(true),log).rejected()).isTrue();assertThat(codes()).containsExactly("FORMULA_NO_CACHED_VALUE");
        log=new CleaningLog();var ok=c.gradeCell("88.5",col,CellMeta.number().asFormula(false),log);
        assertThat(ok.rejected()).isFalse();assertThat(ok.value()).isEqualTo("88.5");assertThat(codes()).containsExactly("FORMULA_CACHED");
        log=new CleaningLog();var plain=c.gradeCell("88.5",col,CellMeta.number(CellMeta.Format.OTHER),log);assertThat(plain.value()).isEqualTo("88.5");assertThat(codes()).isEmpty();
        assertThat(log.problems().stream().map(RowProblem::detail)).noneMatch(d->d.contains("88"));
    }

    // ---- log limits ------------------------------------------------------------------------------------------------------------
    @Test void theLogIsCappedPerRowAndLongValuesAreTruncatedButEveryChangeIsStillCounted()throws Exception{
        var c=cleaner("{\"lrnStripSeparators\":true}");
        for(int i=0;i<60;i++)c.clean("9971-0000-01"+String.format("%02d",i%100),"lrn","LRN",null,log);
        assertThat(log.entries()).hasSize(CleaningLog.MAX_ENTRIES+1);
        assertThat(log.entries().getLast()).containsEntry("rule","MORE_CHANGES");
        assertThat(log.counts()).containsEntry("lrn_strip_separators",60);
        log=new CleaningLog();String longValue="x".repeat(500);
        cleaner("{\"blankValues\":[\""+longValue.substring(0,100)+"\"]}").clean(longValue.substring(0,100),"previousSchool","School",null,log);
        log=new CleaningLog();log.changed("f","c","r",longValue,longValue);
        assertThat(log.entries().getFirst()).containsEntry("beforeTruncated",true).containsEntry("afterTruncated",true);
        assertThat(((String)log.entries().getFirst().get("before"))).hasSize(CleaningLog.MAX_TEXT);
    }

    // ---- settings parse and validation (no database) --------------------------------------------------------------------------
    List<String> problems(String settings,String valueMaps,String... fields)throws Exception{
        List<String> out=new ArrayList<>();CleaningProfile.validate(JSON.readTree(settings),List.of(fields.length==0?new String[]{"studentNumber","lrn","lastName","firstName","birthDate","sex"}:fields),JSON.readTree(valueMaps),out);return out;
    }
    @Test void absentKeysMeanTodaysBehaviorAndValidSettingsParse()throws Exception{
        assertThat(profile("{}")).isEqualTo(CleaningProfile.DEFAULT);
        assertThat(problems("{}","{}")).isEmpty();assertThat(problems("{\"cleaning\":{}}","{}")).isEmpty();
        var p=profile("{\"nativeDates\":\"reject\",\"cleaning\":{\"unicodeSpaces\":true,\"blankValues\":[\" N/A \"],\"fieldBlankValues\":{\"lrn\":[\"0\"]},\"nameParticles\":[\"De\"],\"padNumeric\":{\"studentNumber\":8},\"extendedDateFormats\":true}}");
        assertThat(p.unicodeSpaces()).isTrue();assertThat(p.extendedDateFormats()).isTrue();assertThat(p.nativeDatesAccepted()).isFalse();
        assertThat(p.blankValues()).containsExactly("n/a");assertThat(p.fieldBlankValues().get("lrn")).containsExactly("0");assertThat(p.nameParticles()).containsExactly("de");assertThat(p.padNumeric()).containsEntry("studentNumber",8);
        assertThat(problems("{\"nativeDates\":\"reject\",\"sheet\":\"Students\",\"cleaning\":{\"unicodeSpaces\":true,\"blankValues\":[\"n/a\"],\"fieldBlankValues\":{\"lrn\":[\"0\"]},\"nameParticles\":[\"de\"],\"padNumeric\":{\"studentNumber\":8},\"extendedDateFormats\":true,\"lrnStripSeparators\":false}}","{}")).isEmpty();
    }
    @Test void unknownKeysAndBadValuesAreRejectedWithEveryProblemListed()throws Exception{
        assertThat(problems("{\"cleaning\":{\"surprise\":1}}","{}")).anyMatch(s->s.contains("Unknown cleaning key 'surprise'"));
        assertThat(problems("{\"cleaning\":[]}","{}")).anyMatch(s->s.contains("must be an object"));
        assertThat(problems("{\"cleaning\":{\"unicodeSpaces\":\"yes\",\"stripInvisible\":1}}","{}")).hasSize(2);
        assertThat(problems("{\"nativeDates\":\"maybe\"}","{}")).anyMatch(s->s.contains("nativeDates"));
        assertThat(problems("{\"sheet\":\"\"}","{}")).anyMatch(s->s.contains("sheet"));assertThat(problems("{\"sheet\":5}","{}")).anyMatch(s->s.contains("sheet"));
        assertThat(problems("{\"cleaning\":{\"blankValues\":[\"x\",\" X \"]}}","{}")).anyMatch(s->s.contains("more than once"));
        assertThat(problems("{\"cleaning\":{\"blankValues\":[\"\"]}}","{}")).anyMatch(s->s.contains("empty"));
        assertThat(problems("{\"cleaning\":{\"blankValues\":\"n/a\"}}","{}")).anyMatch(s->s.contains("blankValues"));
        assertThat(problems("{\"cleaning\":{\"fieldBlankValues\":{\"nickname\":[\"x\"]}}}","{}")).anyMatch(s->s.contains("unknown field"));
        assertThat(problems("{\"cleaning\":{\"nameParticles\":[\"d3\"]}}","{}")).anyMatch(s->s.contains("nameParticles"));
        assertThat(problems("{\"cleaning\":{\"padNumeric\":{\"lastName\":8}}}","{}")).anyMatch(s->s.contains("identifier field"));
        assertThat(problems("{\"cleaning\":{\"padNumeric\":{\"studentNumber\":0}}}","{}")).anyMatch(s->s.contains("1 to 40"));
        assertThat(problems("{\"cleaning\":{\"padNumeric\":{\"studentNumber\":41}}}","{}")).anyMatch(s->s.contains("1 to 40"));
    }
    @Test void aPlaceholderCanNeverAlsoBeAValueMapKey()throws Exception{
        String maps="{\"sex\":{\"N/A\":\"Unknown\"},\"mark\":{\"Inc.\":\"INC\"}}";
        assertThat(problems("{\"cleaning\":{\"blankValues\":[\"n/a\"]}}",maps)).anyMatch(s->s.contains("also a key of a value map"));
        assertThat(problems("{\"cleaning\":{\"blankValues\":[\"inc.\"]}}",maps)).anyMatch(s->s.contains("also a key of a value map"));
        assertThat(problems("{\"cleaning\":{\"fieldBlankValues\":{\"sex\":[\"n/a\"]}}}",maps)).anyMatch(s->s.contains("also a key of valueMaps.sex"));
        assertThat(problems("{\"cleaning\":{\"fieldBlankValues\":{\"lrn\":[\"n/a\"]}}}",maps)).as("a per-field placeholder only collides with that field's map").isEmpty();
    }

    // ---- extended date formats (RowNormalizer) ------------------------------------------------------------------------------
    static final String MAPPING="""
        {"fields":{"studentNumber":{"required":true,"aliases":["student_no"]},"lastName":{"required":true,"aliases":["last_name"]},"firstName":{"required":true,"aliases":["first_name"]},
         "birthDate":{"required":true,"aliases":["birth_date"]},"voucher":{"required":true,"aliases":["voucher"]},"specialization":{"required":true,"aliases":["strand"]}},"valueMaps":{}}""";
    static final ReferenceData REF=new ReferenceData(Map.of("iligan",new ReferenceData.Ref(1,"ILIGAN",null)),Map.of("stem",new ReferenceData.Ref(10,"STEM","SHS")),Map.of("qvr",new ReferenceData.Ref(0,"QVR",null)));
    static final Map<String,Integer> IDX=Map.of("studentNumber",0,"lastName",1,"firstName",2,"birthDate",3,"voucher",4,"specialization",5);
    RowNormalizer.Result row(String settings,String birth,Map<Integer,CellMeta> meta,CleaningLog l)throws Exception{
        var mapping=ImportMapping.parse(JSON.readTree(MAPPING),JSON.readTree(settings));
        var n=new RowNormalizer(mapping,REF,1,LocalDate.of(2026,11,19),List.of("Student No","Last Name","First Name","Birth Date","Voucher","Strand"));
        return n.normalize(List.of("SYN-1","Santos","Ana",birth,"QVR","STEM"),IDX,meta,l);
    }
    static final String EXT="{\"dateFormats\":[\"yyyy-MM-dd\",\"M/d/yyyy\",\"d MMM yyyy\",\"MMMM d, yyyy\"],\"cleaning\":{\"extendedDateFormats\":true}}";
    @Test void extendedDateFormatsParseSingleDigitsAndEnglishMonthNamesAndLogTheConversion()throws Exception{
        for(var e:Map.of("3/4/2009","2009-03-04","14 Mar 2009","2009-03-14","14 mar 2009","2009-03-14","March 14, 2009","2009-03-14","september 5, 2009","2009-09-05","2009-03-14","2009-03-14").entrySet()){
            var l=new CleaningLog();var r=row(EXT,e.getKey(),Map.of(),l);
            assertThat(r.values()).as(e.getKey()).containsEntry("birthDate",e.getValue());assertThat(r.valid()).as(e.getKey()).isTrue();
            if(!e.getKey().equals("2009-03-14"))assertThat(l.entries()).as(e.getKey()).anyMatch(x->String.valueOf(x.get("rule")).startsWith("date_parsed:")&&x.get("before").equals(e.getKey())&&x.get("after").equals(e.getValue())&&"Birth Date".equals(x.get("column")));
            else assertThat(l.entries()).as("a classic format needs no log entry").isEmpty();
        }
        for(String bad:List.of("14 March 2009","Mar 14 2009","31 Feb 2009","14 Foo 2009","3/4/09","13/4/2009x","2009-3-4"))
            assertThat(row(EXT,bad,Map.of(),new CleaningLog()).problems()).as(bad).extracting(RowProblem::code).containsExactly("INVALID_DATE");
        assertThat(row("{\"dateFormats\":[\"yyyy-MM-dd\"]}","3/4/2009",Map.of(),new CleaningLog()).problems()).extracting(RowProblem::code).as("without the opt-in formats nothing changes").containsExactly("INVALID_DATE");
    }
    @Test void aDateReadableInTwoConfiguredFormatsIsStillAmbiguousNeverGuessed()throws Exception{
        var s="{\"dateFormats\":[\"M/d/yyyy\",\"d.M.yyyy\"],\"cleaning\":{\"extendedDateFormats\":true}}";
        assertThat(row(s,"3/4/2009",Map.of(),new CleaningLog()).values()).containsEntry("birthDate","2009-03-04");
        assertThat(row(s,"3.4.2009",Map.of(),new CleaningLog()).values()).containsEntry("birthDate","2009-04-03");
        // M/d/yyyy and MM/dd/yyyy mean the same thing, so a value matching both is not ambiguous
        assertThat(row("{\"dateFormats\":[\"M/d/yyyy\",\"MM/dd/yyyy\"],\"cleaning\":{\"extendedDateFormats\":true}}","03/04/2009",Map.of(),new CleaningLog()).values()).containsEntry("birthDate","2009-03-04");
        // the swapped pair is refused when the mapping is saved
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"M/d/yyyy\",\"d/M/yyyy\"]"),true)).anyMatch(p->p.contains("ambiguous"));
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"MM/dd/yyyy\",\"d/M/yyyy\"]"),true)).anyMatch(p->p.contains("ambiguous"));
    }
    @Test void extendedPatternsAreRefusedOnSaveUnlessTheMappingOptsIn()throws Exception{
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"d MMM yyyy\"]"))).isNotEmpty();
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"d MMM yyyy\"]"),false)).isNotEmpty();
        assertThat(ImportMappingService.dateProblems(JSON.readTree("[\"d MMM yyyy\",\"MMMM d, yyyy\",\"M/d/yyyy\",\"yyyy-MM-dd\"]"),true)).isEmpty();
        for(String bad:List.of("[\"d MMM yy\"]","[\"d MMM\"]","[\"yyyyMMdd\"]","[\"d d yyyy\"]","[\"M/d/yyyy/x\"]","[\"d MMM yyyy\",\"d MMM yyyy\"]","[\"MMM MMM yyyy\"]"))
            assertThat(ImportMappingService.dateProblems(JSON.readTree(bad),true)).as(bad).isNotEmpty();
    }
    @Test void spreadsheetDateCellsBypassConfiguredFormatsAndAreRefusedWhenRejected()throws Exception{
        var l=new CleaningLog();var r=row("{\"dateFormats\":[\"MM/dd/yyyy\"]}","2009-03-14",Map.of(3,CellMeta.date()),l);
        assertThat(r.values()).containsEntry("birthDate","2009-03-14");assertThat(r.valid()).isTrue();assertThat(l.entries()).extracting(e->e.get("rule")).containsExactly("xlsx_native_date");
        var rejected=row("{\"dateFormats\":[\"MM/dd/yyyy\"],\"nativeDates\":\"reject\"}","2009-03-14",Map.of(3,CellMeta.date()),new CleaningLog());
        assertThat(rejected.problems()).extracting(RowProblem::code).containsExactly("NATIVE_DATE_REJECTED");
        assertThat(row("{\"dateFormats\":[\"MM/dd/yyyy\"]}","2999-01-01",Map.of(3,CellMeta.date()),new CleaningLog()).problems()).extracting(RowProblem::code).as("the same bounds as typed dates").containsExactly("FUTURE_DATE");
        assertThat(row("{\"dateFormats\":[\"MM/dd/yyyy\"]}","1899-12-31",Map.of(3,CellMeta.date()),new CleaningLog()).problems()).extracting(RowProblem::code).containsExactly("INVALID_DATE");
    }
    @Test void aRefusedCellDoesNotAlsoReportItselfAsMissing()throws Exception{
        var r=row("{\"dateFormats\":[\"yyyy-MM-dd\"]}","#N/A",Map.of(3,CellMeta.error()),new CleaningLog());
        assertThat(r.problems()).extracting(RowProblem::code).containsExactly("ERROR_CELL");
    }
}
