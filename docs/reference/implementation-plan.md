PROJECT IMPLEMENTATION PLAN: ICI LOCAL 2.0 (GREENFIELD MIS)
1. Document Overview
System Name: ICI Local 2.0 Management Information System
Client Organization: Iligan Computer Institute (ICI)
Campuses Covered: Iligan Main, Kapatagan, and Cagayan de Oro (Bugo)
Target Audience: Over 4,000 students across Senior High School and Technical-Vocational programs
Project Type: Greenfield Management Information System replacing legacy standalone software, manual spreadsheets, and physical paper workflows
Technology Stack: Frontend: React (Vite, React Router, Axios, CSS Modules/Tailwind) | Backend: Java Spring Boot (Spring Security, Spring Data JPA, Hibernate, Flyway) | Database: PostgreSQL (Centralized instance)
Methodology: Scrumban (Continuous task pull, 1 active task per team member, strict WIP limit of 4)
2. Project Scope and Boundary Management
In-Scope Functional Modules
Enrollment Management Module: Digital applicant intake, automated duplicate applicant detection, requirement checklist tracking, attendance-based enrollment confirmation, and section capacity assignment.
Academic Records Module: Advisory class rosters, teacher grade entry grids, grading period lock controls, formal grade correction workflows, incomplete grade tracking, consolidated 360-degree academic histories, and withdrawal clearance tracking.
Reporting and Monitoring Module: Operational registrar summaries, missing document lists, honor student computation, Guidance Office at-risk student monitoring, DepEd-compliant transcript printing (Form 137, TOR), and cashiering reference exports.
Data Ingestion and Cutover: Phased legacy CSV, Excel, and hard-copy record parsing into the centralized PostgreSQL database, reconciliation auditing, and full decommissioning of legacy tools.
Explicitly Out-of-Scope Capabilities
Dedicated payment gateways, monetary collections, and cash drawers.
General ledger accounting, balance sheets, and tax management.
Human Resources, staff timekeeping, and payroll processing.
Inventory management and institutional procurement.
Learning Management Systems (LMS) including courseware delivery, quiz engines, and assignment submissions.
3. Epics and Features Breakdown
Epic 1: Core Architecture, Authentication and Security Framework
Feature 1.1: Relational Persistence Foundation
Description: Establishes the database schema for the three primary functional modules and auditing tables.
Technical Implementation: PostgreSQL database initialized with schema migrations managed via Flyway/Liquibase. Enforces strict relational foreign key constraints, column validations, unique indexes, and audit timestamps (created_at, updated_at, created_by, updated_by).
Key Entities: users, roles, permissions, students, guardians, vouchers, requirements, sections, academic_terms, grades, grade_corrections, withdrawals, audit_logs.
Feature 1.2: Authentication and Token Service
Description: Secure login mechanism for school personnel to authenticate into the system.
Technical Implementation: Java Spring Boot using Spring Security and jjwt. Endpoint /api/auth/login accepts credentials, verifies BCrypt hashes, and issues signed stateless JSON Web Tokens (JWT) containing user ID, campus scope, and role claims. Includes a token invalidation endpoint (/api/auth/logout).
Feature 1.3: Role-Based Access Control (RBAC)
Description: Access restriction ensuring users view and operate only functions authorized for their role.
Technical Implementation: Declarative method-level security (@PreAuthorize("hasRole('...')")) on Spring Boot controllers alongside React client-side route guards.
Authorized Roles:
ROLE_ADMIN: Full system administrative and account control.
ROLE_REGISTRAR: Master record creation, requirement auditing, section assignment, grade period lock, and document issuance.
ROLE_PRINCIPAL: System-wide academic oversight, operational report generation, and withdrawal approvals.
ROLE_ADVISER: Read-only access to assigned advisory section, student compliance badges, and withdrawal endorsement.
ROLE_TEACHER: Grade submission for assigned subjects during active submission windows and correction request submission.
ROLE_GUIDANCE: At-risk student dashboard access and counseling notes.
ROLE_CASHIER_REF: Read-only access to registered student rosters, voucher categories, and clearance statuses.
Feature 1.4: Application UI Shell and Design System
Description: Single-page interface layout reflecting ICI brand design standards.
Technical Implementation: React interface implementing responsive navigation, collapsible sidebars, breadcrumbs, modal dialogs, and toast notifications. Configured with brand design tokens: Primary Blue (#56A9C8), Deep Slate Blue (#6A8FB4), Soft Coral (#D97A84), Mint Green (#7FC9B3), Charcoal (#2F3136), and Mist White (#F7F9FB).
Epic 2: Enrollment Management Module
Feature 2.1: Student Master Profile Intake
Description: Ingestion and management of incoming applicant demographic, family, and educational details.
Technical Implementation: Form submission and Spring Boot REST API persisting personal details, Learner Reference Number (LRN), junior high origin, parental information, household income bracket, track, and specialization (STEM, ABM, HUMSS, TVL-Cookery, etc.).
Voucher Support: Classifies applicants as Qualified Voucher Recipient (QVR), Education Service Contracting (ESC), Alternative Learning System (ALS), or Regular/Paying.
Feature 2.2: Duplicate Detection Engine
Description: Automatic validation preventing duplicate applicant records across campuses.
Technical Implementation: Spring Boot service running SQL validation checks when an applicant is submitted. Flags matches based on exact LRN matches or fuzzy matches on normalized full name plus date of birth.
Resolution Workflow: Suspected duplicates trigger a DUPLICATE_FLAG state, placing the file into a dedicated Registrar resolution queue with side-by-side comparison tools to merge or reject records.
Feature 2.3: Credential Verification and Tracking Checklist
Description: Digital tracking system replacing paper-based and Excel document trackers.
Technical Implementation: Relational document checklist recording submission state per mandatory credential: PSA Birth Certificate, Form 137/Report Card, Certificate of Completion, and Certificate of Good Moral Character.
Item Statuses: Submitted, Missing, Pending, and Invalid. The engine dynamically evaluates the aggregate state (Complete, Incomplete, Pending) and creates an immutable audit row whenever a document status changes.
Feature 2.4: Attendance Verification and Enrollment Confirmation
Description: Business workflow transitioning students from provisional applicants to officially confirmed students.
Technical Implementation: Multi-point gatekeeper API. Checks document completion and verifies first-week orientation attendance flag before updating a student record status from Pre-Enlisted to Enrolled. Prevents premature record generation for non-attending walk-ins.
Feature 2.5: Section Capacity and Allocation Management
Description: Balanced student distribution across sections based on physical constraints and routing rules.
Technical Implementation: Database model defining section entities with attributes for campus, academic year, grade level, track, strand, physical classroom room assignment, and maximum capacity limit.
Assignment Workspace: React interface displaying real-time filled/available seat meters. Filters students by residential district/transportation route and program. Enforces concurrency checks to prevent section over-allocation.
Epic 3: Academic Records Module
Feature 3.1: Advisory Class Roster
Description: Read-only monitoring screen dedicated to Teacher Advisers.
Technical Implementation: React dashboard connected to Spring Boot endpoint querying by teacher ID and active academic year. Displays section students, contact numbers, enrollment states, credential completion badges, and academic flags without allowing unauthorized record edits.
Feature 3.2: Subject Grade Encoding Grid
Description: Spreadsheet-like web interface for subject teachers to encode grading period marks.
Technical Implementation: Data-entry grid supporting keyboard navigation, cell-level numeric validation (0.00 to 100.00 or DepEd non-numeric scales), autosaving drafts, and formal submission controls. Ensures teachers only access classes explicitly assigned to them.
Feature 3.3: Grading Period Lock Engine
Description: Automated and manual cutoffs preventing unauthorized grade adjustments past deadlines.
Technical Implementation: Administrative control module allowing the Registrar to set submission deadlines or manually trigger immediate grade locks per grading period, grade level, and section. Post-lock write operations via teacher endpoints are rejected with HTTP 403 Forbidden.
Feature 3.4: Traceable Grade Correction Workflow
Description: Digital post-lock grade revision cycle replacing paper letters and informal notifications.
Technical Implementation Steps:
Teacher submits an electronic request specifying student ID, subject, original grade, proposed revised grade, and mandatory justification.
Teacher uploads supporting proof (scanned assessment or computation sheet) handled via Spring Boot multipart storage.
Registrar reviews request in an adjudication queue, issuing an Approval or Rejection.
System executes the grade change within a database transaction and logs old value, new value, timestamp, teacher ID, and approving registrar ID into the audit log.
Feature 3.5: Incomplete Grade Tracking and Resolution
Description: Monitoring tool to surface, resolve, and close outstanding incomplete marks (INC).
Technical Implementation: Database view aggregating all unresolved INC grades across historical and active terms. Allows registrar staff to track pending completion tasks, record follow-up logs with subject teachers, and update finalized marks upon completion.
Feature 3.6: Consolidated Student Academic History (360° View)
Description: Comprehensive single-screen timeline displaying a student's complete academic path.
Technical Implementation: Aggregated React view retrieving all completed terms, subjects, quarterly marks, final ratings, section assignments, adviser history, and document checklist statuses for a given student ID. Includes a single-click PDF export function.
Feature 3.7: Student Withdrawal and Clearance Workflow
Description: End-to-end clearance pipeline managing student departures and credential withholding.
Mandatory Verification Steps:
Teacher adviser endorsement.
Principal approval.
Cashiering office financial clearance confirmation.
Enforcement: Locks official credential release until all clearances are checked, automatically updating student standing to Withdrawn.
Epic 4: Reporting and Monitoring Module
Feature 4.1: Dynamic Operational Query Engine
Description: Modular data filtering engine powering operational summaries.
Technical Implementation: Spring Boot service utilizing JPA Criteria API and native queries to aggregate data dynamically across five dimensions: Campus, Academic Year, Grading Period, Grade Level, and Section.
Feature 4.2: Standard Operational Registrar Reports
Description: Exportable institutional reports for administration and DepEd monitoring.
Technical Implementation: Generates real-time PDF and CSV reports for active enrollment counts, voucher classification distributions (QVR, ESC, ALS, Regular), and missing credential checklists.
Feature 4.3: Guidance At-Risk Student Monitoring
Description: Early-warning dashboard helping the Guidance Office identify students requiring academic or attendance intervention.
Technical Implementation: Filter engine flagging students based on configurable rules:
Attendance flags: Consecutive unexcused absences.
Academic flags: Failing subject marks or unresolved INC ratings.
Compliance flags: Overdue mandatory credentials.
Includes exportable case sheets and counseling status notes.
Feature 4.4: Honor Student Computation Engine
Description: Automated academic achievement calculation.
Technical Implementation: Spring Boot computation service calculating General Weighted Averages (GWA) across grading terms and sorting candidates based on institutional and DepEd honor criteria.
Feature 4.5: DepEd Official Document Formatter (Form 137 and TOR)
Description: Automated document generator producing DepEd Form 137 and Transcripts of Records.
Technical Implementation: Template engine compiling certified transcripts with pre-generation rule checks. Blocks document generation if the student has unresolved INC grades, pending admission credentials, or missing DepEd Special Order (SO) numbers.
Feature 4.6: Cashiering Reference Export Service
Description: Automated data export bridge eliminating manual CSV handoffs to the independent billing application.
Technical Implementation: Dedicated Spring Boot export endpoint generating structured CSV rosters containing student IDs, full names, enrolled strands, campus assignments, and voucher subsidy categories.
Epic 5: Legacy Ingestion, Migration and Cutover
Feature 5.1: Legacy ICI Local Data Parsing and Ingestion (Phase 1)
Description: Cleanses and imports master student profiles from legacy exports into the new PostgreSQL database.
Technical Implementation: Spring Batch / OpenCSV service reading legacy CSV exports, normalizing character encodings, validating LRN formats, mapping legacy fields into the new entity model, and logging import exceptions.
Feature 5.2: Historical Academic Record Migration (Phase 2)
Description: Ingests past grades and subject catalogs.
Technical Implementation: Custom ingestion scripts parsing historical academic matrices, standardizing subject names, linking marks to migrated student master IDs, and storing past terms in a read-only historical schema.
Feature 5.3: External Spreadsheet Ingestion (Phase 3)
Description: Imports sectioning and document tracking spreadsheets into the system.
Technical Implementation: Apache POI parsing service extracting section assignments, room schedules, physical folder status flags, and historical withdrawal logs from Excel files into PostgreSQL.
Feature 5.4: Reconciliation Auditing and Decommissioning Tool
Description: Verification panel ensuring zero data loss before turning off legacy systems.
Technical Implementation: Internal audit dashboard comparing record counts, grade distributions, and checklist totals between legacy extracts and migrated PostgreSQL tables. Requires formal administrative sign-off before legacy ICI Local is shut down.
4. Scrumban Task Board and Work Breakdown Structure (WBS)
WIP Rules
Product Backlog: Unlimited
Ready / To Do: WIP Limit 6 to 8
In Progress: Strict WIP Limit of 4 (1 task per developer: Anton Adriano, Elijah Lobitaña, Pauline Novera, Linus Tabanao)
For Review and Testing: WIP Limit of 4
Done: Unlimited
Tasks Breakdown
Task ID
Task Name
Tech Stack
Epic
Deliverable / Scope
TSK-01
Database Schema Definition and Flyway Setup
PostgreSQL / Spring Boot
Epic 1
Relational tables, constraints, indexes, and initial role seed data.
TSK-02
Authentication and Token Management Service
Java Spring Boot
Epic 1
JWT issuance, BCrypt password hashing, and login/logout endpoints.
TSK-03
Method-Level RBAC Middleware Engine
Java Spring Boot
Epic 1
@PreAuthorize security rules enforcing access across all 7 user roles.
TSK-04
React Shell and Design Token Implementation
React / Tailwind
Epic 1
App shell, navigation, protected routes, and brand color palette setup.
TSK-05
User Account and Access Control Panel
React / Spring Boot
Epic 1
Full-stack admin management to create, edit, activate, and deactivate users.
TSK-06
Student Master Record CRUD API
Java Spring Boot
Epic 2
Endpoints for student demographics, voucher categories, and program data.
TSK-07
Duplicate Applicant Detection Algorithm
Java Spring Boot
Epic 2
Database query service identifying LRN or name/birthdate matches.
TSK-08
Enrollment Queue and Intake Interface
React
Epic 2
Dashboard metric cards, intake modal, and duplicate resolution screens.
TSK-09
Document Checklist Service and Audit Trail
Java Spring Boot
Epic 2
Requirement tracking engine managing document statuses with timestamped audit logs.
TSK-10
Requirement Verification Screen
React
Epic 2
Registrar UI for checking, filtering, and updating student document completeness.
TSK-11
Attendance Verification and Confirmation Flow
Spring Boot / React
Epic 2
Verification gatekeeper promoting status from Pre-Enlisted to Enrolled.
TSK-12
Section Configuration and Limits API
Java Spring Boot
Epic 2
Endpoints managing sections, campus assignments, rooms, and max seat limits.
TSK-13
Section Allocation and Capacity Workspace
React
Epic 2
Interactive sectioning screen with seat counters and transport route filters.
TSK-14
Teacher Advisory Roster Dashboard
React / Spring Boot
Epic 3
Read-only advisory screen showing student enrollment and credential statuses.
TSK-15
Subject Grade Encoding Grid Interface
React
Epic 3
Data grid for subject teachers with cell validation and draft saving.
TSK-16
Grade Finalization and Period Lock Service
Java Spring Boot
Epic 3
Administrative function locking grade submission per term and section.
TSK-17
Grade Correction Request Submission Flow
React / Spring Boot
Epic 3
Teacher form to submit post-lock corrections and upload supporting evidence files.
TSK-18
Grade Correction Review Dashboard
React / Spring Boot
Epic 3
Registrar interface to approve/reject grade revisions and log audit entries.
TSK-19
Incomplete Grade Monitoring and Resolution View
React / Spring Boot
Epic 3
Query dashboard tracking unresolved INC marks and follow-up notes.
TSK-20
Consolidated Student Academic History (360°)
React / Spring Boot
Epic 3
Unified view of historical subjects, grades, and credential statuses.
TSK-21
Student Withdrawal Clearance Workflow
React / Spring Boot
Epic 3
Multi-office clearance tracker (Adviser, Principal, Cashiering).
TSK-22
Dynamic Report Aggregation Query Engine
Java Spring Boot
Epic 4
Criteria-based query service supporting multi-dimensional reporting filters.
TSK-23
Operational Reports UI and PDF/CSV Exporter
React / Spring Boot
Epic 4
Exportable reports for active enrollments and missing credentials.
TSK-24
Guidance At-Risk Student Monitoring Module
React / Spring Boot
Epic 4
Configurable filter interface detecting absences, failing grades, and missing documents.
TSK-25
Honor Roll Computation Engine
Java Spring Boot
Epic 4
Automated GWA calculation service identifying top academic performers.
TSK-26
DepEd Document Generator (Form 137 / TOR)
Java Spring Boot
Epic 4
Printable transcript generator with prerequisite validation checks.
TSK-27
Cashiering Reference Roster Export Utility
Java Spring Boot
Epic 4
Automated CSV generation exporting registered students and voucher classifications.
TSK-28
Legacy ICI Local CSV Ingestion Service
Spring Boot Batch
Epic 5
Parsing service ingesting legacy CSV extracts into student master tables.
TSK-29
Historical Grade Matrix Normalization Script
PostgreSQL / Java
Epic 5
ETL parser importing and normalizing past academic records.
TSK-30
Spreadsheet Data Parsing Pipeline
Spring Boot (POI)
Epic 5
Utility importing Excel section schedules and physical credential records.
TSK-31
Cutover Reconciliation and Audit Dashboard
React / Spring Boot
Epic 5
System comparison tool verifying imported counts before decommissioning legacy tools.

5. Development Phases and Milestones
Milestone 1: Core Architecture and Security Foundation (Weeks 1 to 3)
Goal: Initialize development environment, database schema, authentication, and core application shell.
Included Tasks: TSK-01, TSK-02, TSK-03, TSK-04, TSK-05
Exit Criteria: Users can authenticate, receive JWTs with appropriate role claims, view role-protected routes, and manage accounts through the administrator dashboard.
Milestone 2: Enrollment Management Implementation (Weeks 4 to 6)
Goal: Deliver the applicant intake pipeline, duplicate detection engine, credential checklists, and sectioning tools.
Included Tasks: TSK-06, TSK-07, TSK-08, TSK-09, TSK-10, TSK-11, TSK-12, TSK-13
Exit Criteria: Registrar staff can intake students, identify duplicates, update requirement checklists, confirm enrollments following attendance verification, and allocate students into sections without exceeding room limits.
Milestone 3: Academic Records Implementation (Weeks 7 to 9)
Goal: Deliver advisory views, grade encoding matrices, period locking, grade correction workflows, academic history views, and withdrawal processing.
Included Tasks: TSK-14, TSK-15, TSK-16, TSK-17, TSK-18, TSK-19, TSK-20, TSK-21
Exit Criteria: Teachers can submit grades within open windows, the registrar can lock grading terms, post-lock correction requests process through formal registrar reviews, and academic histories render completely.
Milestone 4: Reporting, Legacy Migration and Final Cutover (Weeks 10 to 12)
Goal: Deliver reporting dashboards, DepEd document generation, legacy data ingestion pipelines, and cutover auditing.
Included Tasks: TSK-22, TSK-23, TSK-24, TSK-25, TSK-26, TSK-27, TSK-28, TSK-29, TSK-30, TSK-31
Exit Criteria: Operational reports and transcripts generate cleanly, cashiering reference exports function, legacy data imports successfully into PostgreSQL with zero discrepancy flags, and legacy tools are decommissioned.
