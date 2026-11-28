/** Dates travel as `YYYY-MM-DD`; tolerate a timestamp or full ISO value so date matching never breaks. */
export function isoDay(value) {
  if (typeof value === 'number') return new Date(value).toISOString().slice(0, 10);
  return String(value ?? '').slice(0, 10);
}
