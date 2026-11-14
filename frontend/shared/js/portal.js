// Portal Climind SaaS. En producción vive en otro host (p. ej. saas.climind-ia.com) del mismo contenedor: los
// enlaces a Climind Cotiza apuntan a su dominio configurado y la API de planes responde en el mismo origen.
import { loadPlans, renderPlanCards } from './pricing.js';
import './site.js';

const appUrl = window.CLIMIND_CONFIG?.appUrl || '';
const grid = document.getElementById('pricing-grid');
try {
  const plans = await loadPlans();
  renderPlanCards(grid, plans, { href: () => `${appUrl}/registro` });
} catch {
  const p = document.createElement('p');
  p.className = 'pricing-error';
  p.textContent = 'No pudimos cargar los planes en este momento.';
  grid.replaceChildren(p);
}
