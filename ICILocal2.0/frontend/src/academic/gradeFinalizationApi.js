// Mock ICI2-25 adapter. The service boundary mirrors section-level API calls;
// the backend can translate each section operation to its class grading windows.
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
const copy = value => JSON.parse(JSON.stringify(value));

function seedScopes(now) {
  const future = days => new Date(now + days * DAY).toISOString();
  return [
    {
      id: 'scope-2026-2027-q1-grade11-stem-a', academicYear: '2026–2027', gradingPeriod: 'Quarter 1',
      gradeLevel: 'Grade 11', section: '11-STEM-A', deadline: future(5), lockedAt: null, lockedBy: null, lockReason: null,
      offerings: [
        { id: 'off-101', subject: 'General Mathematics', teacher: 'Michael Reyes', submittedRecords: 28, submittedIncRecords: 0, totalRecords: 28, unresolvedInc: 0 },
        { id: 'off-102', subject: 'Oral Communication', teacher: 'Angela Lim', submittedRecords: 21, submittedIncRecords: 2, totalRecords: 30, unresolvedInc: 2 },
        { id: 'off-103', subject: 'Earth and Life Science', teacher: 'Carlo Bautista', submittedRecords: 0, submittedIncRecords: 0, totalRecords: 30, unresolvedInc: 0 },
      ],
      history: [],
    },
    {
      id: 'scope-2026-2027-q1-grade11-stem-b', academicYear: '2026–2027', gradingPeriod: 'Quarter 1',
      gradeLevel: 'Grade 11', section: '11-STEM-B', deadline: future(7), lockedAt: null, lockedBy: null, lockReason: null,
      offerings: [
        { id: 'off-104', subject: 'General Mathematics', teacher: 'Liza Ramos', submittedRecords: 30, submittedIncRecords: 0, totalRecords: 30, unresolvedInc: 0 },
        { id: 'off-105', subject: 'Oral Communication', teacher: 'Angela Lim', submittedRecords: 26, submittedIncRecords: 1, totalRecords: 30, unresolvedInc: 1 },
      ],
      history: [],
    },
    {
      id: 'scope-2026-2027-q1-grade12-abm-a', academicYear: '2026–2027', gradingPeriod: 'Quarter 1',
      gradeLevel: 'Grade 12', section: '12-ABM-A', deadline: future(3), lockedAt: null, lockedBy: null, lockReason: null,
      offerings: [
        { id: 'off-106', subject: 'Business Finance', teacher: 'Rosa Mendoza', submittedRecords: 18, submittedIncRecords: 1, totalRecords: 32, unresolvedInc: 1 },
        { id: 'off-107', subject: 'Applied Economics', teacher: 'David Tan', submittedRecords: 32, submittedIncRecords: 0, totalRecords: 32, unresolvedInc: 0 },
      ],
      history: [],
    },
    {
      id: 'scope-2025-2026-q4-grade12-humss-a', academicYear: '2025–2026', gradingPeriod: 'Quarter 4',
      gradeLevel: 'Grade 12', section: '12-HUMSS-A', deadline: future(-160), lockedAt: null, lockedBy: null, lockReason: null,
      offerings: [
        { id: 'off-108', subject: 'Creative Writing', teacher: 'Rosa Mendoza', submittedRecords: 29, submittedIncRecords: 0, totalRecords: 29, unresolvedInc: 0 },
        { id: 'off-109', subject: 'Community Engagement', teacher: 'Ben Cruz', submittedRecords: 29, submittedIncRecords: 0, totalRecords: 29, unresolvedInc: 0 },
      ],
      history: [],
    },
  ];
}

function submissionStatus(offering) {
  if (offering.submittedRecords >= offering.totalRecords) return 'Submitted';
  return offering.submittedRecords > 0 ? 'In progress' : 'Not started';
}

function finalizeSubmittedRecords(scope) {
  scope.offerings.forEach(offering => {
    const submittedIncRecords = Math.min(offering.submittedIncRecords, offering.submittedRecords);
    offering.finalizedIncRecords = submittedIncRecords;
    offering.finalizedGradeRecords = offering.submittedRecords - submittedIncRecords;
    offering.unsubmittedRecords = offering.totalRecords - offering.submittedRecords;
  });
}

export function createGradingLocksMock({ clock = () => Date.now() } = {}) {
  const scopes = seedScopes(clock());

  function applyAutomaticLocks() {
    const now = clock();
    scopes.forEach(scope => {
      if (!scope.lockedAt && scope.deadline && Date.parse(scope.deadline) <= now) {
        finalizeSubmittedRecords(scope);
        scope.lockedAt = new Date(now).toISOString();
        scope.lockedBy = 'System';
        scope.lockReason = 'deadline';
        scope.history.unshift({
          id: `auto-${scope.id}-${now}`, action: 'Automatically locked at deadline',
          actor: 'System', occurredAt: scope.lockedAt,
        });
      }
    });
  }

  function findScope(scopeId) {
    const scope = scopes.find(item => item.id === scopeId);
    if (!scope) throw new Error('The selected grading scope could not be found.');
    return scope;
  }

  return {
    async listScopes() {
      applyAutomaticLocks();
      return { items: copy(scopes).map(scope => ({ ...scope, offerings: scope.offerings.map(offering => ({ ...offering, submissionStatus: submissionStatus(offering) })) })) };
    },
    async getScope(scopeId) {
      applyAutomaticLocks();
      const scope = copy(findScope(scopeId));
      scope.offerings = scope.offerings.map(offering => ({ ...offering, submissionStatus: submissionStatus(offering) }));
      return scope;
    },
    async updateDeadline(scopeId, deadline, actor) {
      applyAutomaticLocks();
      const scope = findScope(scopeId);
      if (scope.lockedAt) throw new Error('This scope is locked. Its deadline can no longer be changed.');
      const time = Date.parse(deadline);
      if (!Number.isFinite(time)) throw new Error('Enter a valid submission deadline.');
      scope.deadline = new Date(time).toISOString();
      scope.history.unshift({ id: `deadline-${clock()}`, action: 'Submission deadline updated', actor, occurredAt: new Date(clock()).toISOString() });
      applyAutomaticLocks();
      return this.getScope(scopeId);
    },
    async lockScope(scopeId, actor) {
      applyAutomaticLocks();
      const scope = findScope(scopeId);
      if (scope.lockedAt) throw new Error('This scope is already locked.');
      finalizeSubmittedRecords(scope);
      scope.lockedAt = new Date(clock()).toISOString();
      scope.lockedBy = actor;
      scope.lockReason = 'manual';
      scope.history.unshift({ id: `manual-${clock()}`, action: 'Scope manually finalized and locked', actor, occurredAt: scope.lockedAt });
      return this.getScope(scopeId);
    },
  };
}

const mock = createGradingLocksMock();
export const listGradingScopes = () => mock.listScopes();
export const getGradingScope = scopeId => mock.getScope(scopeId);
export const updateGradingDeadline = (scopeId, deadline, actor) => mock.updateDeadline(scopeId, deadline, actor);
export const finalizeAndLockScope = (scopeId, actor) => mock.lockScope(scopeId, actor);
