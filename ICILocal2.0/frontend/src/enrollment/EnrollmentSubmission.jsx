import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { Failure } from '../EnrollmentWorkspace.jsx';
import {
  clearSubmissionDraft,
  DEFAULT_REQUIREMENT_TYPES,
  formatFileSize,
  loadPortalReferenceData,
  loadSubmissionDraft,
  saveSubmissionDraft,
  submitEnrollmentApplication,
  validateRequirementFile,
} from './studentSubmissionApi.js';
import './enrollment-submission.css';

const STEPS = [
  { key: 'preferences', short: 'Preferences', title: 'Enrollment Preferences', subtitle: 'Select your target grade level, strand, campus, and learning setup.' },
  { key: 'student', short: 'Student Info', title: 'Student Information', subtitle: 'Enter your personal details as shown on your PSA Birth Certificate.' },
  { key: 'address', short: 'Home Address', title: 'Complete Home Address', subtitle: 'Provide your current residential address.' },
  { key: 'school', short: 'Current School', title: 'Current / Previous School', subtitle: 'Tell us about your current or most recently attended school.' },
  { key: 'guardian', short: 'Parent / Guardian', title: 'Parent & Guardian Information', subtitle: 'Provide parent or guardian contact details for official school notices.' },
  { key: 'documents', short: 'Requirements', title: 'Upload Requirements & Review', subtitle: 'Attach available admission credentials (PDF, JPG, or PNG up to 2 MB each) and submit.' },
];

const GRADE_LEVELS = ['Grade 11', 'Grade 12', 'TESDA / TVET'];
const TRACK_OPTIONS = [
  { value: 'ACADEMIC', label: 'Academic Track' },
  { value: 'TVL', label: 'Technical-Vocational-Livelihood (TVL)' },
  { value: 'TVET', label: 'TESDA / TVET' },
];
const CATEGORY_OPTIONS = [
  { value: 'new', label: 'New / Incoming Student' },
  { value: 'transferee', label: 'Transferee' },
  { value: 'returnee', label: 'Returning Student' },
  { value: 'continuing', label: 'Continuing ICI Student' },
];
const LEARNING_MODALITIES = [
  'Modular (Print)',
  'Modular (Digital)',
  'Online',
  'Educational Television',
  'Radio-Based Instruction',
  'Homeschooling',
  'Blended',
  'Face-to-Face',
];
const REQUIREMENT_HINTS = {
  PSA: 'Clear photo or scan of your PSA-issued Birth Certificate.',
  FORM137: 'Latest SF10 / Form 137 or Junior High School Report Card (SF9).',
  COMPLETION: 'Junior High School Certificate of Completion or Diploma.',
  GOOD_MORAL: 'Certificate of Good Moral Character from your previous school.',
};

const INITIAL_VALUES = {
  gradeLevel: '',
  track: '',
  programId: '',
  campusId: '',
  termId: '',
  category: 'new',
  voucher: 'REGULAR',
  learningModalities: [],
  learningModalityOther: '',

  lrnStatus: 'yes',
  psaBirthCertNo: '',
  lrn: '',
  lastName: '',
  firstName: '',
  middleName: '',
  extensionName: '',
  birthDate: '',
  sex: '',
  phone: '',
  email: '',
  indigenousPeople: 'no',
  indigenousGroup: '',
  motherTongue: '',
  academicHonors: '',

  streetAddress: '',
  province: '',
  cityMunicipality: '',
  barangay: '',

  currentlyEnrolled: 'yes',
  previousSchool: '',
  previousSchoolAddress: '',
  previousSchoolId: '',

  fatherName: '',
  motherName: '',
  guardianName: '',
  guardianRelationship: 'Guardian',
  guardianPhone: '',
  referredBy: '',
};

function programTrack(program) {
  if (!program) return '';
  if (program.track) return program.track;
  if (program.category === 'TVET') return 'TVET';
  return String(program.code || '').startsWith('TVL_') ? 'TVL' : 'ACADEMIC';
}

function validateStep(stepIndex, values, programs) {
  const errors = {};
  if (stepIndex === 0) {
    if (!values.gradeLevel) errors.gradeLevel = 'Select your grade level.';
    if (!values.track) errors.track = 'Select a track.';
    if (!values.programId) {
      errors.programId = 'Select a strand or program.';
    } else if (programs.length) {
      const selected = programs.find(item => String(item.id) === String(values.programId));
      if (selected && values.track && programTrack(selected) !== values.track) {
        errors.programId = 'Selected strand does not match the chosen track.';
      }
    }
    if (!values.campusId) errors.campusId = 'Select your preferred campus.';
    if (!values.termId) errors.termId = 'Select an academic term.';
    if (!values.category) errors.category = 'Select an enrollment category.';
  }

  if (stepIndex === 1) {
    const trimmedLrn = values.lrn.trim();
    if (values.lrnStatus === 'yes' && !trimmedLrn) {
      errors.lrn = 'Enter your 12-digit LRN, or select "No LRN" if you do not have one.';
    } else if (trimmedLrn && !/^[0-9]{12}$/.test(trimmedLrn)) {
      errors.lrn = 'LRN must be exactly 12 numeric digits.';
    }
    if (!values.lastName.trim()) errors.lastName = 'Enter your last name.';
    if (!values.firstName.trim()) errors.firstName = 'Enter your first name.';
    if (!values.birthDate) {
      errors.birthDate = 'Enter your date of birth.';
    } else {
      const today = new Date().toISOString().slice(0, 10);
      if (values.birthDate >= today) errors.birthDate = 'Date of birth must be in the past.';
    }
    if (!values.sex) errors.sex = 'Select sex.';
    if (!values.phone.trim()) {
      errors.phone = 'Enter an active mobile or contact number.';
    } else if (values.phone.replace(/[^0-9]/g, '').length < 7) {
      errors.phone = 'Enter a valid contact number (at least 7 digits).';
    }
    if (!values.email.trim()) {
      errors.email = 'Enter your email address.';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim())) {
      errors.email = 'Enter a valid email address.';
    }
    if (values.indigenousPeople === 'yes' && !values.indigenousGroup.trim()) {
      errors.indigenousGroup = 'Specify your Indigenous Peoples community or group.';
    }
  }

  if (stepIndex === 2) {
    if (!values.streetAddress.trim()) errors.streetAddress = 'Enter your house number and street.';
    if (!values.province.trim()) errors.province = 'Enter your province.';
    if (!values.cityMunicipality.trim()) errors.cityMunicipality = 'Enter your city or municipality.';
    if (!values.barangay.trim()) errors.barangay = 'Enter your barangay.';
  }

  if (stepIndex === 3) {
    if (!values.currentlyEnrolled) errors.currentlyEnrolled = 'Select whether you are currently enrolled.';
    if (values.currentlyEnrolled === 'yes' && !values.previousSchool.trim()) {
      errors.previousSchool = 'Enter the name of your current school.';
    }
    if (!values.previousSchool.trim() && values.category === 'transferee') {
      errors.previousSchool = 'Enter your previous school name.';
    }
  }

  if (stepIndex === 4) {
    const hasAnyContactName = Boolean(
      values.guardianName.trim() || values.fatherName.trim() || values.motherName.trim()
    );
    if (!hasAnyContactName) {
      errors.guardianName = 'Enter at least one parent or guardian name.';
    }
    if (!values.guardianPhone.trim()) {
      errors.guardianPhone = 'Enter a parent or guardian contact number.';
    } else if (values.guardianPhone.replace(/[^0-9]/g, '').length < 7) {
      errors.guardianPhone = 'Enter a valid contact number (at least 7 digits).';
    }
  }

  return errors;
}

function buildGuardians(values) {
  const fullAddress = [
    values.streetAddress.trim(),
    values.barangay.trim(),
    values.cityMunicipality.trim(),
    values.province.trim(),
  ]
    .filter(Boolean)
    .join(', ');

  const guardians = [];
  const primaryName =
    values.guardianName.trim() || values.motherName.trim() || values.fatherName.trim();
  const primaryRelationship = values.guardianName.trim()
    ? values.guardianRelationship.trim() || 'Guardian'
    : values.motherName.trim()
      ? 'Mother'
      : 'Father';

  if (primaryName) {
    guardians.push({
      fullName: primaryName,
      relationship: primaryRelationship,
      phone: values.guardianPhone.trim(),
      address: fullAddress || null,
      primaryContact: true,
    });
  }
  if (values.fatherName.trim() && values.fatherName.trim() !== primaryName) {
    guardians.push({
      fullName: values.fatherName.trim(),
      relationship: 'Father',
      phone: null,
      address: fullAddress || null,
      primaryContact: false,
    });
  }
  if (values.motherName.trim() && values.motherName.trim() !== primaryName) {
    guardians.push({
      fullName: values.motherName.trim(),
      relationship: 'Mother',
      phone: null,
      address: fullAddress || null,
      primaryContact: false,
    });
  }
  return guardians;
}

function formatSavedTime(iso) {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return '';
  }
}

export default function EnrollmentSubmission({ standalone = false }) {
  const [catalog, setCatalog] = useState({
    campuses: [],
    programs: [],
    terms: [],
    vouchers: [],
    requirementTypes: DEFAULT_REQUIREMENT_TYPES,
  });
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(null);
  const [reloadTick, setReloadTick] = useState(0);

  const [step, setStep] = useState(0);
  const [maxStep, setMaxStep] = useState(0);
  const [values, setValues] = useState(INITIAL_VALUES);
  const [errors, setErrors] = useState({});
  const [stepNotice, setStepNotice] = useState('');
  const [draftNotice, setDraftNotice] = useState('');
  const [restoredAt, setRestoredAt] = useState('');

  const [attachedFiles, setAttachedFiles] = useState({});
  const [fileErrors, setFileErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [confirmation, setConfirmation] = useState(null);
  const [copiedRef, setCopiedRef] = useState(false);

  // Restore any locally saved progress on initial mount.
  useEffect(() => {
    const draft = loadSubmissionDraft();
    if (!draft) return;
    setValues(prev => ({ ...prev, ...draft.values }));
    const savedStep = Number.isInteger(draft.step) ? Math.min(Math.max(draft.step, 0), STEPS.length - 1) : 0;
    const savedMax = Number.isInteger(draft.maxStep) ? Math.min(Math.max(draft.maxStep, savedStep), STEPS.length - 1) : savedStep;
    setStep(savedStep);
    setMaxStep(savedMax);
    setRestoredAt(draft.savedAt || '');
  }, []);

  // Load active campuses, programs, terms, vouchers, and requirement types.
  useEffect(() => {
    const controller = new AbortController();
    setCatalogLoading(true);
    setCatalogError(null);
    loadPortalReferenceData(controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        const campuses = Array.isArray(data?.campuses) ? data.campuses : [];
        const programs = Array.isArray(data?.programs) ? data.programs : [];
        const terms = Array.isArray(data?.terms) ? data.terms : [];
        const vouchers = Array.isArray(data?.vouchers) ? data.vouchers : [];
        const requirementTypes =
          Array.isArray(data?.requirementTypes) && data.requirementTypes.length
            ? data.requirementTypes
            : DEFAULT_REQUIREMENT_TYPES;
        setCatalog({ campuses, programs, terms, vouchers, requirementTypes });
        setValues(prev => ({
          ...prev,
          termId: prev.termId || (terms[0] ? String(terms[0].id) : ''),
        }));
      })
      .catch(err => {
        if (err?.name === 'AbortError' || controller.signal.aborted) return;
        setCatalogError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setCatalogLoading(false);
      });
    return () => controller.abort();
  }, [reloadTick]);

  const availableTracks = useMemo(() => {
    if (!catalog.programs.length) return TRACK_OPTIONS;
    const present = new Set(catalog.programs.map(programTrack));
    return TRACK_OPTIONS.filter(option => present.has(option.value));
  }, [catalog.programs]);

  const filteredPrograms = useMemo(() => {
    if (!values.track) return catalog.programs;
    return catalog.programs.filter(item => programTrack(item) === values.track);
  }, [catalog.programs, values.track]);

  const selectedCampus = catalog.campuses.find(item => String(item.id) === String(values.campusId));
  const selectedTerm = catalog.terms.find(item => String(item.id) === String(values.termId));
  const selectedProgram = catalog.programs.find(item => String(item.id) === String(values.programId));
  const attachedCount = Object.values(attachedFiles).filter(Boolean).length;

  function updateField(name, value) {
    setValues(prev => {
      const next = { ...prev, [name]: value };
      if (name === 'track') {
        const currentProgram = catalog.programs.find(item => String(item.id) === String(prev.programId));
        if (currentProgram && programTrack(currentProgram) !== value) {
          next.programId = '';
        }
      }
      if (name === 'lrnStatus' && value === 'no') {
        next.lrn = '';
      }
      if (name === 'indigenousPeople' && value === 'no') {
        next.indigenousGroup = '';
      }
      return next;
    });
    setErrors(prev => {
      if (!prev[name]) return prev;
      const copy = { ...prev };
      delete copy[name];
      return copy;
    });
    setStepNotice('');
    setSubmitError('');
  }

  function toggleModality(label) {
    setValues(prev => {
      const exists = prev.learningModalities.includes(label);
      return {
        ...prev,
        learningModalities: exists
          ? prev.learningModalities.filter(item => item !== label)
          : [...prev.learningModalities, label],
      };
    });
  }

  function handleSaveProgress() {
    const saved = saveSubmissionDraft({ step, maxStep, values });
    if (saved) {
      setRestoredAt(saved.savedAt);
      setDraftNotice(
        'Progress saved on this device. You can safely close this page and return later (attached files will need to be re-selected).'
      );
    } else {
      setDraftNotice('Unable to save progress in this browser. Check storage settings.');
    }
  }

  function handleClearDraft() {
    clearSubmissionDraft();
    setValues({
      ...INITIAL_VALUES,
      termId: catalog.terms[0] ? String(catalog.terms[0].id) : '',
    });
    setStep(0);
    setMaxStep(0);
    setErrors({});
    setFileErrors({});
    setAttachedFiles({});
    setStepNotice('');
    setRestoredAt('');
    setDraftNotice('Saved draft cleared. Started a fresh application.');
  }

  function handleNext() {
    const stepErrors = validateStep(step, values, catalog.programs);
    if (Object.keys(stepErrors).length > 0) {
      setErrors(stepErrors);
      setStepNotice('Please complete the highlighted fields before continuing.');
      return;
    }
    setErrors({});
    setStepNotice('');
    const nextStep = Math.min(step + 1, STEPS.length - 1);
    setStep(nextStep);
    setMaxStep(prev => Math.max(prev, nextStep));
    saveSubmissionDraft({ step: nextStep, maxStep: Math.max(maxStep, nextStep), values });
  }

  function handleBack() {
    setErrors({});
    setStepNotice('');
    setStep(prev => Math.max(prev - 1, 0));
  }

  function handleJumpToStep(targetIndex) {
    if (targetIndex === step) return;
    if (targetIndex < step) {
      setErrors({});
      setStepNotice('');
      setStep(targetIndex);
      return;
    }
    // Validate every step between current and target before jumping forward.
    for (let i = step; i < targetIndex; i++) {
      const checkErrors = validateStep(i, values, catalog.programs);
      if (Object.keys(checkErrors).length > 0) {
        setStep(i);
        setErrors(checkErrors);
        setStepNotice(`Complete Step ${i + 1} (${STEPS[i].title}) before jumping ahead.`);
        return;
      }
    }
    setErrors({});
    setStepNotice('');
    setStep(targetIndex);
    setMaxStep(prev => Math.max(prev, targetIndex));
  }

  function handleFileSelect(code, event) {
    const file = event.target.files?.[0];
    if (!file) return;
    const message = validateRequirementFile(file);
    if (message) {
      setFileErrors(prev => ({ ...prev, [code]: message }));
      event.target.value = '';
      return;
    }
    setFileErrors(prev => {
      const copy = { ...prev };
      delete copy[code];
      return copy;
    });
    setAttachedFiles(prev => ({ ...prev, [code]: file }));
  }

  function handleRemoveFile(code) {
    setAttachedFiles(prev => {
      const copy = { ...prev };
      delete copy[code];
      return copy;
    });
    setFileErrors(prev => {
      const copy = { ...prev };
      delete copy[code];
      return copy;
    });
  }

  async function handleSubmit(event) {
    event.preventDefault();
    // Re-validate steps 1 through 5 before final submission.
    for (let i = 0; i < 5; i++) {
      const checkErrors = validateStep(i, values, catalog.programs);
      if (Object.keys(checkErrors).length > 0) {
        setStep(i);
        setErrors(checkErrors);
        setStepNotice(`Please fix the highlighted fields in Step ${i + 1}: ${STEPS[i].title}.`);
        return;
      }
    }
    if (Object.keys(fileErrors).length > 0) {
      setSubmitError('Remove or replace invalid requirement files before submitting.');
      return;
    }

    const guardians = buildGuardians(values);
    const payload = {
      campusId: Number(values.campusId),
      termId: Number(values.termId),
      programId: Number(values.programId),
      category: values.category,
      track: values.track,
      gradeLevel: values.gradeLevel,
      voucher: values.voucher || 'REGULAR',
      lrnStatus: values.lrnStatus,
      psaBirthCertNo: values.psaBirthCertNo.trim() || null,
      lrn: values.lrnStatus === 'no' ? null : values.lrn.trim() || null,
      firstName: values.firstName.trim(),
      middleName: values.middleName.trim() || null,
      lastName: values.lastName.trim(),
      extensionName: values.extensionName.trim() || null,
      birthDate: values.birthDate,
      sex: values.sex,
      phone: values.phone.trim(),
      email: values.email.trim(),
      indigenousPeople: values.indigenousPeople === 'yes',
      indigenousGroup: values.indigenousPeople === 'yes' ? values.indigenousGroup.trim() || null : null,
      motherTongue: values.motherTongue.trim() || null,
      academicHonors: values.academicHonors.trim() || null,
      learningModalities: values.learningModalities,
      learningModalityOther: values.learningModalityOther.trim() || null,
      streetAddress: values.streetAddress.trim(),
      province: values.province.trim(),
      cityMunicipality: values.cityMunicipality.trim(),
      barangay: values.barangay.trim(),
      currentlyEnrolled: values.currentlyEnrolled,
      previousSchool: values.previousSchool.trim() || null,
      previousSchoolAddress: values.previousSchoolAddress.trim() || null,
      previousSchoolId: values.previousSchoolId.trim() || null,
      referredBy: values.referredBy.trim() || null,
      guardians,
    };

    setSubmitting(true);
    setSubmitError('');
    try {
      const result = await submitEnrollmentApplication(payload, attachedFiles);
      clearSubmissionDraft();
      setConfirmation(result);
      setRestoredAt('');
      setDraftNotice('');
      setCopiedRef(false);
    } catch (err) {
      setSubmitError(err?.message || 'Unable to submit your application right now. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCopyReference() {
    if (!confirmation?.applicationNumber) return;
    try {
      await navigator.clipboard?.writeText(confirmation.applicationNumber);
      setCopiedRef(true);
    } catch {
      setCopiedRef(false);
    }
  }

  function handleStartAnother() {
    setConfirmation(null);
    setValues({
      ...INITIAL_VALUES,
      termId: catalog.terms[0] ? String(catalog.terms[0].id) : '',
    });
    setStep(0);
    setMaxStep(0);
    setErrors({});
    setFileErrors({});
    setAttachedFiles({});
    setStepNotice('');
    setSubmitError('');
    setDraftNotice('');
  }

  const currentStepMeta = STEPS[step];
  const progressPercent = Math.round(((step + 1) / STEPS.length) * 100);

  const content = (
    <div className="submission-page">
      <header className="submission-header">
        <p className="eyebrow">Student Portal · Online Enrollment</p>
        <h1>Enrollment Submission</h1>
        <p>Complete the 6-step enrollment form below. You can save your progress on this device at any time.</p>
      </header>

      <Failure error={catalogError} reload={() => setReloadTick(value => value + 1)} />

      {confirmation ? (
        <section className="submission-confirmation" aria-labelledby="confirmation-title" role="status">
          <div>
            <p className="eyebrow">Application received</p>
            <h2 id="confirmation-title">Enrollment Application Submitted</h2>
            <p>
              Thank you, <strong>{confirmation.applicantName}</strong>. Your application has been queued for Registrar review.
            </p>
          </div>

          <div className="submission-ref-banner">
            <div>
              <span className="submission-ref-label">Application Reference Number</span>
              <div className="submission-ref-code" data-testid="reference-number">
                {confirmation.applicationNumber}
              </div>
            </div>
            <button type="button" className="button button-outline" onClick={handleCopyReference}>
              {copiedRef ? 'Copied' : 'Copy reference number'}
            </button>
          </div>

          <dl className="submission-review-grid">
            <div>
              <dt>Campus</dt>
              <dd>{confirmation.campus}</dd>
            </div>
            <div>
              <dt>Academic Term</dt>
              <dd>{confirmation.term}</dd>
            </div>
            <div>
              <dt>Strand / Program</dt>
              <dd>{confirmation.program}</dd>
            </div>
            <div>
              <dt>Grade Level</dt>
              <dd>{confirmation.gradeLevel}</dd>
            </div>
            <div>
              <dt>Submitted Date</dt>
              <dd>{confirmation.submittedAt}</dd>
            </div>
            <div>
              <dt>Queue Status</dt>
              <dd>
                <span className={`status status-${confirmation.status === 'duplicate_flag' ? 'warning' : 'success'}`}>
                  {confirmation.status === 'duplicate_flag' ? 'Under identity verification' : 'Submitted for review'}
                </span>
              </dd>
            </div>
          </dl>

          {Array.isArray(confirmation.uploadedDocuments) && confirmation.uploadedDocuments.length > 0 && (
            <div className="submission-review-box">
              <h3>Attached Requirement Documents ({confirmation.uploadedDocuments.length})</h3>
              <ul className="submission-next-steps">
                {confirmation.uploadedDocuments.map(doc => (
                  <li key={doc.code}>
                    <strong>{doc.code}</strong>: {doc.originalFilename} ({formatFileSize(doc.sizeBytes)}) — pending registrar verification
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div>
            <h3>What happens next</h3>
            <ol className="submission-next-steps">
              <li>Save or screenshot your reference number ({confirmation.applicationNumber}).</li>
              <li>The Registrar&apos;s Office will review your application details and verify any uploaded credentials.</li>
              <li>Bring original copies of your requirements during campus verification and orientation attendance.</li>
            </ol>
          </div>

          <div className="submission-actions">
            <button type="button" className="button button-outline" onClick={handleStartAnother}>
              Submit another application
            </button>
            {standalone && (
              <Link className="button" to="/sign-in">
                Return to Sign In
              </Link>
            )}
          </div>
        </section>
      ) : (
        <>
          {restoredAt && (
            <div className="submission-draft-bar" role="status">
              <span>
                Saved draft active on this device{formatSavedTime(restoredAt) ? ` (last saved ${formatSavedTime(restoredAt)})` : ''}.
              </span>
              <div className="submission-draft-actions">
                <button type="button" className="button button-outline" onClick={handleClearDraft}>
                  Start over
                </button>
              </div>
            </div>
          )}

          {draftNotice && (
            <p className="notice" role="status">
              {draftNotice}
            </p>
          )}

          <nav className="submission-stepper" aria-label="Application progress">
            <div className="submission-stepper-top">
              <span className="submission-stepper-count">
                Step {step + 1} of {STEPS.length}
              </span>
              <span className="submission-stepper-title">{currentStepMeta.title}</span>
            </div>
            <div className="submission-progress-track" aria-hidden="true">
              <div className="submission-progress-fill" style={{ width: `${progressPercent}%` }} />
            </div>
            <ol className="submission-steps-list">
              {STEPS.map((item, index) => {
                const isActive = index === step;
                const isCompleted = index < step;
                const canClick = index <= maxStep;
                return (
                  <li key={item.key}>
                    <button
                      type="button"
                      className={`submission-step-pill${isActive ? ' is-active' : ''}${isCompleted ? ' is-completed' : ''}`}
                      aria-current={isActive ? 'step' : undefined}
                      disabled={!canClick}
                      onClick={() => handleJumpToStep(index)}
                    >
                      <span className="submission-step-num">{isCompleted ? '✓' : index + 1}</span>
                      <span>{item.short}</span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </nav>

          <form className="submission-card" onSubmit={handleSubmit} noValidate>
            <div className="submission-section-head">
              <h2>
                {step + 1}. {currentStepMeta.title}
              </h2>
              <p>{currentStepMeta.subtitle}</p>
            </div>

            {catalogLoading && (
              <p role="status" className="field-help">
                Loading campuses, strands, and academic terms…
              </p>
            )}

            {stepNotice && (
              <div className="notice notice-error" role="alert">
                {stepNotice}
              </div>
            )}

            {submitError && (
              <div className="notice notice-error" role="alert">
                {submitError}
              </div>
            )}

            {/* STEP 1: Enrollment Preferences */}
            {step === 0 && (
              <div className="submission-grid">
                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-grade-level">
                      Grade Level<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-grade-level"
                      value={values.gradeLevel}
                      aria-invalid={Boolean(errors.gradeLevel)}
                      onChange={event => updateField('gradeLevel', event.target.value)}
                    >
                      <option value="">Select grade level</option>
                      {GRADE_LEVELS.map(level => (
                        <option key={level} value={level}>
                          {level}
                        </option>
                      ))}
                    </select>
                    {errors.gradeLevel && <span className="field-error" role="alert">{errors.gradeLevel}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-track">
                      Track<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-track"
                      value={values.track}
                      aria-invalid={Boolean(errors.track)}
                      onChange={event => updateField('track', event.target.value)}
                    >
                      <option value="">Select track</option>
                      {availableTracks.map(option => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    {errors.track && <span className="field-error" role="alert">{errors.track}</span>}
                  </div>
                </div>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-program">
                      Strand / Specialization<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-program"
                      value={values.programId}
                      aria-invalid={Boolean(errors.programId)}
                      onChange={event => updateField('programId', event.target.value)}
                    >
                      <option value="">
                        {values.track ? 'Select strand or specialization' : 'Choose a track first'}
                      </option>
                      {filteredPrograms.map(program => (
                        <option key={program.id} value={String(program.id)}>
                          {program.code} — {program.name}
                        </option>
                      ))}
                    </select>
                    {errors.programId && <span className="field-error" role="alert">{errors.programId}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-campus">
                      Preferred Campus<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-campus"
                      value={values.campusId}
                      aria-invalid={Boolean(errors.campusId)}
                      onChange={event => updateField('campusId', event.target.value)}
                    >
                      <option value="">Select campus</option>
                      {catalog.campuses.map(campus => (
                        <option key={campus.id} value={String(campus.id)}>
                          {campus.name}
                        </option>
                      ))}
                    </select>
                    {errors.campusId && <span className="field-error" role="alert">{errors.campusId}</span>}
                  </div>
                </div>

                <div className="submission-grid submission-grid-3">
                  <div className="submission-field">
                    <label htmlFor="sub-term">
                      Academic Term<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-term"
                      value={values.termId}
                      aria-invalid={Boolean(errors.termId)}
                      onChange={event => updateField('termId', event.target.value)}
                    >
                      <option value="">Select term</option>
                      {catalog.terms.map(term => (
                        <option key={term.id} value={String(term.id)}>
                          {term.academicYear ? `${term.academicYear} — ${term.name}` : term.name}
                        </option>
                      ))}
                    </select>
                    {errors.termId && <span className="field-error" role="alert">{errors.termId}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-category">
                      Student Type<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-category"
                      value={values.category}
                      aria-invalid={Boolean(errors.category)}
                      onChange={event => updateField('category', event.target.value)}
                    >
                      {CATEGORY_OPTIONS.map(option => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    {errors.category && <span className="field-error" role="alert">{errors.category}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-voucher">Voucher Classification</label>
                    <select
                      id="sub-voucher"
                      value={values.voucher}
                      onChange={event => updateField('voucher', event.target.value)}
                    >
                      {(catalog.vouchers.length
                        ? catalog.vouchers
                        : [
                            { code: 'REGULAR', name: 'Regular / Paying' },
                            { code: 'QVR', name: 'Qualified Voucher Recipient' },
                            { code: 'ESC', name: 'Education Service Contracting' },
                            { code: 'ALS', name: 'Alternative Learning System' },
                          ]
                      ).map(v => (
                        <option key={v.code} value={v.code}>
                          {v.code} — {v.name}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <fieldset className="submission-fieldset">
                  <legend>Preferred Distance / Learning Modalities (optional)</legend>
                  <div className="submission-option-grid">
                    {LEARNING_MODALITIES.map(modality => (
                      <label key={modality} className="submission-option-pill">
                        <input
                          type="checkbox"
                          checked={values.learningModalities.includes(modality)}
                          onChange={() => toggleModality(modality)}
                        />
                        <span>{modality}</span>
                      </label>
                    ))}
                  </div>
                  <div className="submission-field">
                    <label htmlFor="sub-modality-other">Other learning modality (optional)</label>
                    <input
                      id="sub-modality-other"
                      type="text"
                      maxLength={120}
                      placeholder="Specify if not listed above"
                      value={values.learningModalityOther}
                      onChange={event => updateField('learningModalityOther', event.target.value)}
                    />
                  </div>
                </fieldset>
              </div>
            )}

            {/* STEP 2: Student Information */}
            {step === 1 && (
              <div className="submission-grid">
                <fieldset className="submission-fieldset">
                  <legend>
                    Learner Reference Number (LRN) Status<span className="submission-required">*</span>
                  </legend>
                  <div className="submission-option-grid">
                    <label className="submission-option-pill">
                      <input
                        type="radio"
                        name="lrnStatus"
                        value="yes"
                        checked={values.lrnStatus === 'yes'}
                        onChange={event => updateField('lrnStatus', event.target.value)}
                      />
                      <span>With LRN</span>
                    </label>
                    <label className="submission-option-pill">
                      <input
                        type="radio"
                        name="lrnStatus"
                        value="no"
                        checked={values.lrnStatus === 'no'}
                        onChange={event => updateField('lrnStatus', event.target.value)}
                      />
                      <span>No LRN</span>
                    </label>
                    <label className="submission-option-pill">
                      <input
                        type="radio"
                        name="lrnStatus"
                        value="returning"
                        checked={values.lrnStatus === 'returning'}
                        onChange={event => updateField('lrnStatus', event.target.value)}
                      />
                      <span>Returning / Balik-Aral</span>
                    </label>
                  </div>
                </fieldset>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-lrn">
                      Learner Reference Number (LRN)
                      {values.lrnStatus === 'yes' && <span className="submission-required">*</span>}
                    </label>
                    <input
                      id="sub-lrn"
                      type="text"
                      inputMode="numeric"
                      maxLength={12}
                      disabled={values.lrnStatus === 'no'}
                      placeholder="12-digit LRN"
                      value={values.lrn}
                      aria-invalid={Boolean(errors.lrn)}
                      onChange={event => updateField('lrn', event.target.value.replace(/[^0-9]/g, ''))}
                    />
                    {errors.lrn && <span className="field-error" role="alert">{errors.lrn}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-psa-no">PSA Birth Certificate No. (if available)</label>
                    <input
                      id="sub-psa-no"
                      type="text"
                      maxLength={80}
                      placeholder="Optional"
                      value={values.psaBirthCertNo}
                      onChange={event => updateField('psaBirthCertNo', event.target.value)}
                    />
                  </div>
                </div>

                <div className="submission-grid submission-grid-3">
                  <div className="submission-field">
                    <label htmlFor="sub-last-name">
                      Last Name<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-last-name"
                      type="text"
                      maxLength={150}
                      autoComplete="family-name"
                      value={values.lastName}
                      aria-invalid={Boolean(errors.lastName)}
                      onChange={event => updateField('lastName', event.target.value)}
                    />
                    {errors.lastName && <span className="field-error" role="alert">{errors.lastName}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-first-name">
                      First Name<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-first-name"
                      type="text"
                      maxLength={150}
                      autoComplete="given-name"
                      value={values.firstName}
                      aria-invalid={Boolean(errors.firstName)}
                      onChange={event => updateField('firstName', event.target.value)}
                    />
                    {errors.firstName && <span className="field-error" role="alert">{errors.firstName}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-middle-name">Middle Name</label>
                    <input
                      id="sub-middle-name"
                      type="text"
                      maxLength={150}
                      autoComplete="additional-name"
                      value={values.middleName}
                      onChange={event => updateField('middleName', event.target.value)}
                    />
                  </div>
                </div>

                <div className="submission-grid submission-grid-3">
                  <div className="submission-field">
                    <label htmlFor="sub-extension">Extension Name</label>
                    <input
                      id="sub-extension"
                      type="text"
                      maxLength={30}
                      placeholder="e.g. Jr., III"
                      value={values.extensionName}
                      onChange={event => updateField('extensionName', event.target.value)}
                    />
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-dob">
                      Date of Birth<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-dob"
                      type="date"
                      value={values.birthDate}
                      aria-invalid={Boolean(errors.birthDate)}
                      onChange={event => updateField('birthDate', event.target.value)}
                    />
                    {errors.birthDate && <span className="field-error" role="alert">{errors.birthDate}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-sex">
                      Sex<span className="submission-required">*</span>
                    </label>
                    <select
                      id="sub-sex"
                      value={values.sex}
                      aria-invalid={Boolean(errors.sex)}
                      onChange={event => updateField('sex', event.target.value)}
                    >
                      <option value="">Select sex</option>
                      <option value="Male">Male</option>
                      <option value="Female">Female</option>
                    </select>
                    {errors.sex && <span className="field-error" role="alert">{errors.sex}</span>}
                  </div>
                </div>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-phone">
                      Contact Number<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-phone"
                      type="tel"
                      maxLength={40}
                      autoComplete="tel"
                      placeholder="09XX XXX XXXX"
                      value={values.phone}
                      aria-invalid={Boolean(errors.phone)}
                      onChange={event => updateField('phone', event.target.value)}
                    />
                    {errors.phone && <span className="field-error" role="alert">{errors.phone}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-email">
                      Email Address<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-email"
                      type="email"
                      maxLength={254}
                      autoComplete="email"
                      placeholder="student@example.com"
                      value={values.email}
                      aria-invalid={Boolean(errors.email)}
                      onChange={event => updateField('email', event.target.value)}
                    />
                    {errors.email && <span className="field-error" role="alert">{errors.email}</span>}
                  </div>
                </div>

                <div className="submission-subpanel">
                  <fieldset className="submission-fieldset">
                    <legend>Belonging to any Indigenous Peoples (IP) community?</legend>
                    <div className="submission-option-grid">
                      <label className="submission-option-pill">
                        <input
                          type="radio"
                          name="indigenousPeople"
                          value="no"
                          checked={values.indigenousPeople === 'no'}
                          onChange={event => updateField('indigenousPeople', event.target.value)}
                        />
                        <span>No</span>
                      </label>
                      <label className="submission-option-pill">
                        <input
                          type="radio"
                          name="indigenousPeople"
                          value="yes"
                          checked={values.indigenousPeople === 'yes'}
                          onChange={event => updateField('indigenousPeople', event.target.value)}
                        />
                        <span>Yes</span>
                      </label>
                    </div>
                  </fieldset>

                  {values.indigenousPeople === 'yes' && (
                    <div className="submission-field">
                      <label htmlFor="sub-ip-group">
                        Please specify IP community<span className="submission-required">*</span>
                      </label>
                      <input
                        id="sub-ip-group"
                        type="text"
                        maxLength={150}
                        value={values.indigenousGroup}
                        aria-invalid={Boolean(errors.indigenousGroup)}
                        onChange={event => updateField('indigenousGroup', event.target.value)}
                      />
                      {errors.indigenousGroup && <span className="field-error" role="alert">{errors.indigenousGroup}</span>}
                    </div>
                  )}

                  <div className="submission-grid submission-grid-2">
                    <div className="submission-field">
                      <label htmlFor="sub-mother-tongue">Mother Tongue</label>
                      <input
                        id="sub-mother-tongue"
                        type="text"
                        maxLength={100}
                        placeholder="e.g. Cebuano, Filipino, Maranao"
                        value={values.motherTongue}
                        onChange={event => updateField('motherTongue', event.target.value)}
                      />
                    </div>

                    <div className="submission-field">
                      <label htmlFor="sub-honors">Academic Honors (if any)</label>
                      <input
                        id="sub-honors"
                        type="text"
                        maxLength={200}
                        placeholder="e.g. With Honors"
                        value={values.academicHonors}
                        onChange={event => updateField('academicHonors', event.target.value)}
                      />
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* STEP 3: Home Address */}
            {step === 2 && (
              <div className="submission-grid">
                <div className="submission-field">
                  <label htmlFor="sub-street">
                    House Number &amp; Street<span className="submission-required">*</span>
                  </label>
                  <input
                    id="sub-street"
                    type="text"
                    maxLength={250}
                    autoComplete="street-address"
                    placeholder="House / Lot / Block / Street"
                    value={values.streetAddress}
                    aria-invalid={Boolean(errors.streetAddress)}
                    onChange={event => updateField('streetAddress', event.target.value)}
                  />
                  {errors.streetAddress && <span className="field-error" role="alert">{errors.streetAddress}</span>}
                </div>

                <div className="submission-grid submission-grid-3">
                  <div className="submission-field">
                    <label htmlFor="sub-province">
                      Province<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-province"
                      type="text"
                      maxLength={150}
                      placeholder="e.g. Lanao del Norte"
                      value={values.province}
                      aria-invalid={Boolean(errors.province)}
                      onChange={event => updateField('province', event.target.value)}
                    />
                    {errors.province && <span className="field-error" role="alert">{errors.province}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-city">
                      City / Municipality<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-city"
                      type="text"
                      maxLength={150}
                      placeholder="e.g. Iligan City"
                      value={values.cityMunicipality}
                      aria-invalid={Boolean(errors.cityMunicipality)}
                      onChange={event => updateField('cityMunicipality', event.target.value)}
                    />
                    {errors.cityMunicipality && <span className="field-error" role="alert">{errors.cityMunicipality}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-barangay">
                      Barangay<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-barangay"
                      type="text"
                      maxLength={150}
                      placeholder="e.g. Poblacion"
                      value={values.barangay}
                      aria-invalid={Boolean(errors.barangay)}
                      onChange={event => updateField('barangay', event.target.value)}
                    />
                    {errors.barangay && <span className="field-error" role="alert">{errors.barangay}</span>}
                  </div>
                </div>
              </div>
            )}

            {/* STEP 4: Current School */}
            {step === 3 && (
              <div className="submission-grid">
                <fieldset className="submission-fieldset">
                  <legend>
                    Are you currently enrolled in a school?<span className="submission-required">*</span>
                  </legend>
                  <div className="submission-option-grid">
                    <label className="submission-option-pill">
                      <input
                        type="radio"
                        name="currentlyEnrolled"
                        value="yes"
                        checked={values.currentlyEnrolled === 'yes'}
                        onChange={event => updateField('currentlyEnrolled', event.target.value)}
                      />
                      <span>Yes, currently enrolled</span>
                    </label>
                    <label className="submission-option-pill">
                      <input
                        type="radio"
                        name="currentlyEnrolled"
                        value="no"
                        checked={values.currentlyEnrolled === 'no'}
                        onChange={event => updateField('currentlyEnrolled', event.target.value)}
                      />
                      <span>No (returning / gap year / ALS)</span>
                    </label>
                  </div>
                </fieldset>

                <div className="submission-field">
                  <label htmlFor="sub-school-name">
                    Current / Previous School Name
                    {(values.currentlyEnrolled === 'yes' || values.category === 'transferee') && (
                      <span className="submission-required">*</span>
                    )}
                  </label>
                  <input
                    id="sub-school-name"
                    type="text"
                    maxLength={250}
                    placeholder="Full school name"
                    value={values.previousSchool}
                    aria-invalid={Boolean(errors.previousSchool)}
                    onChange={event => updateField('previousSchool', event.target.value)}
                  />
                  {errors.previousSchool && <span className="field-error" role="alert">{errors.previousSchool}</span>}
                </div>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-school-address">School Address</label>
                    <input
                      id="sub-school-address"
                      type="text"
                      maxLength={500}
                      placeholder="City / Municipality, Province"
                      value={values.previousSchoolAddress}
                      onChange={event => updateField('previousSchoolAddress', event.target.value)}
                    />
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-school-id">School ID (DepEd School ID, if known)</label>
                    <input
                      id="sub-school-id"
                      type="text"
                      maxLength={50}
                      placeholder="Optional"
                      value={values.previousSchoolId}
                      onChange={event => updateField('previousSchoolId', event.target.value)}
                    />
                  </div>
                </div>
              </div>
            )}

            {/* STEP 5: Parent / Guardian */}
            {step === 4 && (
              <div className="submission-grid">
                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-father-name">Father&apos;s Full Name</label>
                    <input
                      id="sub-father-name"
                      type="text"
                      maxLength={250}
                      placeholder="Last Name, First Name, Middle Name"
                      value={values.fatherName}
                      onChange={event => updateField('fatherName', event.target.value)}
                    />
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-mother-name">Mother&apos;s Full Name</label>
                    <input
                      id="sub-mother-name"
                      type="text"
                      maxLength={250}
                      placeholder="Last Name, First Name, Middle Name"
                      value={values.motherName}
                      onChange={event => updateField('motherName', event.target.value)}
                    />
                  </div>
                </div>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-guardian-name">
                      Primary Guardian Name<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-guardian-name"
                      type="text"
                      maxLength={250}
                      placeholder="Guardian or parent to contact first"
                      value={values.guardianName}
                      aria-invalid={Boolean(errors.guardianName)}
                      onChange={event => updateField('guardianName', event.target.value)}
                    />
                    {errors.guardianName && <span className="field-error" role="alert">{errors.guardianName}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-guardian-rel">Relationship to Applicant</label>
                    <input
                      id="sub-guardian-rel"
                      type="text"
                      maxLength={80}
                      placeholder="e.g. Mother, Father, Aunt, Guardian"
                      value={values.guardianRelationship}
                      onChange={event => updateField('guardianRelationship', event.target.value)}
                    />
                  </div>
                </div>

                <div className="submission-grid submission-grid-2">
                  <div className="submission-field">
                    <label htmlFor="sub-guardian-phone">
                      Guardian Contact Number<span className="submission-required">*</span>
                    </label>
                    <input
                      id="sub-guardian-phone"
                      type="tel"
                      maxLength={40}
                      placeholder="09XX XXX XXXX"
                      value={values.guardianPhone}
                      aria-invalid={Boolean(errors.guardianPhone)}
                      onChange={event => updateField('guardianPhone', event.target.value)}
                    />
                    {errors.guardianPhone && <span className="field-error" role="alert">{errors.guardianPhone}</span>}
                  </div>

                  <div className="submission-field">
                    <label htmlFor="sub-referred-by">Referred By (optional)</label>
                    <input
                      id="sub-referred-by"
                      type="text"
                      maxLength={150}
                      placeholder="Teacher, student, or campaign"
                      value={values.referredBy}
                      onChange={event => updateField('referredBy', event.target.value)}
                    />
                  </div>
                </div>

                <p className="submission-consent">
                  By submitting this form, I certify that the information provided is true and accurate to the best of my
                  knowledge, and I consent to Iligan Computer Institute processing this enrollment application in accordance
                  with school privacy policies.
                </p>
              </div>
            )}

            {/* STEP 6: Upload Requirements & Review */}
            {step === 5 && (
              <div className="submission-grid">
                <div className="submission-upload-list">
                  {catalog.requirementTypes.map(req => {
                    const file = attachedFiles[req.code];
                    const errMsg = fileErrors[req.code];
                    const inputId = `sub-file-${req.code}`;
                    return (
                      <div key={req.code} className={`submission-upload-card${file ? ' has-file' : ''}`}>
                        <div className="submission-upload-head">
                          <div>
                            <h3>{req.name}</h3>
                            <p className="submission-upload-meta">
                              {REQUIREMENT_HINTS[req.code] || 'Upload a clear scan or photo (PDF, JPG, or PNG, max 2 MB).'}
                            </p>
                          </div>
                          <span className={`status status-${file ? 'success' : 'warning'}`}>
                            {file ? 'Attached' : req.mandatory ? 'Required for enrollment' : 'Optional'}
                          </span>
                        </div>

                        <div className="submission-upload-controls">
                          <label htmlFor={inputId} className="field-help">
                            Upload {req.name} (PDF, JPG, PNG · max 2 MB)
                          </label>
                          <input
                            id={inputId}
                            className="submission-file-input"
                            type="file"
                            accept=".pdf,.jpg,.jpeg,.png"
                            aria-label={`Upload ${req.name}`}
                            onChange={event => handleFileSelect(req.code, event)}
                          />
                          {errMsg && (
                            <span className="field-error" role="alert">
                              {errMsg}
                            </span>
                          )}
                          {file && (
                            <div className="submission-file-chip">
                              <span className="submission-file-chip-name">
                                {file.name} ({formatFileSize(file.size)})
                              </span>
                              <button
                                type="button"
                                className="button button-outline"
                                onClick={() => handleRemoveFile(req.code)}
                              >
                                Remove
                              </button>
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>

                <div className="submission-review-box" aria-labelledby="review-summary-title">
                  <h3 id="review-summary-title">Application Summary</h3>
                  <dl className="submission-review-grid">
                    <div>
                      <dt>Applicant</dt>
                      <dd>
                        {[values.firstName, values.middleName, values.lastName, values.extensionName]
                          .filter(Boolean)
                          .join(' ')}
                      </dd>
                    </div>
                    <div>
                      <dt>Campus &amp; Term</dt>
                      <dd>
                        {selectedCampus?.name || '—'} · {selectedTerm ? `${selectedTerm.academicYear || ''} ${selectedTerm.name}`.trim() : '—'}
                      </dd>
                    </div>
                    <div>
                      <dt>Grade Level &amp; Strand</dt>
                      <dd>
                        {values.gradeLevel} · {selectedProgram ? `${selectedProgram.code} — ${selectedProgram.name}` : '—'}
                      </dd>
                    </div>
                    <div>
                      <dt>Contact &amp; Email</dt>
                      <dd>
                        {values.phone} · {values.email}
                      </dd>
                    </div>
                    <div>
                      <dt>Home Address</dt>
                      <dd>
                        {[values.streetAddress, values.barangay, values.cityMunicipality, values.province]
                          .filter(Boolean)
                          .join(', ')}
                      </dd>
                    </div>
                    <div>
                      <dt>Requirements Attached</dt>
                      <dd>
                        {attachedCount} of {catalog.requirementTypes.length} documents selected
                      </dd>
                    </div>
                  </dl>
                </div>
              </div>
            )}

            <div className="submission-actions">
              <button type="button" className="button button-outline" onClick={handleSaveProgress} disabled={submitting}>
                Save progress
              </button>
              <div className="submission-actions-primary">
                {step > 0 && (
                  <button type="button" className="button button-outline" onClick={handleBack} disabled={submitting}>
                    Back
                  </button>
                )}
                {step < STEPS.length - 1 ? (
                  <button type="button" className="button" onClick={handleNext} disabled={catalogLoading}>
                    Next
                  </button>
                ) : (
                  <button type="submit" className="button" disabled={submitting || catalogLoading}>
                    {submitting ? 'Submitting application…' : 'Submit application'}
                  </button>
                )}
              </div>
            </div>
          </form>
        </>
      )}
    </div>
  );

  if (!standalone) {
    return content;
  }

  return (
    <div className="submission-standalone">
      <header className="submission-standalone-header">
        <div className="submission-standalone-brand">
          <span className="brand-mark">ICI</span>
          <div>
            <strong>Iligan Computer Institute</strong>
            <span>Prospective Student Enrollment Portal</span>
          </div>
        </div>
        <Link className="submission-standalone-link" to="/sign-in">
          Staff sign in
        </Link>
      </header>
      <main className="submission-standalone-main">{content}</main>
    </div>
  );
}
