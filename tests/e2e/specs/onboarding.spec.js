// @ts-check
const { test, expect, uniqueEmail, lastEmailLink } = require('./fixtures');

const PASSWORD = 'Cotizar-Facil-2026';

test('registro (entra directo) → verificación → datos del negocio → salir → ingreso', async ({ page, request }) => {
  const email = uniqueEmail('ana');

  // Registro: solo nombre, email y contraseña; entra directo a la guía de configuración.
  await page.goto('/registro');
  await expect(page.getByLabel('Apellido')).toHaveCount(0);
  await expect(page.getByLabel('Repetí la contraseña')).toHaveCount(0);
  await page.getByLabel('Tu nombre').fill('Ana');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Crear mi cuenta gratis' }).click();
  await expect(page).toHaveURL(/\/app\/onboarding$/);

  // Sin confirmar el email: aviso en el panel con opción de reenviar el enlace.
  await page.getByRole('link', { name: 'Saltear por ahora' }).click();
  await expect(page).toHaveURL(/\/app\/$/);
  await expect(page.getByRole('heading', { name: 'Hola, Ana' })).toBeVisible();
  const banner = page.locator('#verify-email-banner');
  await expect(banner).toContainText('Confirmá tu email');
  await banner.getByRole('button', { name: 'Reenviar el enlace' }).click();
  await expect(banner).toContainText('te reenviamos el enlace');

  // Verificación con el link del email (el token se quita de la barra de direcciones).
  const verifyLink = await lastEmailLink(request, email, '/verificar');
  await page.goto(verifyLink);
  await expect(page.getByRole('heading', { name: 'Email confirmado' })).toBeVisible();
  await expect(page).toHaveURL(/\/verificar$/);
  await page.goto('/app/');
  await expect(page.getByRole('heading', { name: 'Hola, Ana' })).toBeVisible();
  await expect(page.locator('#verify-email-banner')).toHaveCount(0);

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

  // Volver a ingresar con email y contraseña.
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/app\/negocio$/);
});

test('validación accesible en el registro y credenciales incorrectas', async ({ page }) => {
  await page.goto('/registro');
  await page.getByRole('button', { name: 'Crear mi cuenta gratis' }).click();
  const firstName = page.getByLabel('Tu nombre');
  await expect(firstName).toHaveAttribute('aria-invalid', 'true');
  await expect(firstName).toBeFocused();
  await expect(page.getByText('Ingresá tu nombre.')).toBeVisible();

  await page.goto('/ingresar');
  await page.getByLabel('Email').fill(uniqueEmail('nadie'));
  await page.getByLabel('Contraseña', { exact: true }).fill('una-clave-cualquiera');
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page.getByRole('alert')).toHaveText('El email o la contraseña no son correctos.');
});
