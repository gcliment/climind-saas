// @ts-check
// Fase 5: landing con demo en vivo y precios de la base, página de precios, ayuda, portal y demos públicas.
const { test, expect, expectNoHorizontalScroll } = require('./fixtures');

test('la landing calcula la demo en el servidor y muestra los precios vigentes', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Dejá de contestar «¿cuánto sale?»');

  const demo = page.locator('#demos');
  await expect(demo).toBeVisible();
  await expect(demo.getByRole('tab')).toHaveCount(3);
  await expect(demo.getByRole('tab').first()).toHaveAttribute('aria-selected', 'true');
  const total = page.locator('#demo-ticket .ticket-total output');
  // El ejemplo inicial coincide con la ilustración del hero: 60 m² × $1.500 + 3 ambientes + horno.
  await expect(total).toHaveText(/108\.000/);

  await page.getByLabel(/metros cuadrados/).fill('100');
  await expect(total).toHaveText(/168\.000/);

  await demo.getByRole('tab', { name: /Catering/ }).click();
  await expect(page.locator('#demo-name')).toHaveText('Presupuesto para tu evento');
  await expect(total).not.toHaveText('—');

  const pricing = page.locator('#pricing-grid');
  await expect(pricing.locator('.price-card')).toHaveCount(3);
  await expect(pricing.locator('[data-plan="GRATIS"]')).toContainText('Sin costo');
  await expect(pricing.locator('[data-plan="PRO"]')).toContainText(/9\.900/);
  await expect(pricing.locator('[data-plan="PRO"]')).toContainText('3 cotizadores publicados');
  // Cotizador a medida sin costo adicional: incluido en los planes pagos, no en el gratis.
  await expect(pricing.locator('[data-plan="PRO"] li', { hasText: 'Cotizador a medida' })).not.toHaveClass(/is-off/);
  await expect(pricing.locator('[data-plan="NEGOCIO"] li', { hasText: 'Cotizador a medida' })).not.toHaveClass(/is-off/);
  await expect(pricing.locator('[data-plan="GRATIS"] li', { hasText: 'Cotizador a medida' })).toHaveClass(/is-off/);
  await expect(page.locator('#a-medida')).toContainText('sin ningún costo adicional', { ignoreCase: true });
  await expectNoHorizontalScroll(page);
});

test('los botones «Escribinos» van al WhatsApp configurado, o al email si no hay WhatsApp', async ({ page }) => {
  await page.goto('/');
  const contacto = page.locator('#a-medida a[data-contact]');
  const href = await contacto.getAttribute('href');
  // El servidor de E2E puede tener WhatsApp (APP_SUPPORT_WHATSAPP), solo email o ninguno de los dos.
  const config = await page.evaluate(() => window.CLIMIND_CONFIG);
  if (config.supportWhatsapp) {
    expect(href).toMatch(new RegExp(`^https://wa\\.me/${config.supportWhatsapp}\\?text=`));
  } else if (config.supportEmail) {
    expect(href).toMatch(/^mailto:/);
  } else {
    await expect(contacto).toBeHidden();
  }
});

test('la demo completa no pide datos personales e invita a crear un cotizador', async ({ page }) => {
  await page.goto('/');
  await page.locator('#demo-open').click();
  await expect(page).toHaveURL(/\/c\/demo\/limpieza$/);
  await expect(page.locator('#demo-banner')).toBeVisible();
  await page.getByRole('radio', { name: 'Limpieza profunda' }).check();
  await page.getByLabel(/metros cuadrados/).fill('60');
  await page.getByRole('textbox', { name: '¿Cuántos ambientes hay que limpiar?' }).fill('3');
  await page.getByRole('radio', { name: 'Una sola vez' }).check();
  await page.getByRole('button', { name: 'Ver precio estimado' }).click();
  await expect(page.locator('#ticket .ticket-total output')).toHaveText(/99\.000/);
  await expect(page.locator('#demo-lead')).toBeVisible();
  await expect(page.locator('#lead-section')).toBeHidden();
  await expect(page.locator('#demo-lead').getByRole('link', { name: 'Crear mi cotizador gratis' })).toHaveAttribute('href', '/registro');
  await expectNoHorizontalScroll(page);
});

test('precios, ayuda y portal se pueden recorrer', async ({ page }) => {
  await page.goto('/precios');
  const compare = page.locator('#compare');
  await expect(compare.getByRole('columnheader')).toHaveText(['Gratis', 'Pro', 'Negocio']);
  await expect(compare.getByRole('row', { name: /Cotizaciones por mes/ })).toContainText('1.500');
  await expectNoHorizontalScroll(page);

  await page.goto('/ayuda');
  await page.getByLabel('Buscar en la ayuda').fill('código QR');
  const visible = page.locator('#help-cards .help-card:visible');
  await expect(visible).toHaveCount(1);
  await visible.click();
  await expect(page).toHaveURL(/\/ayuda\/publicar-y-compartir$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Publicar y compartir');
  await expect(page.locator('.help-nav [aria-current="page"]')).toHaveText('Publicar y compartir');
  await expectNoHorizontalScroll(page);

  await page.goto('/saas/');
  await expect(page.locator('#pricing-grid .price-card')).toHaveCount(3);
  await expectNoHorizontalScroll(page);
});
