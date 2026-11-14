// Página de precios: tarjetas y tabla comparativa con los planes vigentes de la base.
import { loadPlans, renderComparison, renderPlanCards } from './pricing.js';
import './site.js';

const grid = document.getElementById('pricing-grid');
try {
  const plans = await loadPlans();
  renderPlanCards(grid, plans);
  renderComparison(document.getElementById('compare'), plans);
} catch {
  const p = document.createElement('p');
  p.className = 'pricing-error';
  p.textContent = 'No pudimos cargar los planes en este momento. Probá recargar la página en unos minutos.';
  grid.replaceChildren(p);
  document.getElementById('compare').closest('section').hidden = true;
}
