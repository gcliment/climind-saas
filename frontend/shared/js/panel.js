// Estructura común del panel: control de sesión, navegación y cierre de sesión.
import { api, currentUser } from './api.js';

const ICONS = {
  home: '<path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"/>',
  store: '<path d="M4 9h16l-1.2-4.2A1 1 0 0 0 17.8 4H6.2a1 1 0 0 0-1 .8z"/><path d="M5 9v11h14V9M9 20v-6h6v6"/>',
  calc: '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M8 7h8M8 11h2M12 11h2M16 11h0M8 15h2M12 15h2M8 18h6"/>',
  inbox: '<path d="M4 13V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7"/><path d="M4 13h4l1.5 3h5L16 13h4v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"/>',
  chart: '<path d="M4 20V4M4 20h16"/><path d="M8 16v-5M12 16V8M16 16v-3"/>',
  card: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 10h18M7 15h4"/>',
  shield: '<path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6z"/>',
};

function icon(name) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
}

function navItems(me) {
  const items = [];
  if (me.tenant) {
    items.push({ href: '/app/', label: 'Inicio', icon: 'home' });
    items.push({ href: '/app/cotizadores', label: 'Cotizadores', icon: 'calc' });
    items.push({ href: '/app/consultas', label: 'Consultas', icon: 'inbox' });
    items.push({ href: '/app/estadisticas', label: 'Estadísticas', icon: 'chart' });
    items.push({ href: '/app/negocio', label: 'Mi negocio', icon: 'store' });
    // En el celular la barra inferior tiene 5 accesos: el plan se abre desde el inicio.
    items.push({ href: '/app/plan', label: 'Plan', icon: 'card', desktopOnly: true });
  }
  if (me.user.platformRole === 'SUPER_ADMIN') {
    items.push({ href: '/app/admin', label: 'Administración', icon: 'shield' });
  }
  return items;
}

function renderNav(container, items, current) {
  if (!container) return;
  container.replaceChildren(...items.map((item) => {
    const a = document.createElement('a');
    a.href = item.href;
    a.innerHTML = `${icon(item.icon)}<span></span>`;
    a.querySelector('span').textContent = item.label;
    if (item.href === current) a.setAttribute('aria-current', 'page');
    return a;
  }));
}

async function logout() {
  try {
    await api.post('/api/v1/auth/logout');
  } finally {
    window.location.assign('/ingresar');
  }
}

/**
 * Inicializa el panel. Redirige a /ingresar si no hay sesión.
 * @param {{current: string, requireTenant?: boolean}} options
 * @returns {Promise<object>} respuesta de /api/v1/auth/me
 */
export async function initPanel({ current, requireTenant = true }) {
  const me = await currentUser();
  if (!me) {
    window.location.replace(`/ingresar?next=${encodeURIComponent(current)}`);
    return new Promise(() => {}); // la página se abandona
  }
  if (requireTenant && !me.tenant) {
    window.location.replace('/app/admin');
    return new Promise(() => {});
  }

  const items = navItems(me);
  renderNav(document.getElementById('side-nav'), items, current);
  renderNav(document.getElementById('tabbar'), items.filter((i) => !i.desktopOnly), current);

  const fullName = `${me.user.firstName} ${me.user.lastName}`;
  document.querySelectorAll('[data-user-name]').forEach((el) => { el.textContent = fullName; });
  document.querySelectorAll('[data-business-name]').forEach((el) => {
    el.textContent = me.tenant ? me.tenant.name : 'Climind SaaS';
  });
  document.querySelectorAll('[data-logout]').forEach((button) => button.addEventListener('click', logout));

  document.querySelector('.panel')?.removeAttribute('data-state');
  return me;
}
