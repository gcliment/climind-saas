// @ts-check
const { test, expect, registerAndLogin, expectNoHorizontalScroll } = require('./fixtures');

test('el negocio contrata un plan, se activa solo con el pago confirmado y lo cancela', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Marta');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();

  // El inicio muestra el plan y el consumo.
  await expect(page.locator('#plan-block')).toContainText('Gratis');
  await expect(page.locator('#plan-quotes')).toHaveText('0 de 30');

  await page.goto('/app/plan');
  await expect(page.locator('#current-title')).toHaveText('Plan Gratis');
  await expect(page.locator('#simulator-banner')).toBeVisible();
  await expect(page.locator('#quotes-text')).toHaveText('0 de 30');
  await expectNoHorizontalScroll(page);

  // Contratar Pro: va al checkout (simulado) del proveedor.
  await page.getByRole('button', { name: 'Pasar a Pro' }).click();
  await expect(page).toHaveURL(/\/app\/plan\/simulador\?sub=fake-sub-/);
  await expect(page.locator('#sim-text')).toContainText('Contratación del plan Pro');

  // Volver sin pagar no activa nada.
  await page.goto('/app/plan?retorno=1');
  await expect(page.locator('#current-title')).toHaveText('Plan Gratis');
  await expect(page.locator('#pending-block')).toContainText('Empezaste a contratar el plan Pro');

  // Pago rechazado: sigue en Gratis y se informa.
  await page.getByRole('link', { name: 'Continuar con el pago' }).click();
  await page.getByRole('button', { name: 'Rechazar el pago' }).click();
  await expect(page.locator('#plan-notice')).toContainText('Mercado Pago rechazó el pago', { timeout: 15_000 });
  await expect(page.locator('#current-title')).toHaveText('Plan Gratis');

  // Pago aprobado (confirmado por la notificación firmada): plan Pro activo.
  await page.getByRole('link', { name: 'Continuar con el pago' }).click();
  await page.getByRole('button', { name: 'Aprobar el pago' }).click();
  await expect(page).toHaveURL(/\/app\/plan/);
  await expect(page.locator('#current-title')).toHaveText('Plan Pro');
  await expect(page.locator('#current-status')).toContainText('Activo. Se renueva el');
  await expect(page.locator('#calcs-text')).toHaveText('0 de 3');
  await expect(page.locator('#payments-body')).toContainText('Aprobado');
  await expect(page.locator('.plan-card.is-current')).toContainText('Tu plan actual');
  await expectNoHorizontalScroll(page);

  // Cancelar: conserva lo pagado hasta el fin del período.
  await page.getByRole('button', { name: 'Cancelar el plan' }).click();
  await page.locator('#cancel-dialog').getByRole('button', { name: 'Cancelar el plan' }).click();
  await expect(page.locator('#plan-notice')).toHaveText('Cancelaste el plan. Te mandamos un email con el detalle.');
  await expect(page.locator('#current-status')).toContainText('Cancelado. Conservás los beneficios hasta el');
});

test('el plan Gratis limita los cotizadores publicados y ofrece pasar de plan', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Nico');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  const second = await page.evaluate(async () => {
    const { api } = await import('/shared/js/api.js');
    const first = await api.post('/api/v1/calculators', { name: 'Hogares', templateCode: 'limpieza' });
    await api.post(`/api/v1/calculators/${first.id}/publish`);
    return (await api.post('/api/v1/calculators', { name: 'Oficinas', templateCode: 'limpieza' })).id;
  });
  await page.goto(`/app/editor?id=${second}`);
  await page.locator('#publish-button').click();
  const notice = page.locator('#editor-notice');
  await expect(notice).toContainText('Tu plan permite 1 cotizador publicado');
  await notice.getByRole('link', { name: 'Ver planes' }).click();
  await expect(page).toHaveURL(/\/app\/plan$/);
  await expect(page.locator('#calcs-text')).toHaveText('1 de 1');
});

test('el super admin ve métricas, negocios, planes y el estado de la integración', async ({ page }) => {
  await page.goto('/ingresar');
  await page.getByLabel('Email').fill('admin@cotiza.local');
  await page.getByLabel('Contraseña', { exact: true }).fill('Climind-dev-2026');
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/app\/admin/);
  await expect(page.locator('#revenue-stats')).toContainText('MRR estimado');
  await expect(page.locator('#revenue-stats')).toContainText('Cobrado en los últimos 30 días');

  await page.getByRole('tab', { name: 'Negocios' }).click();
  await expect(page.locator('#tenant-rows tr').first()).toBeVisible();
  await page.getByRole('tab', { name: 'Planes' }).click();
  await expect(page.locator('#plan-forms')).toContainText('Pro (PRO)');
  await page.getByRole('tab', { name: 'Integración' }).click();
  await expect(page.locator('#health-stats')).toContainText('Simulador (solo desarrollo)');
  await page.getByRole('button', { name: 'Conciliar ahora' }).click();
  await expect(page.locator('#reconcile-result')).toContainText('Revisadas');
  await expectNoHorizontalScroll(page);
});
