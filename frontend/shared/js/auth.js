// Pantallas de acceso: registro, ingreso, verificación de email y recuperación de contraseña.
import { api, ApiError, currentUser } from './api.js';
import {
  clearFieldErrors, formData, hideNotice, safeNext, setupPasswordToggles, showFieldErrors, showNotice, withBusy,
} from './ui.js';

const page = document.body.dataset.page;
setupPasswordToggles();

/** Lee el token del enlace y lo saca de la barra de direcciones (no queda en el historial). */
function takeTokenFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token');
  if (token) window.history.replaceState(null, '', window.location.pathname);
  return token;
}

function handleError(form, notice, error) {
  if (error instanceof ApiError && error.fieldErrors.length > 0) {
    hideNotice(notice);
    if (!showFieldErrors(form, error.fieldErrors)) showNotice(notice, error.message);
    return;
  }
  showNotice(notice, error.message || 'No pudimos completar la operación. Probá de nuevo.');
  notice?.focus?.();
}

function requireFields(form, fields) {
  const missing = fields
    .filter(({ name }) => {
      const el = form.elements.namedItem(name);
      return el && (el.type === 'checkbox' ? !el.checked : !el.value.trim());
    })
    .map(({ name, message }) => ({ field: name, message }));
  return missing.length ? showFieldErrors(form, missing) : false;
}

/**
 * Después de ingresar: el super admin va a administración; un negocio que todavía no terminó la
 * configuración inicial (y no la salteó en este navegador) va al asistente.
 */
function destinationAfterLogin(me, next) {
  if (!me.tenant) return '/app/admin';
  let skipped = false;
  try { skipped = localStorage.getItem(`climind.onboardingSkipped.${me.user.id}`) === '1'; } catch { /* sin almacenamiento */ }
  if (!me.tenant.onboardingCompleted && !skipped && next === '/app/') return '/app/onboarding';
  return next;
}

function showSection(hideId, showId) {
  document.getElementById(hideId).hidden = true;
  const target = document.getElementById(showId);
  target.hidden = false;
  target.focus();
}

// ---------------------------------------------------------------- registro
if (page === 'registro') {
  const form = document.getElementById('register-form');
  const notice = document.getElementById('register-notice');
  let registeredEmail = '';

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    hideNotice(notice);
    if (requireFields(form, [
      { name: 'firstName', message: 'Ingresá tu nombre.' },
      { name: 'lastName', message: 'Ingresá tu apellido.' },
      { name: 'email', message: 'Ingresá tu email.' },
      { name: 'password', message: 'Elegí una contraseña.' },
      { name: 'passwordConfirmation', message: 'Repetí la contraseña.' },
      { name: 'acceptTerms', message: 'Para crear la cuenta tenés que aceptar los términos y la política de privacidad.' },
    ])) return;
    const data = formData(form);
    if (data.password !== data.passwordConfirmation) {
      showFieldErrors(form, [{ field: 'passwordConfirmation', message: 'Las contraseñas no coinciden.' }]);
      return;
    }
    await withBusy(form.querySelector('[type="submit"]'), async () => {
      try {
        await api.post('/api/v1/auth/register', data);
        registeredEmail = data.email;
        document.getElementById('sent-to').textContent = data.email;
        showSection('register-step', 'register-done');
      } catch (error) {
        handleError(form, notice, error);
      }
    });
  });

  const resendButton = document.getElementById('resend-button');
  resendButton.addEventListener('click', () => withBusy(resendButton, async () => {
    const resendNotice = document.getElementById('resend-notice');
    try {
      const result = await api.post('/api/v1/auth/resend-verification', { email: registeredEmail });
      showNotice(resendNotice, result.message, 'ok');
    } catch (error) {
      showNotice(resendNotice, error.message);
    }
  }));
}

// ---------------------------------------------------------------- ingreso
if (page === 'ingresar') {
  const form = document.getElementById('login-form');
  const notice = document.getElementById('login-notice');
  const resendLink = document.getElementById('resend-link');
  const next = safeNext(new URLSearchParams(window.location.search).get('next'));

  currentUser().then((me) => {
    if (me) window.location.replace(destinationAfterLogin(me, next));
  }).catch(() => {});

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    hideNotice(notice);
    resendLink.hidden = true;
    if (requireFields(form, [
      { name: 'email', message: 'Ingresá tu email.' },
      { name: 'password', message: 'Ingresá tu contraseña.' },
    ])) return;
    await withBusy(form.querySelector('[type="submit"]'), async () => {
      try {
        const me = await api.post('/api/v1/auth/login', formData(form));
        window.location.assign(destinationAfterLogin(me, next));
      } catch (error) {
        if (error.code === 'EMAIL_NOT_VERIFIED') resendLink.hidden = false;
        handleError(form, notice, error);
      }
    });
  });

  resendLink.addEventListener('click', () => withBusy(resendLink, async () => {
    try {
      const result = await api.post('/api/v1/auth/resend-verification', { email: form.email.value.trim() });
      showNotice(notice, result.message, 'ok');
    } catch (error) {
      showNotice(notice, error.message);
    }
  }));
}

// ---------------------------------------------------------------- verificación
if (page === 'verificar') {
  const token = takeTokenFromUrl();
  const fail = (message) => {
    document.getElementById('verify-error-message').textContent = message;
    showSection('verify-pending', 'verify-error');
  };
  if (!token) {
    fail('El enlace está incompleto. Abrilo directamente desde el email o pedí uno nuevo.');
  } else {
    api.post('/api/v1/auth/verify-email', { token })
      .then(() => showSection('verify-pending', 'verify-ok'))
      .catch((error) => fail(error.message));
  }

  const form = document.getElementById('verify-resend-form');
  const notice = document.getElementById('verify-resend-notice');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    if (requireFields(form, [{ name: 'email', message: 'Ingresá tu email.' }])) return;
    await withBusy(form.querySelector('[type="submit"]'), async () => {
      try {
        const result = await api.post('/api/v1/auth/resend-verification', formData(form));
        showNotice(notice, result.message, 'ok');
      } catch (error) {
        handleError(form, notice, error);
      }
    });
  });
}

// ---------------------------------------------------------------- olvidé mi contraseña
if (page === 'recuperar') {
  const form = document.getElementById('forgot-form');
  const notice = document.getElementById('forgot-notice');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    hideNotice(notice);
    if (requireFields(form, [{ name: 'email', message: 'Ingresá tu email.' }])) return;
    await withBusy(form.querySelector('[type="submit"]'), async () => {
      try {
        const data = formData(form);
        await api.post('/api/v1/auth/forgot-password', data);
        document.getElementById('forgot-sent-to').textContent = data.email;
        showSection('forgot-step', 'forgot-done');
      } catch (error) {
        handleError(form, notice, error);
      }
    });
  });
}

// ---------------------------------------------------------------- nueva contraseña
if (page === 'restablecer') {
  const token = takeTokenFromUrl();
  const form = document.getElementById('reset-form');
  const notice = document.getElementById('reset-notice');
  if (!token) {
    showNotice(notice, 'El enlace está incompleto. Abrilo directamente desde el email o pedí uno nuevo en “Olvidé mi contraseña”.');
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    hideNotice(notice);
    if (requireFields(form, [
      { name: 'password', message: 'Elegí una contraseña.' },
      { name: 'passwordConfirmation', message: 'Repetí la contraseña.' },
    ])) return;
    const data = formData(form);
    if (data.password !== data.passwordConfirmation) {
      showFieldErrors(form, [{ field: 'passwordConfirmation', message: 'Las contraseñas no coinciden.' }]);
      return;
    }
    await withBusy(form.querySelector('[type="submit"]'), async () => {
      try {
        await api.post('/api/v1/auth/reset-password', { ...data, token: token || '' });
        showSection('reset-step', 'reset-done');
      } catch (error) {
        handleError(form, notice, error);
      }
    });
  });
}
