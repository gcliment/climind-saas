// @ts-check
const { test, expect, registerAndLogin, expectNoHorizontalScroll } = require('./fixtures');

/** Crea y publica un cotizador de limpieza por la API (con la sesión y el CSRF del navegador). */
async function publishCleaningCalculator(page) {
  return page.evaluate(async () => {
    const { api } = await import('/shared/js/api.js');
    await api.put('/api/v1/business/profile', {
      businessName: 'Limpiezas Sol', industry: 'LIMPIEZA', whatsapp: '+54 9 11 5555-0000',
    });
    const calc = await api.post('/api/v1/calculators', { name: 'Limpieza de hogares', templateCode: 'limpieza' });
    const published = await api.post(`/api/v1/calculators/${calc.id}/publish`);
    return new URL(published.calculator.publicUrl).pathname;
  });
}

test('el cliente cotiza desde el enlace, deja sus datos y el negocio gestiona la consulta', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Sol');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  const path = await publishCleaningCalculator(page);

  // ---------------------------------------------------------------- cliente
  await page.goto(path);
  await expect(page).toHaveTitle('Limpieza de hogares | Limpiezas Sol');
  await expect(page.getByRole('heading', { level: 1, name: 'Limpieza de hogares' })).toBeVisible();
  await expect(page.locator('#biz-name')).toHaveText('Limpiezas Sol');
  await expect(page.locator('#contact')).toContainText('+54 9 11 5555-0000');

  // Sin responder: el servidor marca lo que falta y no guarda nada.
  await page.getByRole('button', { name: 'Ver precio estimado' }).click();
  await expect(page.locator('#form-notice')).toHaveText('Revisá las respuestas marcadas.');
  await expect(page.locator('.qf-error').first()).toHaveText('Respondé esta pregunta.');

  const form = page.locator('#questions');
  await form.getByRole('radio', { name: 'Limpieza profunda' }).check();
  await form.getByRole('textbox', { name: /metros cuadrados/ }).fill('60');
  await form.getByRole('textbox', { name: /ambientes/ }).fill('3');
  await form.getByRole('radio', { name: 'Una sola vez' }).check();
  await page.getByRole('button', { name: 'Ver precio estimado' }).click();

  const ticket = page.locator('#ticket');
  await expect(ticket.locator('.ticket-total output')).toHaveText('$ 99.000');
  await expect(ticket.locator('.ticket-stamp')).toContainText(/COT-[A-Z0-9]{6}/);
  await expect(ticket).toContainText('Precio válido hasta el');
  const whatsapp = page.getByRole('link', { name: 'Consultar por WhatsApp' });
  await expect(whatsapp).toHaveAttribute('href', /^https:\/\/wa\.me\/5491155550000\?text=.*99\.000/);
  await expectNoHorizontalScroll(page);

  // Cambiar una respuesta invalida el precio mostrado hasta recalcular.
  await form.getByRole('textbox', { name: /metros cuadrados/ }).fill('80');
  await expect(page.locator('#stale')).toBeVisible();
  await page.getByRole('button', { name: 'Recalcular precio' }).click();
  await expect(ticket.locator('.ticket-total output')).toHaveText('$ 129.000');
  await expect(page.locator('#stale')).toBeHidden();

  // PDF de la cotización.
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar PDF' }).click();
  expect((await download).suggestedFilename()).toMatch(/^cotizacion-COT-[A-Z0-9]{6}\.pdf$/);

  // Dejar los datos.
  const lead = page.locator('#lead-section');
  await lead.getByLabel('Nombre').fill('Carla Gómez');
  await lead.getByLabel('WhatsApp').fill('11 2345-6789');
  await lead.getByLabel('Comentarios').fill('Es un PH con patio');
  await lead.getByRole('button', { name: 'Enviar mis datos' }).click();
  await expect(lead.getByText('Para enviar la consulta tenés que aceptar')).toBeVisible();
  await lead.getByLabel(/Acepto que Limpiezas Sol use estos datos/).check();
  await lead.getByRole('button', { name: 'Enviar mis datos' }).click();
  await expect(page.getByRole('heading', { name: '¡Listo! Recibimos tu consulta' })).toBeVisible();

  // El enlace guardado muestra la misma cotización (el token viaja en el fragmento, no en la URL del servidor).
  const permalink = await page.locator('#permalink').getAttribute('href');
  expect(permalink).toMatch(/\/cotizacion\/COT-[A-Z0-9]{6}#t=[A-Za-z0-9_-]{43}$/);
  await page.goto(/** @type {string} */ (permalink));
  await expect(page.locator('#ticket .ticket-total output')).toHaveText('$ 129.000');
  await expect(page.getByRole('heading', { name: 'Ya enviaste tus datos' })).toBeVisible();
  await expectNoHorizontalScroll(page);

  // ---------------------------------------------------------------- negocio
  await page.goto('/app/consultas');
  await expect(page.getByRole('button', { name: /Nuevas\s*1/ })).toBeVisible();
  await page.getByRole('button', { name: 'Carla Gómez' }).click();
  const dialog = page.locator('#lead-dialog');
  await expect(dialog.getByRole('heading', { name: 'Carla Gómez' })).toBeVisible();
  await expect(dialog.getByRole('link', { name: 'Escribir por WhatsApp (+54 9 11 2345-6789)' })).toBeVisible();
  await expect(dialog.locator('.ticket-total output')).toHaveText('$ 129.000');
  await expect(dialog).toContainText('Es un PH con patio');
  await dialog.getByLabel('Estado').selectOption('CONTACTADA');
  await dialog.getByLabel('Nota', { exact: true }).fill('Le escribí por WhatsApp');
  await dialog.getByRole('button', { name: 'Guardar' }).click();
  await expect(dialog.locator('#lead-notice')).toHaveText('Guardamos el seguimiento.');
  await expect(dialog.locator('.lead-timeline')).toContainText('Le escribí por WhatsApp');
  await expectNoHorizontalScroll(page);
  await dialog.getByRole('button', { name: 'Cerrar' }).click();
  await expect(page.getByRole('button', { name: /Contactadas\s*1/ })).toBeVisible();

  // Estadísticas: dos cotizaciones (recalcular con otras respuestas es otra), una consulta.
  await page.goto('/app/estadisticas');
  await expect(page.locator('#k-sims')).toContainText('2');
  await expect(page.locator('#k-leads')).toContainText('1');
  await expect(page.locator('#k-rate')).toContainText('50 %');
  await expect(page.locator('#activity-chart svg')).toBeVisible();
  await expectNoHorizontalScroll(page);

  // Inicio: actividad del mes.
  await page.goto('/app/');
  await expect(page.locator('#month-block')).toBeVisible();
  await expect(page.locator('#m-leads')).toHaveText('1');
});

test('un cotizador pausado avisa y muestra el contacto del negocio', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Paula');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  const path = await publishCleaningCalculator(page);
  await page.evaluate(async () => {
    const { api } = await import('/shared/js/api.js');
    const [calc] = await api.get('/api/v1/calculators');
    await api.post(`/api/v1/calculators/${calc.id}/pause`);
  });
  await page.goto(path);
  await expect(page.getByRole('heading', { name: 'Este cotizador no está disponible por el momento' })).toBeVisible();
  await expect(page.locator('#contact')).toContainText('WhatsApp');
  await expect(page.locator('#quote-form')).toBeHidden();
});
