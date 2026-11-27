// @ts-check
// Tu cuenta: datos, contraseña, emails de producto, descarga de datos y baja (programar y cancelar).
const { test, expect, registerAndLogin, expectNoHorizontalScroll } = require('./fixtures');

test('el dueño administra su cuenta, descarga sus datos y programa y cancela la baja', async ({ page, request }) => {
  await registerAndLogin(page, request, 'Paula');
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();

  // En el celular, "Tu cuenta" está en el menú de la barra superior; en escritorio, en la barra lateral.
  // El menú lo arma el panel al terminar de cargar: esperar antes de ver si está visible.
  await page.locator('.panel:not([data-state])').waitFor();
  const menu = page.locator('.topbar-menu summary');
  if (await menu.isVisible()) {
    await menu.click();
    await page.locator('.topbar-menu-list').getByRole('link', { name: 'Tu cuenta' }).click();
  } else {
    await page.locator('#side-nav').getByRole('link', { name: 'Tu cuenta' }).click();
  }
  await expect(page).toHaveURL(/\/app\/cuenta$/);
  await expect(page.locator('#account-email')).toContainText('@e2e.climind.test');

  await page.getByLabel('Nombre', { exact: true }).fill('Paula Andrea');
  await page.locator('#profile-form').getByRole('button', { name: 'Guardar' }).click();
  await expect(page.locator('#profile-status')).toHaveText('Guardado');

  await page.getByLabel(/Consejos para sacarle provecho/).uncheck();
  await expect(page.locator('#emails-status')).toContainText('no vas a recibir');

  await page.getByLabel('Contraseña actual').fill('no-es-esta');
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('Otra-Clave-Segura-2026');
  await page.getByLabel('Repetí la contraseña nueva').fill('Otra-Clave-Segura-2026');
  await page.getByRole('button', { name: 'Cambiar la contraseña' }).click();
  await expect(page.locator('#currentPassword-error')).toHaveText('La contraseña no es correcta.');
  await page.getByLabel('Contraseña actual').fill('Cotizar-Facil-2026');
  await page.getByRole('button', { name: 'Cambiar la contraseña' }).click();
  await expect(page.locator('#password-status')).toContainText('cambiaste tu contraseña');

  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar mis datos' }).click();
  expect((await download).suggestedFilename()).toMatch(/^climind-cotiza-datos-\d{4}-\d{2}-\d{2}\.zip$/);
  await expectNoHorizontalScroll(page);

  // Baja: pide contraseña y la palabra ELIMINAR.
  await page.getByRole('button', { name: 'Dar de baja la cuenta' }).click();
  const dialog = page.locator('#delete-dialog');
  await dialog.getByLabel('Tu contraseña').fill('Otra-Clave-Segura-2026');
  await dialog.getByLabel('Escribí ELIMINAR para confirmar').fill('borrar');
  await dialog.getByRole('button', { name: 'Dar de baja' }).click();
  await expect(dialog.locator('#delete-confirmation-error')).toContainText('ELIMINAR');
  await dialog.getByLabel('Escribí ELIMINAR para confirmar').fill('ELIMINAR');
  await dialog.getByRole('button', { name: 'Dar de baja' }).click();
  await expect(page.locator('#account-notice')).toContainText('Programamos la baja');
  await expect(page.locator('#deletion-pending')).toBeVisible();

  // El resto del panel avisa que hay una baja en curso.
  await page.goto('/app/');
  await expect(page.locator('#deletion-banner')).toContainText('La baja de tu cuenta está programada');
  await page.locator('#deletion-banner').getByRole('link', { name: 'Cancelar la baja' }).click();
  await page.getByRole('button', { name: 'Cancelar la baja' }).click();
  await expect(page.locator('#account-notice')).toContainText('Cancelaste la baja');
  await expect(page.locator('#owner-delete')).toBeVisible();
  await page.goto('/app/');
  await expect(page.locator('#deletion-banner')).toHaveCount(0);
});
