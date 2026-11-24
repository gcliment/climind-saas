// @ts-check
const base = require('@playwright/test');

/**
 * Extiende `page` para que cualquier error de consola o de JavaScript (incluidas las violaciones de la
 * Content-Security-Policy) haga fallar el test.
 */
const test = base.test.extend({
  page: async ({ page }, use) => {
    const problems = [];
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return;
      const text = msg.text();
      // Las respuestas 4xx de la API son parte de los flujos probados (sin sesión, credenciales inválidas):
      // el navegador las registra igual en consola. Cualquier otro error sí falla.
      if (/Failed to load resource: the server responded with a status of 4\d\d/.test(text)) return;
      problems.push(`console: ${text}`);
    });
    page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
    await use(page);
    base.expect(problems, problems.join('\n')).toEqual([]);
  },
});

/** Email único por corrida para no chocar con datos previos de la base de desarrollo. */
function uniqueEmail(prefix) {
  return `${prefix}.${Date.now()}.${Math.floor(Math.random() * 1e4)}@e2e.climind.test`;
}

/** Último link recibido por email (bandeja de desarrollo /api/dev/emails). */
async function lastEmailLink(request, to, pathPart) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = await request.get(`/api/dev/emails?to=${encodeURIComponent(to)}`);
    const emails = await response.json();
    const link = emails.flatMap((e) => e.links).find((l) => l.includes(pathPart));
    if (link) return new URL(link).pathname + new URL(link).search;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`No llegó un email con ${pathPart} a ${to}`);
}

const PASSWORD = 'Cotizar-Facil-2026';

/**
 * Alta completa por la interfaz: registro (entra directo al asistente) y confirmación del email con el
 * link recibido. Deja la sesión iniciada en la guía de configuración, como un primer ingreso.
 */
async function registerAndLogin(page, request, firstName) {
  const email = uniqueEmail(firstName.toLowerCase());
  await page.goto('/registro');
  await page.getByLabel('Tu nombre').fill(firstName);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Crear mi cuenta gratis' }).click();
  await base.expect(page).toHaveURL(/\/app\/onboarding$/);
  await page.goto(await lastEmailLink(request, email, '/verificar'));
  await base.expect(page.getByRole('heading', { name: 'Email confirmado' })).toBeVisible();
  await page.goto('/app/onboarding');
  await base.expect(page).toHaveURL(/\/app\/onboarding$/);
  return email;
}

/** En pantallas angostas el constructor alterna entre editar y ver: muestra la vista previa si hace falta. */
async function showPreview(page) {
  const toggle = page.getByRole('button', { name: 'Vista previa', exact: true });
  if (await toggle.isVisible()) await toggle.click();
}

async function showEditor(page) {
  const toggle = page.getByRole('button', { name: 'Editar', exact: true });
  if (await toggle.isVisible()) await toggle.click();
}

/** Nada debe desbordar el ancho de la pantalla (sin scroll horizontal), sobre todo en el celular. */
async function expectNoHorizontalScroll(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  base.expect(overflow, 'la página no debe tener scroll horizontal').toBeLessThanOrEqual(1);
}

module.exports = {
  test, expect: base.expect, uniqueEmail, lastEmailLink, registerAndLogin, showPreview, showEditor, expectNoHorizontalScroll,
};
