// Centro de ayuda: búsqueda en el índice (sin servidor: los artículos son pocos) y email de soporte.
import './site.js';

const supportEmail = window.CLIMIND_CONFIG?.supportEmail;
document.querySelectorAll('[data-support-fallback]').forEach((el) => { el.hidden = Boolean(supportEmail); });

const input = document.getElementById('help-search');
if (input) {
  const cards = [...document.querySelectorAll('#help-cards .help-card')];
  const empty = document.getElementById('help-empty');
  const normalize = (text) => text.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
  const haystacks = cards.map((card) => normalize(card.dataset.search || card.textContent));
  input.addEventListener('input', () => {
    const words = normalize(input.value).split(/\s+/).filter(Boolean);
    let shown = 0;
    cards.forEach((card, i) => {
      const match = words.every((w) => haystacks[i].includes(w));
      card.hidden = !match;
      if (match) shown++;
    });
    empty.hidden = shown > 0;
  });
}
