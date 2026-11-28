import { useEffect, useMemo, useState } from 'react';
import { loadGradeCatalog } from './gradeEntryApi.js';

const text = value => (value == null ? '' : String(value));
const yearOf = term => text(term.academic_year_id ?? term.academicYearId);

/**
 * Academic year and term selection shared by the teacher and adviser screens.
 * `initial` can carry ids from a link (for example `?academicYearId=1&termId=10`).
 */
export function useAcademicContext(initial = {}) {
  const [catalog, setCatalog] = useState({ years: [], terms: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '' });
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    loadGradeCatalog().then(result => {
      if (!active) return;
      const years = result?.years || [];
      const terms = result?.terms || [];
      const year = years.find(item => text(item.id) === text(initial.academicYearId)) || years[0];
      const inYear = terms.filter(item => yearOf(item) === text(year?.id));
      const term = inYear.find(item => text(item.id) === text(initial.termId)) || inYear[0];
      setCatalog({ years, terms });
      setFilters({ academicYearId: text(year?.id), termId: text(term?.id) });
      setLoaded(true);
    }).catch(failure => {
      if (!active) return;
      setError(failure?.message || 'The academic years could not be loaded.');
      setLoaded(true);
    });
    return () => { active = false; };
    // The initial ids only matter for the first load.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const terms = useMemo(() => catalog.terms.filter(term => yearOf(term) === filters.academicYearId), [catalog.terms, filters.academicYearId]);

  function setYear(value) {
    const term = catalog.terms.find(item => yearOf(item) === value);
    setFilters({ academicYearId: value, termId: text(term?.id) });
  }
  const setTerm = value => setFilters(current => ({ ...current, termId: value }));

  return {
    catalog, terms, filters, loaded, error, setYear, setTerm,
    ready: Boolean(filters.academicYearId && filters.termId),
  };
}
