import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';

const migrations = new URL('../../backend/src/main/resources/db/migration/', import.meta.url);

test('migrations apply and preserve institutional data integrity', async t => {
  const db = new PGlite();
  try {
    for (const name of (await readdir(migrations)).filter(name => name.endsWith('.sql')).sort((a,b)=>Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]))) {
      await db.exec(await readFile(new URL(name, migrations), 'utf8'));
    }
    const query = async sql => (await db.query(sql)).rows;
    await t.test('reference data includes three campuses and seven roles', async () => {
      assert.equal((await query('SELECT count(*)::int n FROM campuses'))[0].n, 3);
      assert.equal((await query('SELECT count(*)::int n FROM roles'))[0].n, 7);
      assert.equal((await query('SELECT count(*)::int n FROM users'))[0].n, 0);
    });
    await db.exec(`
      INSERT INTO users(username,email,full_name,password_hash) VALUES ('registrar','registrar@example.invalid','Test Registrar','TEST_ONLY_NOT_A_REAL_PASSWORD');
      SET app.actor_id = '1';
      INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('2026-2027','2026-06-01','2027-04-30');
      INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (1,'S1','Semester 1','2026-06-01','2026-10-31'),(1,'S2','Semester 2','2026-11-01','2027-04-30');
      INSERT INTO grading_periods(term_id,code,sequence) VALUES (1,'Q1',1),(2,'Q3',1);
      INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (1,1,1,'STEM A','11',1),(2,1,1,'STEM B','11',10);
      INSERT INTO students(student_number,lrn,first_name,last_name,birth_date) VALUES ('STU-1','123456789012','Ana','Test','2009-01-01'),('STU-2','123456789013','Ben','Test','2009-02-01');
      INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,lrn) VALUES ('APP-1',1,1,1,1,'new','Ana','Test','2009-01-01','123456789012'),('APP-2',1,1,1,2,'new','Ben','Test','2009-02-01','123456789012');
      INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id) VALUES (1,1,1,1,1,1);
    `);
    await t.test('duplicate applicant LRN is allowed but permanent identity is unique', async () => {
      assert.equal((await query("SELECT count(*)::int n FROM applications WHERE lrn='123456789012'"))[0].n, 2);
      await assert.rejects(db.exec("UPDATE students SET lrn='123456789012' WHERE id=2"), /unique/i);
    });
    await t.test('malformed LRN and duplicate case without a target are rejected', async () => {
      await assert.rejects(db.exec("UPDATE students SET lrn='abc' WHERE id=2"), /check/i);
      await assert.rejects(db.exec('INSERT INTO duplicate_cases(application_id) VALUES(1)'), /check/i);
    });
    await t.test('section overbooking is rejected', async () => {
      await assert.rejects(db.exec('INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id) VALUES (2,2,1,1,1,1)'), /capacity/i);
    });
    await t.test('section capacity cannot be reduced below occupancy', async () => {
      await assert.rejects(db.exec('UPDATE sections SET capacity=0 WHERE id=1'), /capacity/i);
    });
    await t.test('cross-campus enrollment section is rejected', async () => {
      await assert.rejects(db.exec('UPDATE enrollments SET section_id=2 WHERE student_id=1'), /foreign key/i);
    });
    await t.test('enrollment must match its linked application identity', async () => {
      await assert.rejects(db.exec('UPDATE enrollments SET student_id=2 WHERE student_id=1'), /linked/i);
    });
    const confirm = `UPDATE enrollments SET status='enrolled',confirmed_at=now(),confirmed_by=1,attendance_verified_at=now(),attendance_verified_by=1 WHERE student_id=1`;
    await t.test('confirmation requires attendance and every mandatory credential', async () => {
      await assert.rejects(db.exec(confirm), /attendance/i);
      await db.exec("INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by) VALUES (1,'2026-06-01','present',1)");
      await assert.rejects(db.exec(confirm), /credentials/i);
      await db.exec("INSERT INTO application_requirements(application_id,requirement_code,status,verified_by) SELECT 1,code,'submitted',1 FROM requirement_types");
      await db.exec(confirm);
      assert.equal((await query('SELECT status FROM enrollments WHERE student_id=1'))[0].status,'enrolled');
    });
    await db.exec(`INSERT INTO subjects(code,name) VALUES ('MATH','Mathematics');
      INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES(1,1,1,1);
      INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) SELECT 1,id,1,1 FROM enrollments WHERE student_id=1;
      INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state,updated_by) SELECT 1,id,1,1,89,'submitted',1 FROM enrollments WHERE student_id=1;`);
    await t.test('cross-term grade periods are rejected', async () => {
      await assert.rejects(db.exec('UPDATE grades SET grading_period_id=2'), /foreign key/i);
      await assert.rejects(db.exec('INSERT INTO grading_windows(class_id,grading_period_id) VALUES (1,2)'), /term/i);
    });
    await t.test('invalid grades and numeric plus nonnumeric marks are rejected', async () => {
      await assert.rejects(db.exec('UPDATE grades SET value=101'), /check/i);
      await assert.rejects(db.exec("UPDATE grades SET mark='INC'"), /check/i);
    });
    await t.test('credential and grade audit entries preserve actor and previous values', async () => {
      await db.exec('UPDATE grades SET value=90');
      const audit=(await query("SELECT * FROM audit_logs WHERE table_name='grades' AND operation='UPDATE'"))[0];
      assert.equal(audit.old_data.value,89); assert.equal(audit.new_data.value,90); assert.equal(Number(audit.actor_id),1);
      assert.equal((await query("SELECT count(*)::int n FROM audit_logs WHERE table_name='application_requirements'"))[0].n,4);
    });
    await t.test('audit update, deletion and truncation are rejected', async () => {
      for (const sql of ["UPDATE audit_logs SET operation='EDIT'",'DELETE FROM audit_logs','TRUNCATE audit_logs']) await assert.rejects(db.exec(sql), /append-only/i);
    });
    await t.test('unreconciled import cannot be signed off', async () => {
      await assert.rejects(db.exec("INSERT INTO import_batches(source_name,checksum,status,source_count) VALUES('test','abc','reconciled',10)"), /check/i);
    });
  } finally { await db.close(); }
});
