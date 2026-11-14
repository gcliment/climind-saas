// @ts-check
const { defineConfig, devices } = require('@playwright/test');

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:8080';

/**
 * Corre contra la app con perfil dev (necesita /api/dev/emails para leer los links de verificación).
 * Si no hay una app levantada en BASE_URL, la inicia con PostgreSQL embebido (requiere JAVA_HOME → JDK 21).
 */
module.exports = defineConfig({
  testDir: './specs',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    locale: 'es-AR',
    timezoneId: 'America/Argentina/Buenos_Aires',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 860 } } },
    // Otros navegadores (E2E_BROWSERS=all): Safari en iPhone (WebKit) y Firefox de escritorio.
    // Requieren `npx playwright install firefox webkit`.
    ...(process.env.E2E_BROWSERS === 'all' ? [
      { name: 'iphone-webkit', use: { ...devices['iPhone 14'] } },
      { name: 'firefox', use: { ...devices['Desktop Firefox'], viewport: { width: 1366, height: 860 } } },
    ] : []),
  ],
  webServer: process.env.E2E_BASE_URL ? undefined : {
    command: 'mvn -q -B spring-boot:test-run -Dspring-boot.run.main-class=com.climind.saas.LocalDevApplication',
    cwd: '../../backend',
    url: `${BASE_URL}/actuator/health`,
    reuseExistingServer: true,
    timeout: 240_000,
    // Toda la suite entra desde la misma IP: se sube el límite de autenticación SOLO para estas pruebas
    // (en producción queda en 20 por minuto; el rate limit tiene sus propios tests de integración).
    env: { RATE_LIMIT_AUTH_PER_MINUTE: '300' },
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
