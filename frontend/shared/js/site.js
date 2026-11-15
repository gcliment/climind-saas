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

/**
 * Botones «Escribinos» ([data-contact], con el mensaje inicial en data-contact): WhatsApp comercial si está
 * configurado (APP_SUPPORT_WHATSAPP), si no el email de soporte. Sin ninguno de los dos, el botón se oculta.
 */
export function fillContactLinks(root = document) {
  const whatsapp = config.supportWhatsapp;
  const email = config.supportEmail;
  root.querySelectorAll('a[data-contact]').forEach((a) => {
    const message = a.dataset.contact || 'Hola, quiero consultar por Climind Cotiza.';
    if (whatsapp) {
      a.href = `https://wa.me/${whatsapp}?text=${encodeURIComponent(message)}`;
      a.target = '_blank';
      a.rel = 'noopener';
    } else if (email) {
      a.href = `mailto:${email}?subject=${encodeURIComponent('Cotizador a medida')}&body=${encodeURIComponent(message)}`;
    } else {
      a.hidden = true;
    }
  });
}

/** Enlaces a Climind Cotiza desde el portal (en producción viven en otro dominio). */
export function fillAppLinks(root = document) {
  if (!config.appUrl) return;
  root.querySelectorAll('a[data-app-link]').forEach((a) => {
    a.href = config.appUrl + a.dataset.appLink;
  });
}

fillSupportEmail();
fillContactLinks();
fillAppLinks();
