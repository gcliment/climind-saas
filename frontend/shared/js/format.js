// Formatos de Argentina: moneda ARS, fechas en America/Argentina/Buenos_Aires.

export const TIME_ZONE = 'America/Argentina/Buenos_Aires';
const LOCALE = 'es-AR';

const moneyFormatters = new Map();

/**
 * Formatea un importe (number o string decimal del backend) como "$ 185.500,00".
 * @param {number|string} amount
 * @param {{decimals?: number, currency?: string}} [options]
 */
export function formatMoney(amount, { decimals = 2, currency = 'ARS' } = {}) {
  const key = `${currency}:${decimals}`;
  if (!moneyFormatters.has(key)) {
    moneyFormatters.set(key, new Intl.NumberFormat(LOCALE, {
      style: 'currency',
      currency,
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }));
  }
  const value = typeof amount === 'string' ? Number(amount) : amount;
  // Intl usa un espacio duro entre el símbolo y el número: se normaliza a "$ 185.500,00".
  return moneyFormatters.get(key).format(Number.isFinite(value) ? value : 0).replace(/ /g, ' ');
}

export function formatNumber(value, decimals = 0) {
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: decimals }).format(value);
}

/** "7 de octubre de 2026, 12:00" en hora de Buenos Aires. */
export function formatDateTime(iso) {
  return new Intl.DateTimeFormat(LOCALE, {
    dateStyle: 'long', timeStyle: 'short', timeZone: TIME_ZONE,
  }).format(new Date(iso));
}

export function formatDate(iso) {
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeZone: TIME_ZONE }).format(new Date(iso));
}

/** Fecha sin hora del backend ("2026-10-22") → "22 oct 2026". Se fija al mediodía UTC para no cambiar de día. */
export function formatLocalDate(isoDate) {
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium", timeZone: TIME_ZONE }).format(new Date(`${isoDate}T12:00:00Z`));
}

/** Fecha sin hora de hoy en Buenos Aires ("2026-10-07"), para filtros y campos de fecha. */
export function todayIso() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TIME_ZONE }).format(new Date());
}

/** WhatsApp en formato de wa.me ("5491123456789") → "+54 9 11 2345-6789". Otros países: "+" y los dígitos. */
export function formatPhone(digits) {
  if (!digits) return '';
  const m = /^549(11|2\d{2}|3\d{2})(\d{3,4})(\d{4})$/.exec(digits);
  if (m) return `+54 9 ${m[1]} ${m[2]}-${m[3]}`;
  return `+${digits}`;
}
