// Píxel de Meta: mide las visitas y los registros que llegan desde los anuncios, para que Meta optimice
// por registros y no por clics. Solo se usa en la landing, precios y registro (nunca en el panel ni en
// los cotizadores públicos de los negocios) y solo si el servidor tiene APP_META_PIXEL_ID.
// Va como archivo y no como el fragmento inline de Meta para respetar la CSP (script-src sin 'unsafe-inline').

const pixelId = window.CLIMIND_CONFIG?.metaPixelId || '';
let ready = Promise.resolve(false);

function init() {
  if (!pixelId || window.fbq) return;
  // Cola equivalente al fragmento oficial: los eventos se guardan hasta que carga fbevents.js.
  // `arguments` (no rest params): fbevents.js espera ese objeto en la cola.
  const fbq = function fbq() {
    if (fbq.callMethod) fbq.callMethod.apply(fbq, arguments); else fbq.queue.push(arguments);
  };
  fbq.push = fbq;
  fbq.loaded = true;
  fbq.version = '2.0';
  fbq.queue = [];
  window.fbq = fbq;
  if (!window._fbq) window._fbq = fbq;

  ready = new Promise((resolve) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = 'https://connect.facebook.net/en_US/fbevents.js';
    script.addEventListener('load', () => resolve(true));
    script.addEventListener('error', () => resolve(false)); // bloqueadores de anuncios: la página sigue igual
    document.head.append(script);
  });

  window.fbq('init', pixelId);
  window.fbq('track', 'PageView');
}

init();

const wait = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * Registra el alta de una cuenta. Espera a que el evento salga (con un tope) para que la redirección
 * al panel no lo corte; si no hay píxel o está bloqueado, vuelve enseguida.
 */
export async function trackRegistration() {
  if (!pixelId || !window.fbq) return;
  const loaded = await Promise.race([ready, wait(1500).then(() => false)]);
  if (!loaded) return;
  window.fbq('track', 'CompleteRegistration');
  await wait(300);
}
