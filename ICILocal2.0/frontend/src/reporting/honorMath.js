// Exact decimal arithmetic for honor-policy worked examples, so the editor can show what the server will compute.
const SCALE = 4n;
const TEN = 10n;

function toScaled(value) {
  const text = String(value ?? '').trim();
  const match = /^(-?)(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match || (match[2] === '' && !match[3])) return null;
  const fraction = (match[3] ?? '').slice(0, Number(SCALE)).padEnd(Number(SCALE), '0');
  const scaled = BigInt(`${match[2] || '0'}${fraction}`);
  return match[1] ? -scaled : scaled;
}

function divide(numerator, denominator, rounding) {
  const quotient = numerator / denominator;
  const remainder = numerator % denominator;
  if (remainder === 0n || rounding === 'DOWN') return quotient;
  const twice = 2n * (remainder < 0n ? -remainder : remainder);
  const absDen = denominator < 0n ? -denominator : denominator;
  const up = twice > absDen || (twice === absDen && (rounding === 'HALF_UP' || quotient % 2n !== 0n));
  return up ? quotient + 1n : quotient;
}

function format(scaled, places) {
  const negative = scaled < 0n;
  const digits = (negative ? -scaled : scaled).toString().padStart(places + 1, '0');
  const whole = digits.slice(0, digits.length - places);
  const fraction = places ? `.${digits.slice(-places)}` : '';
  return `${negative ? '-' : ''}${whole}${fraction}`;
}

/** Weighted average of `values` with `weights`, rounded once to `places` decimals. Returns a string, or null for bad input. */
export function weightedAverage(values, weights, places, rounding) {
  if (!values.length || values.length !== weights.length) return null;
  let sum = 0n;
  let total = 0n;
  for (let index = 0; index < values.length; index++) {
    const value = toScaled(values[index]);
    const weight = toScaled(weights[index]);
    if (value === null || weight === null || weight <= 0n) return null;
    sum += value * weight;
    total += weight;
  }
  const digits = Math.max(0, Math.min(4, Number(places) || 0));
  return format(divide(sum * TEN ** BigInt(digits), total * TEN ** SCALE, rounding), digits);
}

/** The honor category an average earns: the highest threshold it meets, provided every grade meets the subject minimum. */
export function categoryFor(average, values, minimumSubjectGrade, categories) {
  const avg = toScaled(average);
  const minimum = toScaled(minimumSubjectGrade);
  if (avg === null) return null;
  if (minimum !== null && values.some(value => (toScaled(value) ?? -1n) < minimum)) return null;
  let best = null;
  for (const category of categories) {
    const threshold = toScaled(category.minimumAverage);
    if (threshold === null || !String(category.name || '').trim() || avg < threshold) continue;
    if (!best || threshold > best.threshold) best = { name: category.name.trim(), threshold };
  }
  return best?.name ?? null;
}
