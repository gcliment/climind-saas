// Demo ilustrativa de la landing. Precios FICTICIOS, calculados en el navegador solo para mostrar
// la experiencia. En los cotizadores reales el importe lo calcula y valida el servidor.
import { formatMoney, formatNumber } from './format.js';

const PRICE_PER_M2 = { basica: 1200, profunda: 1900, obra: 2600 };
const TYPE_LABEL = { basica: 'Limpieza básica', profunda: 'Limpieza profunda', obra: 'Después de obra' };
const EXTRAS = { vidrios: ['Vidrios', 15000], horno: ['Horno', 9000] };
const URGENT_PERCENT = 20;
const MINIMUM = 30000;

const form = document.getElementById('demo');
if (form) {
  const m2 = form.querySelector('#demo-m2');
  const m2Out = form.querySelector('#demo-m2-out');
  const lines = form.querySelector('#demo-lines');
  const total = form.querySelector('#demo-total');
  let lastTotal = null;

  const render = () => {
    const type = form.tipo.value;
    const meters = Number(m2.value);
    const extras = [...form.querySelectorAll('input[name="extra"]:checked')].map((i) => i.value);
    const items = [[`${TYPE_LABEL[type]} (${formatNumber(meters)} m²)`, meters * PRICE_PER_M2[type]]];
    extras.filter((e) => EXTRAS[e]).forEach((e) => items.push(EXTRAS[e]));
    let sum = items.reduce((acc, [, amount]) => acc + amount, 0);
    if (extras.includes('urgente')) {
      const surcharge = Math.round(sum * URGENT_PERCENT) / 100;
      items.push([`Urgencia (+${URGENT_PERCENT}%)`, surcharge]);
      sum += surcharge;
    }
    if (sum < MINIMUM) {
      items.push(['Ajuste a precio mínimo', MINIMUM - sum]);
      sum = MINIMUM;
    }

    m2Out.textContent = `${formatNumber(meters)} m²`;
    lines.replaceChildren(...items.map(([label, amount]) => {
      const li = document.createElement('li');
      const name = document.createElement('span');
      name.textContent = label;
      const value = document.createElement('b');
      value.textContent = formatMoney(amount, { decimals: 0 });
      li.append(name, value);
      return li;
    }));
    total.textContent = formatMoney(sum, { decimals: 0 });
    if (lastTotal !== null && lastTotal !== sum) {
      total.classList.remove('bump');
      void total.offsetWidth; // reinicia la animación
      total.classList.add('bump');
    }
    lastTotal = sum;
  };

  form.addEventListener('input', render);
  form.addEventListener('submit', (e) => e.preventDefault());
  render();
}
