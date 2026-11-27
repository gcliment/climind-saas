// Extras del panel: app instalable (service worker, botón "Instalar la app" y la guía de instalación) y cambio
// de modo claro/oscuro. El tema lo aplica theme.js (script clásico del <head>), que también guarda la invitación
// a instalar del navegador (puede llegar antes de que carguen los módulos).
// La instalación sigue el mismo criterio que Transparencia Financiera: si el navegador ofrece instalarla, un
// botón; en iPhone/iPad (no hay botón posible), los pasos según el navegador; si no, cómo hacerlo desde el menú.

const INSTALL_GUIDE = '/app/cuenta#instalar';

export function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || (window.location.protocol !== 'https:' && window.location.hostname !== 'localhost')) return;
  navigator.serviceWorker.register('/sw.js').catch(() => { /* sin app instalable: el panel funciona igual */ });
}

// ------------------------------------------------------------------ estado de la instalación

let installedNow = false;
window.addEventListener('appinstalled', () => {
  installedNow = true;
  window.climindInstallPrompt = null;
  document.dispatchEvent(new CustomEvent('climind:installable'));
});

/** iPhone, iPod o iPad (el iPad se presenta como Mac, pero tiene pantalla táctil). */
export function isIosDevice(userAgent = navigator.userAgent, platform = navigator.platform, maxTouchPoints = navigator.maxTouchPoints) {
  return /iphone|ipad|ipod/i.test(userAgent) || (platform === 'MacIntel' && maxTouchPoints > 1);
}

/** Navegador en iPhone/iPad por el user agent; si no se reconoce, Safari (el de fábrica). */
export function detectIosBrowser(userAgent = navigator.userAgent) {
  if (/CriOS/.test(userAgent)) return 'chrome';
  if (/EdgiOS|FxiOS|OPiOS|OPT\/|YaBrowser|DuckDuckGo/i.test(userAgent)) return 'other';
  return 'safari';
}

export function installState() {
  const standalone = installedNow
    || window.matchMedia?.('(display-mode: standalone)').matches === true
    || window.navigator.standalone === true;
  return {
    /** Ya se está usando como app instalada (pantalla completa, sin barra del navegador). */
    standalone,
    /** El navegador ofrece instalarla con un botón (Chrome, Edge, Android). */
    canPrompt: Boolean(window.climindInstallPrompt) && !standalone,
    /** iPhone/iPad: se agrega desde «Compartir»; no hay botón programable. */
    ios: isIosDevice(),
  };
}

/** Abre el diálogo de instalación del navegador. Devuelve true si la persona aceptó. */
export async function promptInstall() {
  const event = window.climindInstallPrompt;
  if (!event) return false;
  window.climindInstallPrompt = null;
  await event.prompt();
  let accepted = false;
  try { accepted = (await event.userChoice).outcome === 'accepted'; } catch { /* cerró la invitación */ }
  document.dispatchEvent(new CustomEvent('climind:installable'));
  return accepted;
}

// ------------------------------------------------------------------ íconos (trazos simples, 24×24)

const ICONS = {
  share: '<path d="M12 3v12"/><path d="m8 7 4-4 4 4"/><path d="M8 10H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2h-2"/>',
  ellipsis: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  plus: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M8 12h8M12 8v8"/>',
  toggle: '<rect x="2" y="6" width="20" height="12" rx="6"/><circle cx="16" cy="12" r="3"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  download: '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/>',
};

function icon(name, label) {
  const span = document.createElement('span');
  span.className = 'install-icon';
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${label ? `role="img" aria-label="${label}"` : 'aria-hidden="true"'}>${ICONS[name]}</svg>`;
  return span;
}

/** Texto con fragmentos en negrita e íconos en línea: ['Tocá ', {b: 'Compartir'}, {icon: 'share', label: '…'}]. */
function rich(parts) {
  const p = document.createElement('p');
  for (const part of parts) {
    if (typeof part === 'string') p.append(part);
    else if (part.b) { const strong = document.createElement('strong'); strong.textContent = part.b; p.append(strong); }
    else if (part.icon) { const i = icon(part.icon, part.label); i.classList.add('is-inline'); p.append(i); }
  }
  return p;
}

// ------------------------------------------------------------------ guía de instalación

const IOS_BROWSERS = [['safari', 'Safari'], ['chrome', 'Chrome'], ['other', 'Otro']];

/** Pasos en iPhone/iPad según el navegador: se elige solo, pero se puede cambiar. */
function iosSteps() {
  const wrap = document.createElement('div');
  wrap.className = 'install-ios';
  const question = document.createElement('p');
  question.id = 'install-browser-label';
  question.className = 'install-question';
  question.textContent = '¿Con qué navegador estás?';
  const group = document.createElement('div');
  group.className = 'install-browsers';
  group.setAttribute('role', 'radiogroup');
  group.setAttribute('aria-labelledby', 'install-browser-label');
  const list = document.createElement('ol');
  list.className = 'install-steps';

  const firstStep = {
    safari: ['Tocá ', { b: 'Compartir' }, ' (el cuadrado con una flecha hacia arriba ', { icon: 'share', label: 'Compartir' },
      '). Está en la barra de abajo o, en los iPhone más nuevos, dentro del botón ', { icon: 'ellipsis', label: 'Más opciones' }, ' que está al lado de la dirección.'],
    chrome: ['Tocá ', { b: 'Compartir' }, ' ', { icon: 'share', label: 'Compartir' }, ', arriba a la derecha al lado de la dirección. Si no lo ves, tocá ',
      { icon: 'ellipsis', label: 'Más opciones' }, ' y después ', { b: 'Compartir' }, '.'],
    other: ['Abrí el menú del navegador (', { icon: 'ellipsis', label: 'tres puntos' }, ' o ', { icon: 'menu', label: 'tres rayas' }, ') y tocá ', { b: 'Compartir' }, '.'],
  };
  const render = (choice) => {
    group.querySelectorAll('button').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.value === choice)));
    const steps = [
      ['share', firstStep[choice]],
      ['plus', ['Bajá en la lista y tocá ', { b: 'Agregar a pantalla de inicio' }, '.']],
      ['toggle', ['Si aparece ', { b: 'Abrir como app web' }, ', dejalo activado. Tocá ', { b: 'Agregar' }, '.']],
      ['check', ['Listo: vas a ver el ícono de ', { b: 'Cotiza' }, ' en la pantalla de inicio. Abrila desde ahí.']],
    ];
    list.replaceChildren(...steps.map(([name, parts], i) => {
      const li = document.createElement('li');
      const sr = document.createElement('span');
      sr.className = 'visually-hidden';
      sr.textContent = `Paso ${i + 1}: `;
      const text = rich(parts);
      text.prepend(sr);
      li.append(icon(choice === 'other' && i === 0 ? 'menu' : name), text);
      return li;
    }));
  };
  for (const [value, label] of IOS_BROWSERS) {
    const b = document.createElement('button');
    b.type = 'button';
    b.setAttribute('role', 'radio');
    b.dataset.value = value;
    b.textContent = label;
    b.addEventListener('click', () => render(value));
    group.append(b);
  }
  const note = document.createElement('p');
  note.className = 'install-note';
  note.textContent = '¿No aparece «Agregar a pantalla de inicio»? Probá desde Safari, que siempre lo tiene. En otros navegadores hace falta iOS 16.4 o posterior.';
  render(detectIosBrowser());
  wrap.append(question, group, list, note);
  return wrap;
}

/** Contenido de la tarjeta «Instalar la app» (Tu cuenta). Se vuelve a dibujar si cambia el estado. */
export function renderInstallGuide(container) {
  const draw = () => {
    const state = installState();
    if (state.standalone) {
      const ok = document.createElement('p');
      ok.className = 'install-done';
      ok.append(icon('check'), 'Ya la estás usando como app instalada.');
      container.replaceChildren(ok);
    } else if (state.canPrompt) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-primary install-now';
      button.append(icon('download'), 'Instalar en este dispositivo');
      const status = document.createElement('p');
      status.className = 'form-text';
      status.setAttribute('role', 'status');
      button.addEventListener('click', async () => {
        if (await promptInstall()) status.textContent = 'Listo: la app quedó instalada. Buscá el ícono de Cotiza.';
      });
      container.replaceChildren(button, status);
    } else if (state.ios) {
      container.replaceChildren(iosSteps());
    } else {
      container.replaceChildren(rich(['Abrí el menú del navegador (', { icon: 'ellipsis', label: 'tres puntos' }, ' o ',
        { icon: 'menu', label: 'tres rayas' }, ') y elegí ', { b: 'Instalar aplicación' }, ' o ', { b: 'Agregar a la pantalla principal' },
        '. En la computadora también podés usar el ícono de instalar que aparece a la derecha de la dirección.']));
    }
  };
  draw();
  document.addEventListener('climind:installable', draw);
}

/**
 * Botón «Instalar la app» de los menús: abre la instalación del navegador si la ofrece; si no (iPhone y otros),
 * lleva a la guía paso a paso de Tu cuenta. No aparece si ya se usa como app.
 */
export function installButton(className) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.textContent = 'Instalar la app';
  const update = () => { button.hidden = installState().standalone; };
  update();
  document.addEventListener('climind:installable', update);
  button.addEventListener('click', async () => {
    if (installState().canPrompt) {
      await promptInstall();
      return;
    }
    if (window.location.pathname === '/app/cuenta') {
      document.getElementById('instalar')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      document.querySelector('.topbar-menu')?.removeAttribute('open');
    } else {
      window.location.assign(INSTALL_GUIDE);
    }
  });
  return button;
}

// ------------------------------------------------------------------ modo claro / oscuro

/** Tema efectivo ahora (lo que se ve), para ofrecer el contrario. */
const currentTheme = () => (document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light');

/** Botón que alterna claro/oscuro. Cambia de texto según el modo actual. */
export function themeToggleButton(className) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.dataset.themeToggle = '';
  const label = () => { button.textContent = currentTheme() === 'dark' ? 'Modo claro' : 'Modo oscuro'; };
  label();
  button.addEventListener('click', () => {
    window.climindTheme?.set(currentTheme() === 'dark' ? 'light' : 'dark');
    document.dispatchEvent(new CustomEvent('climind:themechange'));
  });
  document.addEventListener('climind:themechange', label);
  return button;
}
