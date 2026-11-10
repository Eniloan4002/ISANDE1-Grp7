import EnrollmentWorkspace from '../EnrollmentWorkspace.jsx';
// PR #14 attendance route now uses persisted attendance, policy dates, confirmation and audit.
// Confirmation eligibility is decided by the backend, not a fixed five-day mock calendar.
export default function AttendanceScreen(){return <EnrollmentWorkspace mode="attendance"/>;}
