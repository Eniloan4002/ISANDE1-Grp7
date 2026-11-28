import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import DailyAttendance, { datesBetween } from '../academic/DailyAttendance.jsx';
import * as attendance from '../academic/dailyAttendanceApi.js';
import * as grades from '../academic/gradeEntryApi.js';
import { pages } from '../routes/routeConfig.js';

let mockRoles = ['teacher'];
vi.mock('../auth/AuthContext.jsx', () => ({ useAuth: () => ({ roles: mockRoles }) }));
vi.mock('../academic/gradeEntryApi.js', async importOriginal => ({ ...(await importOriginal()), loadGradeCatalog: vi.fn() }));
vi.mock('../academic/dailyAttendanceApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadAttendanceClasses: vi.fn(), loadClassStudents: vi.fn(), loadSchoolDays: vi.fn(), saveSchoolDays: vi.fn(),
  loadStudentAttendance: vi.fn(), saveAttendance: vi.fn(), loadCalendarReference: vi.fn(),
}));

const days = [
  { date: '2026-06-01', schoolDay: true, version: 1 },
  { date: '2026-06-02', schoolDay: true, version: 1 },
  { date: '2026-06-06', schoolDay: false, version: 1 },
];
const students = [
  { enrollmentId: 101, studentNumber: 'STU-1', name: 'Ana Santos' },
  { enrollmentId: 102, studentNumber: 'STU-2', name: 'Ben Cruz' },
];

function conflict(message) {
  const error = new Error(message);
  error.status = 409;
  return error;
}

beforeEach(() => {
  mockRoles = ['teacher'];
  grades.loadGradeCatalog.mockResolvedValue({ years: [{ id: 1, label: '2026-2027' }], terms: [{ id: 10, academic_year_id: 1, name: 'First Semester' }] });
  attendance.loadAttendanceClasses.mockResolvedValue([{ id: 7, subject: 'General Mathematics', subjectCode: 'MATH11', section: 'STEM 11-A', campusId: 1, termId: 10 }]);
  attendance.loadClassStudents.mockResolvedValue(students);
  attendance.loadSchoolDays.mockResolvedValue(days);
  attendance.loadStudentAttendance.mockImplementation(async id => (id === 101 ? [{ date: '2026-06-01', status: 'absent', version: 3 }] : []));
  attendance.saveAttendance.mockResolvedValue({ saved: 2 });
  attendance.loadCalendarReference.mockResolvedValue({ campuses: [{ id: 1, name: 'Iligan Main' }], terms: [{ id: 10, name: 'First Semester', academicYear: '2026-2027', startsOn: '2026-06-01', endsOn: '2026-10-31' }] });
  attendance.saveSchoolDays.mockResolvedValue({ items: days });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

async function chooseClass() {
  await screen.findByRole('option', { name: /STEM 11-A/ });
  fireEvent.change(screen.getByLabelText('Class'), { target: { value: '7' } });
}

async function openClass() {
  render(<DailyAttendance />);
  await chooseClass();
  await screen.findByText('Ana Santos');
  fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-06-01' } });
}

describe('Daily attendance', () => {
  it('is routed for teachers and the registrar', () => {
    expect(pages.find(page => page.path === '/academic/daily-attendance').allowedRoles).toEqual(['registrar', 'teacher']);
  });

  it('offers only school days on the calendar as dates', async () => {
    render(<DailyAttendance />);
    await chooseClass();
    await screen.findByText('Ana Santos');
    const dates = within(screen.getByLabelText('Date')).getAllByRole('option').map(option => option.value);
    expect(dates).toEqual(['2026-06-01', '2026-06-02']);
  });

  it('marks each student and saves one batch with the stored versions', async () => {
    await openClass();
    expect(screen.getByRole('radio', { name: 'Absent', checked: true })).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('group', { name: 'Attendance for Ana Santos' })).getByLabelText('Excused'));
    fireEvent.click(screen.getByRole('button', { name: 'Mark the rest present' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    await waitFor(() => expect(attendance.saveAttendance).toHaveBeenCalledTimes(1));
    expect(attendance.saveAttendance).toHaveBeenCalledWith([
      { enrollmentId: 101, date: '2026-06-01', status: 'excused', note: null, version: 3 },
      { enrollmentId: 102, date: '2026-06-01', status: 'present', note: null, version: 0 },
    ]);
    expect(await screen.findByText(/Saved attendance for 2 students/)).toBeInTheDocument();
  });

  it('keeps the teacher’s marks when a save hits a version conflict', async () => {
    attendance.saveAttendance.mockRejectedValueOnce(conflict('Record changed. Reload before saving.'));
    await openClass();
    fireEvent.click(within(screen.getByRole('group', { name: 'Attendance for Ben Cruz' })).getByLabelText('Present'));
    fireEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your changes are still on screen');
    expect(within(screen.getByRole('group', { name: 'Attendance for Ben Cruz' })).getByLabelText('Present')).toBeChecked();
    expect(screen.getByText('1 unsaved change')).toBeInTheDocument();
  });

  it('explains a duplicate entry rejection', async () => {
    attendance.saveAttendance.mockRejectedValueOnce(Object.assign(new Error('Duplicate enrollment/date in import.'), { status: 400 }));
    await openClass();
    fireEvent.click(within(screen.getByRole('group', { name: 'Attendance for Ben Cruz' })).getByLabelText('Present'));
    fireEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Each student can be marked once per date');
  });

  it('shows an empty state when the term has no school days', async () => {
    attendance.loadSchoolDays.mockResolvedValue([]);
    render(<DailyAttendance />);
    await chooseClass();
    expect(await screen.findByText('No school days on the calendar')).toBeInTheDocument();
  });

  it('lets the registrar add school days and save them with a reason', async () => {
    mockRoles = ['registrar'];
    render(<DailyAttendance />);
    await screen.findByText(/Sat, Jun 6, 2026/);
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-06-03' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-06-07' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    expect(await screen.findByText(/3 dates marked as school days/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save calendar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Give a reason');
    fireEvent.change(screen.getByLabelText('Reason for the change'), { target: { value: 'Approved school calendar' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save calendar' }));
    await waitFor(() => expect(attendance.saveSchoolDays).toHaveBeenCalledWith('1', '10', [
      { date: '2026-06-03', schoolDay: true, version: 0 },
      { date: '2026-06-04', schoolDay: true, version: 0 },
      { date: '2026-06-05', schoolDay: true, version: 0 },
    ], 'Approved school calendar'));
  });

  it('lists weekdays in a range without assuming weekends', () => {
    expect(datesBetween('2026-06-05', '2026-06-08', [1, 2, 3, 4, 5])).toEqual(['2026-06-05', '2026-06-08']);
    expect(datesBetween('2026-06-06', '2026-06-07', [0, 6])).toEqual(['2026-06-06', '2026-06-07']);
    expect(datesBetween('2026-06-08', '2026-06-01', [1])).toEqual([]);
  });
});
