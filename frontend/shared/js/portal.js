// Portal Climind SaaS: los enlaces a Climind Cotiza apuntan a su dominio configurado
// (en producción el portal vive en otro host, p. ej. saas.climind-ia.com).
const appUrl = window.CLIMIND_CONFIG?.appUrl;
if (appUrl) {
  document.querySelectorAll('a[data-app-link]').forEach((a) => {
    a.href = appUrl + a.dataset.appLink;
  });
}
