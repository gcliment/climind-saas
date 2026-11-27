// Service worker de la app instalable. Deliberadamente mínimo: todo va a la red (los datos y los precios
// siempre son los del servidor) y solo se guarda una página "Sin conexión" para cuando no hay internet.
const CACHE = 'cotiza-offline-v1';
const OFFLINE = '/offline.html';
const OFFLINE_ASSETS = [
  OFFLINE,
  '/vendor/bootstrap-5.3.8/bootstrap.min.css',
  '/shared/css/climind.css',
  '/shared/css/legal.css',
  '/shared/js/theme.js',
  '/shared/img/cotiza-mark.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(OFFLINE_ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
    return;
  }
  // Recursos de la página sin conexión: si la red falla, se sirven desde la caché.
  if (OFFLINE_ASSETS.includes(new URL(request.url).pathname)) {
    event.respondWith(fetch(request).catch(() => caches.match(request)));
  }
});
