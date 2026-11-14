// @ts-check
const { test, expect, registerAndLogin, showPreview, showEditor, expectNoHorizontalScroll } = require('./fixtures');

/** El ticket de la vista previa muestra el total calculado por el servidor. */
async function expectTotal(page, text) {
  await expect(page.locator('#preview-ticket .ticket-total output')).toHaveText(text);
}

test('guía de configuración: plantilla de limpieza, precios propios y publicación', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Lucia');
  await expect(page).toHaveURL(/\/app\/onboarding$/);

  // 1. Negocio
  await page.getByLabel('Nombre comercial').fill('Limpiezas Lucía');
  await page.getByLabel('Rubro').selectOption('LIMPIEZA');
  await page.getByLabel('WhatsApp').fill('+54 9 11 5555-0000');
  await page.getByRole('button', { name: 'Continuar' }).click();

  // 2. Objetivo
  await expect(page.getByRole('heading', { name: '¿Qué querés cotizar?' })).toBeVisible();
  await page.getByRole('button', { name: 'Continuar' }).click();

  // 3. Plantilla: la de su rubro aparece primera y recomendada.
  await expect(page.getByRole('heading', { name: 'Elegí cómo empezar' })).toBeVisible();
  const first = page.locator('#template-list .choice-card').first();
  await expect(first).toContainText('Servicios de limpieza');
  await expect(first).toContainText('Recomendada para tu rubro');
  await page.getByRole('button', { name: 'Continuar' }).click();

  // 4. Precios: cambia el m² de limpieza profunda de 1.500 a 1.800.
  await expect(page.getByRole('heading', { name: 'Cargá tus precios' })).toBeVisible();
  const deep = page.getByLabel('Limpieza profunda', { exact: true });
  await expect(deep).toHaveValue('1.500');
  await deep.fill('1.800');
  await page.getByRole('button', { name: 'Guardar y continuar' }).click();

  // 5. Publicar
  await page.getByRole('button', { name: 'Publicar mi cotizador' }).click();
  await expect(page.getByRole('heading', { name: 'Tu cotizador ya está listo para compartir' })).toBeVisible();
  await expect(page.locator('#done-url')).toContainText('/c/limpiezas-lucia-');
  await expectNoHorizontalScroll(page);

  // Ver cotizador: vista previa con el precio nuevo (60 m² × $1.800 + 3 ambientes × $3.000 = $117.000).
  await page.getByRole('link', { name: 'Ver en el editor' }).click();
  await expect(page.locator('#calc-status')).toHaveText('Publicado');
  await showPreview(page);
  const form = page.locator('#preview-form');
  await form.getByRole('radio', { name: 'Limpieza profunda' }).check();
  await form.getByRole('textbox', { name: /metros cuadrados/ }).fill('60');
  await form.getByRole('textbox', { name: /ambientes/ }).fill('3');
  await form.getByRole('radio', { name: 'Una sola vez' }).check();
  await expectTotal(page, '$ 117.000');
  await expect(page.locator('#preview-ticket')).toContainText('60 m² × $ 1.800');
});

test('constructor desde cero: pregunta, precio por unidad, vista previa en vivo, guardar y publicar', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Mateo');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();

  await page.goto('/app/cotizadores');
  await expect(page.getByRole('heading', { name: 'Todavía no tenés cotizadores' })).toBeVisible();
  await page.getByRole('button', { name: 'Nuevo cotizador' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('radio', { name: /Empezar de cero/ }).check();
  await dialog.getByLabel('Nombre del cotizador').fill('Fundas a medida');
  await dialog.getByRole('button', { name: 'Crear y editar' }).click();
  await expect(page).toHaveURL(/\/app\/editor\?id=.+&tab=preguntas/);
  await expect(page.locator('#calc-status')).toHaveText('Borrador');

  // Pregunta de cantidad
  await page.getByRole('button', { name: /^Cantidad/ }).click();
  await page.getByLabel('Pregunta', { exact: true }).fill('¿Cuántas fundas necesitás?');
  await expect(page.locator('#save-state')).toHaveText('Cambios sin guardar');

  // Precio por unidad (la pantalla no se desborda en ningún tamaño)
  await expectNoHorizontalScroll(page);
  await page.getByRole('tab', { name: 'Precios' }).click();
  await page.getByRole('button', { name: /^Precio por unidad Cantidad/ }).click();
  await page.getByLabel('Cantidad o medida').selectOption({ label: '¿Cuántas fundas necesitás?' });
  await page.getByLabel('Precio por unidad', { exact: true }).fill('2.500');
  await expectNoHorizontalScroll(page);

  // Vista previa en vivo (calculada por el servidor con la configuración sin guardar).
  await showPreview(page);
  await page.locator('#preview-form').getByRole('textbox', { name: '¿Cuántas fundas necesitás?' }).fill('4');
  await expectTotal(page, '$ 10.000');

  // Guardar y publicar
  await showEditor(page);
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.locator('#save-state')).toHaveText('Guardado');
  await page.getByRole('button', { name: 'Publicar', exact: true }).click();
  await expect(page.locator('#editor-notice')).toContainText('Publicaste la versión 1');
  await expect(page.locator('#calc-status')).toHaveText('Publicado');

  // El listado lo muestra publicado.
  await page.goto('/app/cotizadores');
  await expect(page.locator('.calc-row')).toContainText('Fundas a medida');
  await expect(page.locator('.calc-row .status-badge')).toHaveText('Publicado');
});

test('no se puede publicar un cotizador incompleto: el editor dice qué falta', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Sofia');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  await page.goto('/app/cotizadores?nuevo=1');
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('radio', { name: /Empezar de cero/ }).check();
  await dialog.getByLabel('Nombre del cotizador').fill('Vacío');
  await dialog.getByRole('button', { name: 'Crear y editar' }).click();

  await page.getByRole('button', { name: 'Publicar', exact: true }).click();
  await expect(page.locator('#editor-notice')).toContainText('Antes de publicar, completá lo que falta.');
  await expect(page.getByRole('tab', { name: 'Publicación' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.checklist')).toContainText('Agregá al menos una pregunta.');
});
