// Modo claro / oscuro del panel. Script clásico (no módulo) cargado en el <head> para aplicar el tema antes de
// pintar la página y evitar el destello claro. La preferencia se guarda en este dispositivo.
(function () {
  var KEY = 'climind-theme';
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return value === 'light' || value === 'dark' ? value : 'auto';
    } catch (e) {
      return 'auto';
    }
  }

  function apply(mode) {
    var dark = mode === 'dark' || (mode === 'auto' && media && media.matches);
    var root = document.documentElement;
    root.setAttribute('data-theme', dark ? 'dark' : 'light');
    root.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#0B1520' : '#08131F');
  }

  apply(stored());
  if (media && media.addEventListener) {
    media.addEventListener('change', function () {
      if (stored() === 'auto') apply('auto');
    });
  }

  // La invitación a instalar la app puede llegar antes de que carguen los módulos: se guarda acá.
  window.addEventListener('beforeinstallprompt', function (event) {
    event.preventDefault();
    window.climindInstallPrompt = event;
    document.dispatchEvent(new CustomEvent('climind:installable'));
  });

  window.climindTheme = {
    /** 'auto' (sigue al sistema), 'light' o 'dark'. */
    get: stored,
    set: function (mode) {
      try {
        if (mode === 'light' || mode === 'dark') window.localStorage.setItem(KEY, mode);
        else window.localStorage.removeItem(KEY);
      } catch (e) { /* sin almacenamiento: vale solo para esta página */ }
      apply(mode);
    },
  };
}());
