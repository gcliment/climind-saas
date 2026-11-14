// Piezas comunes de las páginas públicas (landing, precios, ayuda, portal). Requiere /site-config.js.
const config = window.CLIMIND_CONFIG || {};

/** Email de soporte configurado (APP_SUPPORT_EMAIL). Sin configurar, se muestra el texto alternativo. */
export function fillSupportEmail(root = document) {
  const email = config.supportEmail;
  root.querySelectorAll('[data-support-email]').forEach((el) => {
    if (!email) return;
    const a = document.createElement('a');
    a.href = `mailto:${email}`;
    a.textContent = email;
    el.replaceChildren(a);
  });
  root.querySelectorAll('[data-support-only]').forEach((el) => { el.hidden = !email; });
}

/** Enlaces a Climind Cotiza desde el portal (en producción viven en otro dominio). */
export function fillAppLinks(root = document) {
  if (!config.appUrl) return;
  root.querySelectorAll('a[data-app-link]').forEach((a) => {
    a.href = config.appUrl + a.dataset.appLink;
  });
}

fillSupportEmail();
fillAppLinks();
