// Utilidades de interfaz: errores de formularios accesibles, estados de carga y avisos.

const PLAN_CODES = new Set(['PLAN_LIMIT_CALCULATORS', 'PLAN_FEATURE', 'QUOTA_EXCEEDED']);

/** Enlace a la página de planes cuando un error se debe a un límite del plan. */
export function planLink(error) {
  return error && PLAN_CODES.has(error.code) ? { href: '/app/plan', text: 'Ver planes' } : null;
}

/**
 * Muestra el aviso general de un formulario (role="alert" para lectores de pantalla).
 * @param {{href: string, text: string}|null} [link] acción opcional (p. ej. ir a los planes)
 */
export function showNotice(element, message, kind = 'error', link = null) {
  if (!element) return;
  element.className = `notice notice-${kind}`;
  element.textContent = message;
  if (link) {
    const a = document.createElement('a');
    a.href = link.href;
    a.textContent = link.text;
    element.append(' ', a);
  }
  element.hidden = false;
  element.setAttribute('role', kind === 'error' ? 'alert' : 'status');
}

export function hideNotice(element) {
  if (element) element.hidden = true;
}

/** Limpia los errores de validación de un formulario. */
export function clearFieldErrors(form) {
  form.querySelectorAll('.is-invalid').forEach((input) => {
    input.classList.remove('is-invalid');
    input.removeAttribute('aria-invalid');
  });
  form.querySelectorAll('.invalid-feedback[data-generated]').forEach((el) => el.remove());
}

/**
 * Marca los campos con error (fieldErrors del backend o validación local) y enfoca el primero.
 * @param {HTMLFormElement} form
 * @param {{field: string, message: string}[]} errors
 */
export function showFieldErrors(form, errors) {
  let first = null;
  for (const { field, message } of errors) {
    const input = form.elements.namedItem(field);
    if (!(input instanceof HTMLElement)) continue;
    input.classList.add('is-invalid');
    input.setAttribute('aria-invalid', 'true');
    const feedback = document.createElement('div');
    feedback.className = 'invalid-feedback d-block';
    feedback.dataset.generated = '';
    feedback.id = `${input.id || field}-error`;
    feedback.textContent = message;
    const anchor = input.closest('.password-field, .form-check') || input;
    anchor.after(feedback);
    input.setAttribute('aria-describedby', feedback.id);
    first ??= input;
  }
  first?.focus();
  return first !== null;
}

/** Ejecuta una acción con el botón en estado "cargando" (evita doble envío). */
export async function withBusy(button, action) {
  if (!button) return action();
  button.setAttribute('aria-busy', 'true');
  button.disabled = true;
  try {
    return await action();
  } finally {
    button.removeAttribute('aria-busy');
    button.disabled = false;
  }
}

/** Botones "Mostrar" en campos de contraseña. */
export function setupPasswordToggles(root = document) {
  root.querySelectorAll('.password-field').forEach((wrapper) => {
    const input = wrapper.querySelector('input');
    const button = wrapper.querySelector('.toggle-password');
    if (!input || !button) return;
    button.addEventListener('click', () => {
      const visible = input.type === 'text';
      input.type = visible ? 'password' : 'text';
      button.textContent = visible ? 'Mostrar' : 'Ocultar';
      button.setAttribute('aria-pressed', String(!visible));
    });
  });
}

/** Lee un formulario como objeto, con los textos recortados. */
export function formData(form) {
  const data = {};
  for (const element of form.elements) {
    if (!element.name || element.disabled) continue;
    if (element.type === 'checkbox') data[element.name] = element.checked;
    else if (element.type === 'password') data[element.name] = element.value;
    else data[element.name] = element.value.trim();
  }
  return data;
}

/** Destino seguro post-login: solo rutas internas de la app. */
export function safeNext(value, fallback = '/app/') {
  if (typeof value === 'string' && /^\/app\/[A-Za-z0-9/_-]*$/.test(value)) return value;
  return fallback;
}
