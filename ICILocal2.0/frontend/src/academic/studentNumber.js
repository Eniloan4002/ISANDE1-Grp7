// Students created in the app get "STU-<uuid>" numbers. Screens show a short form and keep the full value in a tooltip.
const generated = /^(STU-)([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function shortStudentNumber(value) {
  const text = value == null ? '' : String(value);
  const match = text.match(generated);
  return match ? `${match[1]}${match[2].toUpperCase()}` : text;
}
