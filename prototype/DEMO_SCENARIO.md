# Cross-Module Transferee Demo Scenario

## Scenario Overview

Carlo Bautista is a 17-year-old Grade 11 transferee applying to the TVL–Culinary Arts program at ICI’s Bugo — CDO campus. His family recently moved closer to Bugo, so Carlo is transferring from Iligan City National High School to continue his senior high school studies.

Carlo submits his application with LRN `121456789012`, application ID `APP-2026-0153`, and the following initial information:

- Category: Transferee
- Track and specialization: TVL — Culinary Arts
- Campus: Bugo — CDO
- Previous school: Iligan City National High School
- Guardian: Edwin Bautista
- Initial documents: PSA Birth Certificate and Certificate of Completion available; Form 137 still being requested from the previous school; Good Moral Certificate to follow

During encoding, the system identifies a possible existing record under **Carlos D. Bautista**, student ID `STU-2023-1210`. The names and legacy LRN differ, but the birth date, sex, previous school, and guardian contact match. The registrar verifies the identity with the PSA document and guardian, then merges the records instead of creating a second student history.

The live demonstration follows these actors:

| Actor | Responsibility in the scenario |
| --- | --- |
| Carlo Bautista | Submits the transferee application |
| R. Santos, Registrar Staff | Validates the application, requirements, attendance, enrollment, and reports |
| Nicole Chua, Teacher Adviser | Reviews Carlo’s synchronized class record and adds a monitoring note |
| Michael Reyes, Subject Teacher | Encodes Carlo’s Cookery Fundamentals scores |
| Angela Cruz, Guidance Staff | Reviews the at-risk report and records an intervention |

The objective is to show that one verified student record moves from enrollment validation to academic monitoring and finally to a documented intervention.

## Demo Flow

### Step 1 — Submit the transferee application

**Module:** Enrollment Management  
**User:** Carlo Bautista / Prospective Student  
**Situation:** Carlo is applying to ICI after transferring from Iligan City National High School.

**User Action:**

1. Open **Enrollment Submission**.
2. Select **Transferee — Duplicate Flag** under Scenario.
3. Select **Step-through (guide me)** as the journey mode.
4. Start the simulation and fill each application section.
5. Submit the application.

**System Response:** The system creates application `APP-2026-0153` for Carlo Bautista and places it in the Enrollment Queue as **Pre-Enlisted**. The submitted profile includes his LRN, Grade 11 TVL track, Culinary Arts specialization, Bugo campus, previous school, guardian contact, and uploaded document references.

**Connection to Next Step:** The application is now available for registrar review and profile encoding.

### Step 2 — Review the Enrollment Queue and encode the profile

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** The registrar reviews newly submitted applications before allowing them to proceed to validation.

**User Action:**

1. Open **Enrollment Queue**.
2. Select Carlo’s application row.
3. Open **Evaluate Applications**.
4. Select Carlo and review the profile details, LRN, category, previous school, and campus.
5. Click the profile encoding/check action.

**System Response:** The application status changes to **For Review** and the system runs an automated duplicate check across the student records. A possible existing record is shown for comparison.

**Connection to Next Step:** Because the system found a possible match, the registrar must make an identity decision before the record can proceed.

### Step 3 — Resolve the possible duplicate

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** The incoming application says **Carlo Bautista**, while the existing record says **Carlos D. Bautista**. The registrar must avoid creating duplicate academic histories.

**User Action:**

1. Open **Duplicate Records**.
2. Review the side-by-side comparison for `APP-2026-0153` and `STU-2023-1210`.
3. Compare the birth date `03/09/2009`, sex, previous school, guardian contact, name variation, and legacy LRN.
4. Confirm the guardian and PSA document identify both records as the same learner.
5. Select **Merge Records**.

**System Response:** The duplicate case is marked **Resolved** and the application becomes ready for continued enrollment processing. The system preserves the historical record while linking the current application to the verified learner identity.

**Connection to Next Step:** The registrar can now validate the transferee’s category-specific requirements without creating another student record.

### Step 4 — Validate requirements and send a follow-up

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** A transferee can proceed only after the registrar has reviewed the applicable credentials. Form 137 and Good Moral are not yet complete.

**User Action:**

1. Open **Requirements** and select Carlo.
2. Set **PSA Birth Certificate** to **Submitted**.
3. Set **Certificate of Completion** to **Submitted**.
4. Set **Form 137** to **Pending**.
5. Set **Good Moral Certificate** to **Missing**.
6. Send the follow-up notice to the guardian.

**System Response:** The checklist shows two completed documents, one pending document, and one missing document. The overall status becomes **Incomplete**, the student remains blocked from final confirmation, and each status update is added to the audit trail with the responsible staff member and timestamp.

**Connection to Next Step:** The missing and pending requirements are now visible to both the registrar and the reporting workflow. The presenter can generate a Missing Requirements snapshot before continuing.

### Step 5 — Generate the missing-requirements snapshot

**Module:** Reporting & Monitoring  
**User:** R. Santos / Registrar Staff  
**Situation:** The registrar needs a current list of students requiring document follow-up.

**User Action:**

1. Open **Reporting & Monitoring**.
2. Select **Missing Requirements**.
3. Set the school year to `2026-2027`, grade level to `Grade 11`, and campus to `Bugo — CDO`.
4. Generate the report.
5. Show Carlo’s row and the missing/pending document names.
6. Use **Export PDF** or **Export CSV** and point out the report-generation log.

**System Response:** The generated report includes Carlo, `APP-2026-0153`, his outstanding credentials, contact information, selected criteria, and the registrar who generated the report.

**Connection to Next Step:** After the follow-up, the registrar returns to Enrollment Management to update the checklist and verify the second enrollment condition: first-week attendance.

### Step 6 — Update the requirements after follow-up

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** Carlo submits the Form 137 and Good Moral Certificate after the previous school and guardian complete the follow-up.

**User Action:** In Carlo’s requirements checklist, change **Form 137** and **Good Moral Certificate** to **Submitted**.

**System Response:** The overall requirement status changes to **Complete**. The audit trail records both updates, and the student is now eligible for the requirements portion of enrollment confirmation.

**Connection to Next Step:** The registrar must still verify first-week attendance before confirming official enrollment.

### Step 7 — Demonstrate the attendance gate

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** ICI confirms a student only after the first week of attendance has been recorded. The presenter needs to show that complete credentials alone are not enough.

**User Action:**

1. Open **First-Week Attendance** and select Carlo.
2. Initially mark one day as **Absent** and leave another day **Pending**.
3. Show the gate status.
4. Update the attendance log so all five days are **Present**.

**System Response:** Before correction, the gate displays **Not met** because attendance is not fully verified. After all five days are recorded as Present, the gate changes to **Both met** and the confirmation link becomes available.

**Connection to Next Step:** The two enrollment conditions—complete requirements and verified attendance—are now satisfied.

### Step 8 — Confirm enrollment and assign the section

**Module:** Enrollment Management  
**User:** R. Santos / Registrar Staff  
**Situation:** Carlo is ready to become an official student and must be placed in an appropriate TVL section.

**User Action:**

1. Open **Enrollment Records** and view Carlo’s record.
2. Confirm that the requirements and attendance indicators are complete.
3. Click **Confirm Enrollment**.
4. Click **Auto section (simulate)** or select **Cookery-1 — 18/25 (Open)**.
5. Open **Enrolled & Assigned** to show the journey recap.

**System Response:** The system changes Carlo’s status to **Enrolled**, generates student ID `STU-2026-0456`, assigns him to **Cookery-1**, validates the available capacity, and records the registrar action. The application ID remains linked to the new official student ID.

**Connection to Next Step:** The same student ID and application ID are now available to Academic Records.

### Step 9 — Review the synchronized academic record

**Module:** Academic Records  
**User:** Nicole Chua / Teacher Adviser  
**Situation:** The adviser needs to see whether Carlo’s enrollment and requirements have been completed and whether his academic record needs follow-up.

**User Action:**

1. Open **Advisory Class**.
2. Search for **Bautista, Carlo** or `STU-2026-0456`.
3. Open **Student Class Record**.
4. Review the Enrollment, Requirements, Academic Standing, Section History, and Quarterly Academic Record panels.
5. Open **Student Monitoring** and save an **Academic follow-up** note explaining that Carlo needs support completing his first practical assessment.

**System Response:** Academic Records displays Carlo as **Enrolled**, shows his requirements as **Complete**, displays **Cookery-1** and Bugo — CDO, and calculates his initial academic standing as **At risk** because the Cookery Fundamentals record is incomplete. The adviser note is saved with Nicole Chua’s name and timestamp while core record fields remain read-only.

**Connection to Next Step:** The adviser’s view identifies the missing academic score, so the subject teacher can complete the grade entry.

### Step 10 — Enter component scores and submit an incomplete grade

**Module:** Academic Records  
**User:** Michael Reyes / Subject Teacher  
**Situation:** The subject teacher submits Carlo’s Quarter 1 grades. One practical-task score is not yet available.

**User Action:**

1. Open **Assigned Classes**.
2. Open **Cookery Fundamentals — Grade 11 · Cookery-1 · Cookery Lab 1**.
3. Review the 40% Written Work, 50% Performance Tasks, and 10% Recitation/Other weights.
4. Enter Written Work scores `17` and `38`, Recitation/Other `8`, and leave **Performance Task 1** blank.
5. Save the draft and submit the class grades.

**System Response:** The system calculates the grade as **Incomplete**, prevents a final quarter grade from being displayed, and creates or updates Carlo’s incomplete-grade case for registrar follow-up.

**Connection to Next Step:** Because a finalized academic record cannot contain an unresolved missing score, the registrar must monitor and resolve the case.

### Step 11 — Resolve the incomplete academic record

**Module:** Academic Records  
**User:** R. Santos / Registrar Staff  
**Situation:** Carlo completes the practical assessment and submits the missing performance-task evidence.

**User Action:**

1. Open **Incomplete Grades**.
2. Filter by Carlo, Cookery-1, or the `Cookery Fundamentals` subject.
3. Open the incomplete case.
4. Add a follow-up note confirming that the teacher supplied the missing assessment.
5. Enter the verified Performance Task 1 score `78`.
6. Click **Resolve and update grade**.

**System Response:** The incomplete case changes to **Resolved**, the Quarter 1 grade updates to **78**, and Carlo’s Academic Standing changes from **At risk** to **Good standing**. The change is recorded in the academic record and linked to the incomplete case.

**Connection to Next Step:** Reporting & Monitoring can now show both the original concern and the updated post-intervention state.

### Step 12 — Review the consolidated academic history

**Module:** Academic Records  
**User:** R. Santos / Registrar Staff  
**Situation:** The registrar verifies that the student’s official enrollment, section, requirements, grades, and follow-up history are consistent before producing monitoring outputs.

**User Action:** Open **Academic History**, select Carlo, and review the consolidated student ID, LRN, Cookery-1 section, requirements status, incomplete-case count, and quarterly grade table.

**System Response:** The academic history shows Carlo’s current official identity, the linked enrollment status, the resolved academic case, the Quarter 1 grade of 78, and the section history. The presenter can use the print/save-PDF action as the final record-verification point.

**Connection to Next Step:** Guidance Staff can now review the student’s remaining monitoring status and document the intervention.

### Step 13 — Generate and review the at-risk report

**Module:** Reporting & Monitoring  
**User:** Angela Cruz / Guidance Staff  
**Situation:** Guidance Staff monitors students whose enrollment requirements or academic records require attention.

**User Action:**

1. Open **At-Risk Student Reports**.
2. Set Grade Level to **Grade 11**.
3. Set Section to **Cookery-1**.
4. Set Adviser to **Nicole Chua**.
5. Select **Academic** or **Documentation** risk, depending on the point in the story being shown.
6. Generate the report.
7. Select Carlo’s row and open **View** to inspect the concern and priority.

**System Response:** Before the academic score is resolved, Carlo appears as an open academic-risk case because of the incomplete academic record. Before the requirements are completed, he appears as a documentation-risk case showing the pending Form 137 and Good Moral Certificate. After the score and requirements are resolved, the same row changes to **Monitoring** with the updated academic state.

**Connection to Next Step:** The guidance user records the action taken so the concern has an accountable follow-up, not just a report entry.

### Step 14 — Log the intervention and close the loop

**Module:** Reporting & Monitoring  
**User:** Angela Cruz / Guidance Staff  
**Situation:** Carlo’s records are now complete, but Guidance Staff wants a traceable follow-up after the earlier enrollment and academic concerns.

**User Action:**

1. Click **Log Intervention** for Carlo.
2. Select **Referred to class adviser** or **Requested document follow-up**.
3. Enter a note such as: “Requirements and first practical assessment were completed; adviser will continue monitoring the transferee during the next grading period.”
4. Save the intervention.
5. Generate the report again.

**System Response:** Carlo’s case changes to **Monitoring**, the intervention is stored with Angela Cruz and a timestamp, and the report reflects the updated status instead of treating the student as an unresolved open case.

**Connection to Next Step:** The scenario ends with one student record that has moved through enrollment, academic record maintenance, and accountable monitoring.

## Module Coverage Summary

| Module | Features Demonstrated | Scenario Steps |
| --- | --- | --- |
| Enrollment Management | Student application, queue review, profile encoding, duplicate detection and merge, requirements checklist, follow-up, attendance validation, enrollment gate, student ID generation, capacity-aware section assignment | 1–8 |
| Academic Records | Adviser roster, synchronized enrollment and requirements status, adviser note, weighted grade entry, incomplete-grade creation, registrar verification, grade update, academic history | 9–12 |
| Reporting & Monitoring | Missing Requirements report, report export and audit log, at-risk filters, risk details, intervention logging, updated monitoring status | 5, 13–14 |

## Suggested Demo Narrative

“We will follow Carlo Bautista, a transferee who is applying to ICI’s TVL–Culinary Arts program. The important point is that the process begins with one application ID and ends with one official student record used by the registrar, teachers, advisers, and guidance staff.”

“During encoding, the system detects that Carlo may already exist under a slightly different name. The registrar can compare the records, validate the identity, and merge the history before a duplicate student record is created.”

“The requirements checklist then makes the decision visible. Carlo cannot be confirmed while Form 137 and Good Moral are unresolved. Even after the documents are completed, the system still requires first-week attendance verification before official enrollment is allowed.”

“Once Carlo is enrolled and assigned to Cookery-1, Academic Records uses the same student identity. The adviser can see the enrollment and requirements status, while the subject teacher records the weighted grade components. A missing performance score creates a traceable incomplete-grade case instead of silently producing an inaccurate grade.”

“Finally, Reporting & Monitoring turns the same information into action. The registrar can produce a missing-requirements report, Guidance Staff can filter Carlo’s academic or documentation risk, and the intervention is logged. After the missing score and documents are resolved, the report changes from an open concern to continued monitoring.”

## Pre-Demo Setup Checklist

1. Click **Reset Demo** from the Enrollment Queue before starting.
2. Open **Enrollment Submission** and select **Transferee — Duplicate Flag** with **Step-through (guide me)** mode.
3. Confirm that the Academic Records seed contains Carlo as `STU-2026-0456`, linked to `APP-2026-0153`, Cookery-1, Bugo — CDO, and Nicole Chua.
4. Confirm that the Cookery Fundamentals class is assigned to Michael Reyes and includes the incomplete Performance Task 1 score.
5. Confirm that the requirements and attendance screens can be updated manually before the enrollment gate is tested.
6. Confirm that the Reporting & Monitoring screen includes Cookery-1 and Nicole Chua as filter options.
7. Use the same identifiers everywhere: Carlo Bautista, LRN `121456789012`, `APP-2026-0153`, and `STU-2026-0456`.

The scenario intentionally excludes payment processing, cashiering, payroll, inventory, and unrelated system-administration workflows.
