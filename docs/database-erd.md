# Core relational map

This diagram shows the core relationships. See database-schema.md and the SQL migrations for the full table inventory and constraints.

```mermaid
erDiagram
    CAMPUSES ||--o{ SECTIONS : hosts
    CAMPUSES ||--o{ APPLICATIONS : receives
    ACADEMIC_YEARS ||--|{ ACADEMIC_TERMS : contains
    ACADEMIC_TERMS ||--o{ GRADING_PERIODS : contains
    ACADEMIC_TERMS ||--o{ SECTIONS : schedules
    PROGRAMS ||--o{ SECTIONS : groups
    STUDENTS ||--o{ GUARDIANS : has
    STUDENTS o|--o{ APPLICATIONS : linked_to
    APPLICATIONS ||--o{ DUPLICATE_CASES : reviewed_in
    APPLICATIONS ||--o{ APPLICATION_REQUIREMENTS : tracks
    REQUIREMENT_TYPES ||--o{ APPLICATION_REQUIREMENTS : defines
    APPLICATIONS ||--o{ ORIENTATION_ATTENDANCE : records
    APPLICATIONS ||--o| ENROLLMENTS : produces
    STUDENTS ||--o{ ENROLLMENTS : registers
    SECTIONS o|--o{ ENROLLMENTS : assigns
    SECTIONS ||--o{ CLASS_OFFERINGS : offers
    SUBJECTS ||--o{ CLASS_OFFERINGS : defines
    USERS ||--o{ CLASS_OFFERINGS : teaches
    CLASS_OFFERINGS ||--o{ CLASS_MEMBERSHIPS : includes
    ENROLLMENTS ||--o{ CLASS_MEMBERSHIPS : takes
    CLASS_MEMBERSHIPS ||--o{ GRADES : receives
    GRADING_PERIODS ||--o{ GRADES : scopes
    CLASS_OFFERINGS ||--o{ GRADING_WINDOWS : controls
    GRADES ||--o{ GRADE_CORRECTIONS : corrects
    GRADES ||--o{ INCOMPLETE_CASES : tracks
    ENROLLMENTS ||--o| WITHDRAWALS : requests
    WITHDRAWALS ||--o{ WITHDRAWAL_CLEARANCES : requires
    STUDENTS ||--o{ DOCUMENT_REQUESTS : requests
    STUDENTS ||--o{ STUDENT_NOTES : receives
    USERS ||--o{ USER_ROLE_GRANTS : holds
    ROLES ||--o{ USER_ROLE_GRANTS : grants
    CAMPUSES ||--o{ USER_ROLE_GRANTS : scopes
    IMPORT_BATCHES ||--o{ IMPORT_EXCEPTIONS : reports
```

Reporting queries derive from relational records rather than maintaining duplicate summary tables. Honor policy tables are intentionally deferred until institutional rules are provided.
