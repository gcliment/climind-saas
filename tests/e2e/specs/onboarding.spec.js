// @ts-check
const { test, expect, uniqueEmail, lastEmailLink } = require('./fixtures');

const PASSWORD = 'Cotizar-Facil-2026';

test('registro → verificación → ingreso → datos del negocio → salir', async ({ page, request }) => {
  const email = uniqueEmail('ana');

  // Registro
  await page.goto('/registro');
  await page.getByLabel('Nombre', { exact: true }).fill('Ana');
  await page.getByLabel('Apellido').fill('Prueba');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Repetí la contraseña').fill(PASSWORD);
  await page.getByLabel(/Acepto los/).check();
  await page.getByRole('button', { name: 'Crear cuenta' }).click();
  await expect(page.getByRole('heading', { name: 'Revisá tu email' })).toBeVisible();
  await expect(page.locator('#sent-to')).toHaveText(email);

  // Ingresar sin confirmar: avisa y ofrece reenviar.
  await page.goto('/ingresar');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page.getByRole('alert')).toContainText('Todavía no confirmaste tu email');
  await expect(page.getByRole('button', { name: 'Reenviar email de confirmación' })).toBeVisible();

  // Verificación con el link del email (el token se quita de la barra de direcciones).
  const verifyLink = await lastEmailLink(request, email, '/verificar');
  await page.goto(verifyLink);
  await expect(page.getByRole('heading', { name: 'Email confirmado' })).toBeVisible();
  await expect(page).toHaveURL(/\/verificar$/);

  // Ingreso
  await page.getByRole('link', { name: 'Ingresar' }).click();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  // Primer ingreso: la guía de configuración; se puede saltear.
  await expect(page).toHaveURL(/\/app\/onboarding$/);
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  await expect(page).toHaveURL(/\/app\/$/);
  await expect(page.getByRole('heading', { name: 'Hola, Ana' })).toBeVisible();

  // Datos del negocio
  await page.getByRole('link', { name: 'Completar datos' }).click();
  await expect(page).toHaveURL(/\/app\/negocio$/);
  await expect(page.getByLabel('Rubro')).toContainText('Limpieza');
  await page.getByLabel('Nombre comercial').fill('Limpiezas Ana');
  await page.getByLabel('Rubro').selectOption('LIMPIEZA');
  await page.getByLabel('WhatsApp').fill('+54 9 11 2345-6789');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText('Cambios guardados')).toBeVisible();
  await expect(page.getByLabel('WhatsApp')).toHaveValue('+5491123456789');
  await expect(page.locator('#public-link')).toContainText('/c/limpiezas-ana-');

  // El paso 1 queda completo en el inicio.
  await page.goto('/app/');
  await expect(page.locator('#step-profile')).toHaveClass(/done/);

  // Salir: la sesión deja de valer. En el celular "Cerrar sesión" está en el menú de la barra superior.
  const menu = page.locator('.topbar-menu summary');
  if (await menu.isVisible()) await menu.click();
  await page.locator('[data-logout]:visible').first().click();
  await expect(page).toHaveURL(/\/ingresar$/);
  await page.goto('/app/negocio');
  await expect(page).toHaveURL(/\/ingresar\?next=%2Fapp%2Fnegocio$/);
});

test('validación accesible en el registro y credenciales incorrectas', async ({ page }) => {
  await page.goto('/registro');
  await page.getByRole('button', { name: 'Crear cuenta' }).click();
  const firstName = page.getByLabel('Nombre', { exact: true });
  await expect(firstName).toHaveAttribute('aria-invalid', 'true');
  await expect(firstName).toBeFocused();
  await expect(page.getByText('Ingresá tu nombre.')).toBeVisible();

  await page.goto('/ingresar');
  await page.getByLabel('Email').fill(uniqueEmail('nadie'));
  await page.getByLabel('Contraseña', { exact: true }).fill('una-clave-cualquiera');
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page.getByRole('alert')).toHaveText('El email o la contraseña no son correctos.');
});
