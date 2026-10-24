// ScreenMT — la ventana. Todo lo que toca la tablet lo hace el lado nativo (main.rs) con adb y scrcpy.
const T = window.__TAURI__;
// Abierta en un navegador normal (para mirar el diseño) usa datos de ejemplo.
const invoke = T ? T.core.invoke : demo;

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// ajustes: comodidad de este Mac, nada importante se guarda aquí
const AJ = Object.assign(
  { tamano: 1400, apagar: false, sinSonido: false, destino: '', ancho: 440, cable: true, extra: true, calidad: 'nitida', espacio: 'grande' },
  leer()
);
function leer() { try { return JSON.parse(localStorage.getItem('screenmt') || '{}'); } catch { return {}; } }
function guardar() { try { localStorage.setItem('screenmt', JSON.stringify(AJ)); } catch {} }

const V = { pant: null, pantErr: null, encendiendo: false, tab: ['ver', 'archivos', 'pantalla', 'ajustes'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'ver', estado: null, archivos: null, marcados: new Set(), ocupado: false };

const CARPETAS = [
  ['Pictures/Screenshots', 'Capturas'], ['Download', 'Descargas'], ['Documents', 'Documentos'],
  ['DCIM', 'Fotos'], ['Pictures', 'Imágenes'], ['Movies', 'Videos'],
];
const carpetaDe = (r) => (CARPETAS.find(([p]) => r.startsWith(p + '/')) || [, ''])[1];
const nombreDe = (r) => r.split('/').pop();
function peso(b) {
  if (b < 1024) return b + ' B';
  if (b < 1048576) return Math.round(b / 1024) + ' KB';
  if (b < 1073741824) return (b / 1048576).toFixed(1).replace('.', ',') + ' MB';
  return (b / 1073741824).toFixed(2).replace('.', ',') + ' GB';
}
function cuando(seg) {
  const d = new Date(seg * 1000), hoy = new Date();
  const dias = Math.floor((new Date(hoy.toDateString()) - new Date(d.toDateString())) / 864e5);
  const hora = d.toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' });
  if (dias === 0) return 'hoy ' + hora;
  if (dias === 1) return 'ayer ' + hora;
  return d.toLocaleDateString('es-CO', { day: 'numeric', month: 'short', year: d.getFullYear() === hoy.getFullYear() ? undefined : 'numeric' });
}

let avisoT;
function aviso(txt, mal) {
  const a = $('#aviso');
  a.textContent = txt;
  a.className = 'aviso ver' + (mal ? ' mal' : '');
  clearTimeout(avisoT);
  avisoT = setTimeout(() => (a.className = 'aviso'), 3200);
}

/* ---------- estado de la conexión ---------- */
async function revisar() {
  try { V.estado = await invoke('estado'); } catch { V.estado = { conectada: false }; }
  try {
    const p = await invoke('pantalla_estado');
    const cambio = !!p.activa !== !!V.pant?.activa;
    const ctl = V.pant?.control;
    V.pant = Object.assign(V.pant || {}, p);
    // el lado nativo solo avisa cuando el permiso llega tarde; si no, se conserva lo del arranque
    V.pant.control = p.control || ctl;
    if (p.control && ctl === false && V.tab === 'pantalla') pintar(false);
    if (p.error) { V.pantErr = p.error; }
    if (cambio && V.tab === 'pantalla') pintar(false);
  } catch {}
  const e = V.estado;
  if (e.recibidos?.length) {
    aviso(e.recibidos.length === 1 ? `Llegó de la tablet: ${e.recibidos[0]}` : `Llegaron ${e.recibidos.length} archivos de la tablet`);
  }
  const antes = $('#estado').dataset.c;
  $('#estado').dataset.c = e.conectada ? '1' : '0';
  $('#estado').innerHTML = e.conectada
    ? `<span class="punto ok"></span><div><b>${esc(e.nombre || 'Tablet')}</b><small>Conectada por cable</small></div>
       <div class="der">${e.bateria != null ? `Batería ${e.bateria} %${e.cargando ? ' · cargando' : ''}` : ''}<br>${e.viendo ? 'Viéndola en el Mac' : '&nbsp;'}</div>`
    : `<span class="punto mal"></span><div><b>No veo la tablet</b><small>Conecta el cable y acepta «Permitir depuración USB» en la tablet</small></div>`;
  if (antes !== $('#estado').dataset.c) {
    if (e.conectada && V.tab === 'archivos') cargarArchivos();
    pintar(false);
  } else if (V.tab === 'ver') pintarPie();
}

/* ---------- pestañas ---------- */
const VISTAS = { ver: vVer, archivos: vArchivos, pantalla: vPantalla, ajustes: vAjustes };
function pintar(animar = true) {
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === V.tab));
  const m = $('#vista');
  m.innerHTML = VISTAS[V.tab]();
  m.className = 't-' + V.tab;
  if (animar) { void m.offsetWidth; m.classList.add('entra'); }
  pintarPie();
}
function pintarPie() {
  const c = V.estado?.conectada;
  const p = {
    ver: `<button class="btn pri" data-a="ver" ${c ? '' : 'disabled'}>${V.estado?.viendo ? 'Traer la ventana al frente' : 'Abrir la tablet en el Mac'}</button>`,
    archivos: `<button class="btn" data-a="carpeta">Abrir la carpeta</button>
      <button class="btn pri" data-a="traer" ${c && V.marcados.size && !V.ocupado ? '' : 'disabled'}>${V.ocupado ? 'Pasando…' : `Traer${V.marcados.size ? ' ' + V.marcados.size : ''} al Mac`}</button>`,
    pantalla: V.pant?.activa
      ? `<button class="btn pri" data-a="pant-apagar">Apagar segunda pantalla</button>`
      : `<button class="btn pri" data-a="pant-encender" ${V.encendiendo || (AJ.cable && !c) ? 'disabled' : ''}>${V.encendiendo ? 'Encendiendo…' : 'Encender segunda pantalla'}</button>`,
    ajustes: '',
  }[V.tab];
  if ($('#pie').innerHTML !== p) $('#pie').innerHTML = p;
}

const elige = (clave, ops) =>
  `<div class="elige">${ops.map(([v, t]) => `<button data-a="aj" data-k="${clave}" data-v="${v}" class="${String(AJ[clave]) === String(v) ? 'on' : ''}">${t}</button>`).join('')}</div>`;
const sw = (clave, titulo, sub) =>
  `<button class="fila${AJ[clave] ? ' on' : ''}" data-a="sw" data-k="${clave}"><div class="n"><b>${titulo}</b>${sub ? `<small>${sub}</small>` : ''}</div><span class="sw"></span></button>`;
const radio = (clave, valor, titulo, sub) =>
  `<button class="fila${AJ[clave] === valor ? ' on' : ''}" data-a="aj" data-k="${clave}" data-v="${valor}"><span class="radio"></span><div class="n"><b>${titulo}</b><small>${sub}</small></div></button>`;

function vVer() {
  return `
  <div class="caja ancha"><h3>Calidad de la imagen</h3>
    ${elige('tamano', [[1024, 'Liviana'], [1400, 'Normal'], [1920, 'Nítida']])}
  </div>
  <div class="caja ancha"><h3>Mientras la manejas</h3>
    ${sw('apagar', 'Apagar la pantalla de la tablet', 'Se sigue viendo en el Mac y ahorra batería')}
    ${sw('sinSonido', 'Sin el sonido de la tablet', 'Por defecto el sonido sale por el Mac')}
  </div>
  <div class="caja ancha cuadricula"><h3>Atajos en la ventana de la tablet</h3>
    <div class="fila"><div class="n"><b>Arrastrar un archivo encima</b><small>Llega a Descargas de la tablet · un .apk se instala</small></div></div>
    <div class="fila"><div class="n"><b>⌘C y ⌘V</b><small>Copias en un lado y pegas en el otro</small></div></div>
    <div class="fila"><div class="n"><b>Clic derecho</b><small>Atrás</small></div></div>
    <div class="fila"><div class="n"><b>⌘H</b><small>Ir al inicio de la tablet</small></div></div>
  </div>`;
}

function vArchivos() {
  const c = V.estado?.conectada;
  let lista;
  if (!c) lista = `<div class="vacio">Conecta la tablet para ver sus archivos.</div>`;
  else if (!V.archivos) lista = `<div class="cargando"></div><div class="cargando" style="width:70%"></div><div class="cargando" style="width:85%"></div>`;
  else if (!V.archivos.length) lista = `<div class="vacio">La tablet no tiene archivos en Descargas, Documentos, Fotos, Capturas ni Videos.</div>`;
  else lista = V.archivos.map((a) => `
    <label class="fila${V.marcados.has(a.ruta) ? ' on' : ''}" data-a="marcar" data-r="${esc(a.ruta)}">
      <span class="ck"></span><div class="n"><b>${esc(nombreDe(a.ruta))}</b><small>${carpetaDe(a.ruta)} · ${peso(a.bytes)} · ${cuando(a.fecha)}</small></div>
    </label>`).join('');
  const todos = V.archivos?.length && V.marcados.size === V.archivos.length;
  return `
  <div class="soltar" id="soltar"><b>Suelta aquí lo que quieras enviar</b>
    <small>Llega a Descargas de la tablet · o <button data-a="elegir">elegir archivos</button></small></div>
  <div class="caja desde"><h3>Desde la tablet</h3>
    <div class="fila"><div class="n"><b>En la app ScreenMT de la tablet: «Enviar archivos al Mac»</b>
      <small>Llegan solos a ${esc(AJ.destino || 'Descargas › Desde la tablet')}</small></div></div></div>
  <div class="caja crece"><h3>En la tablet <span class="der">
      ${V.archivos?.length ? `<button data-a="todos">${todos ? 'Quitar marcas' : 'Marcar todo'}</button> · ` : ''}<button data-a="recargar">Recargar</button></span></h3>
    <div class="lista">${lista}</div>
  </div>`;
}

function vPantalla() {
  const P = V.pant || {};
  if (P.activa) {
    const wifi = !AJ.cable;
    let qr = '';
    if (wifi && P.url && window.qrcode) { const q = qrcode(0, 'M'); q.addData(P.url); q.make(); qr = q.createSvgTag(4, 0); }
    return `
    <div class="caja ancha"><h3>Encendida</h3>
      <div class="fila"><span class="punto ok"></span><div class="n"><b>La tablet está mostrando tu Mac</b>
        <small>${AJ.extra ? 'Como pantalla extra' : 'Copia de la pantalla principal'} · ${wifi ? 'por wifi' : 'por cable'}${P.info ? ` · ${P.info.ancho} × ${P.info.alto} a ${P.info.fps} cuadros/s` : ''}</small></div></div>
      ${wifi ? `<div class="fila qr"><div class="cod">${qr}</div><div class="n"><b>Escanea el código con la cámara de la tablet</b>
        <small>Se abre la app ScreenMT de la tablet y se conecta sola. Si no la tienes, instálala en Ajustes (por cable, una vez).</small></div></div>` : ''}
    </div>
    ${P.control === false ? `<div class="caja ancha"><h3>Manejar el Mac desde la tablet</h3>
      <button class="fila" data-a="permiso" data-p="control"><div class="n"><b>Falta el permiso de Accesibilidad</b>
        <small>Actívalo para ScreenMT y vuelve a encender. Mientras tanto la tablet solo muestra.</small></div><span class="flecha">›</span></button></div>` : ''}
    <div class="caja ancha cuadricula"><h3>En la tablet</h3>
      <div class="fila"><div class="n"><b>Tocar</b><small>Clic</small></div></div>
      <div class="fila"><div class="n"><b>Tocar y mover</b><small>Arrastrar</small></div></div>
      <div class="fila"><div class="n"><b>Dejar el dedo quieto</b><small>Clic derecho</small></div></div>
      <div class="fila"><div class="n"><b>Dos dedos</b><small>Desplazar</small></div></div>
    </div>
    ${AJ.extra ? `<button class="caja fila sola ancha" data-a="permiso" data-p="pantallas"><div class="n"><b>Ordenar las pantallas</b>
      <small>Dile al Mac si la tablet queda a la izquierda o a la derecha de tu monitor</small></div><span class="flecha">›</span></button>` : ''}`;
  }
  const err = V.pantErr;
  return `
  ${err ? `<div class="caja mal"><h3>No se pudo encender</h3>
    ${err.codigo === 'permiso_pantalla'
      ? `<button class="fila" data-a="permiso" data-p="pantalla"><div class="n"><b>Falta el permiso de Grabación de pantalla</b>
         <small>Actívalo para ScreenMT, cierra ScreenMT y vuelve a abrirla.</small></div><span class="flecha">›</span></button>`
      : `<div class="fila"><div class="n"><b>${esc(err.error || err)}</b></div></div>`}</div>` : ''}
  <div class="caja"><h3>Cómo se conecta</h3>
    ${radio('cable', true, 'Por cable', 'Más rápida, sin retraso y sin depender del wifi')}
    ${radio('cable', false, 'Por wifi', 'Sin cable · el Mac y la tablet en el mismo wifi')}
  </div>
  <div class="caja"><h3>Qué muestra la tablet</h3>
    ${radio('extra', true, 'Una pantalla extra', 'Arrastras ventanas del Mac hacia la tablet')}
    ${radio('extra', false, 'Copia de esta pantalla', 'Lo mismo que ves en tu pantalla principal')}
  </div>
  <div class="caja ancha"><h3>Calidad</h3>
    ${elige('calidad', [['fluida', 'Fluida'], ['media', 'Equilibrada'], ['nitida', 'Nítida'], ['maxima', 'Máxima']])}
    <p class="explica">${{
      fluida: 'La mitad del tamaño, 60 cuadros por segundo. Para wifi o equipos lentos.',
      media: 'Algo menos nítida, siempre a 60 cuadros por segundo: lo más suave.',
      nitida: 'El tamaño más grande que acepta tu tablet. Puede bajar a unos 30 cuadros por segundo.',
      maxima: 'Como Nítida, con el doble de datos: casi sin compresión visible. Mejor por cable.',
    }[AJ.calidad] || ''}</p>
  </div>
  ${AJ.extra ? `<div class="caja ancha"><h3>Tamaño de lo que ves en la tablet</h3>
    ${elige('espacio', [['grande', 'Grande'], ['medio', 'Mediano'], ['maximo', 'Más espacio']])}
    <p class="explica">${{
      grande: 'Como un Mac Retina: letras y botones grandes, cómodos para tocar.',
      medio: 'Un punto intermedio: caben más cosas sin que la letra quede muy pequeña.',
      maximo: 'Un punto por cada píxel: cabe mucho más, con letra pequeña.',
    }[AJ.espacio] || ''}</p>
  </div>` : ''}`;
}

function vAjustes() {
  return `
  <div class="caja ancha"><h3>Archivos que traes de la tablet</h3>
    <button class="fila" data-a="destino"><div class="n"><b>${esc(AJ.destino || 'Descargas › Desde la tablet')}</b><small>Toca para cambiar la carpeta</small></div></button>
  </div>
  <div class="caja ancha"><h3>App ScreenMT en la tablet</h3>
    <button class="fila" data-a="instalar"><div class="n"><b>${V.instalando ? 'Instalando…' : 'Instalar o actualizar por cable'}</b>
      <small>Se instala sola al encender la segunda pantalla por cable. Para usar solo wifi, instálala una vez aquí.</small></div><span class="flecha">›</span></button>
  </div>
  <div class="caja ancha"><h3>Ancho al pegarla a un lado</h3>
    ${elige('ancho', [[380, 'Angosta'], [440, 'Normal'], [560, 'Ancha']])}
  </div>
  <div class="caja ancha"><h3>Acerca de</h3>
    <div class="fila"><div class="n"><b>ScreenMT 0.3.0</b><small>Código abierto (MIT). Usa scrcpy y adb. En la tablet solo va la app ScreenMT, para la segunda pantalla.</small></div></div>
  </div>`;
}

/* ---------- acciones ---------- */
async function cargarArchivos() {
  V.archivos = null;
  if (V.tab === 'archivos') pintar(false);
  try { V.archivos = await invoke('listar'); } catch (e) { V.archivos = []; aviso('No pude leer la tablet: ' + e, true); }
  const hay = new Set(V.archivos.map((a) => a.ruta));
  V.marcados.forEach((r) => hay.has(r) || V.marcados.delete(r));
  if (V.tab === 'archivos') pintar(false);
}

async function enviar(rutas) {
  if (!rutas?.length) return;
  if (!V.estado?.conectada) return aviso('Primero conecta la tablet.', true);
  aviso(`Enviando ${rutas.length} archivo${rutas.length > 1 ? 's' : ''}…`);
  try {
    await invoke('enviar', { rutas });
    aviso(`Listo: ${rutas.length} en Descargas de la tablet.`);
    cargarArchivos();
  } catch (e) { aviso('No se pudo enviar: ' + e, true); }
}

async function pegar(lado) {
  if (!T) return;
  const w = T.window.getCurrentWindow();
  const m = await w.currentMonitor();
  if (!m) return;
  const area = m.workArea || { position: m.position, size: m.size };
  const ancho = Math.round(AJ.ancho * m.scaleFactor);
  const x = lado === 'izq' ? area.position.x : area.position.x + area.size.width - ancho;
  await w.setSize(new T.dpi.PhysicalSize(ancho, area.size.height));
  await w.setPosition(new T.dpi.PhysicalPosition(x, area.position.y));
}

document.addEventListener('click', async (ev) => {
  const t = ev.target.closest('[data-tab],[data-a]');
  if (!t) return;
  if (t.dataset.tab) {
    if (t.dataset.tab === V.tab) return;
    V.tab = t.dataset.tab;
    pintar();
    if (V.tab === 'archivos' && V.estado?.conectada && !V.archivos) cargarArchivos();
    return;
  }
  const a = t.dataset.a;
  if (a === 'lado') pegar(t.dataset.lado);
  else if (a === 'sw') { AJ[t.dataset.k] = !AJ[t.dataset.k]; guardar(); t.classList.toggle('on'); }
  else if (a === 'aj') {
    const k = t.dataset.k, v = t.dataset.v;
    AJ[k] = v === 'true' ? true : v === 'false' ? false : isNaN(v) ? v : Number(v);
    guardar(); pintar(false);
  }
  else if (a === 'ver') {
    try { await invoke('ver_tablet', { tamano: AJ.tamano, apagarPantalla: AJ.apagar, sinSonido: AJ.sinSonido }); setTimeout(revisar, 1500); }
    catch (e) { aviso(String(e), true); }
  }
  else if (a === 'marcar') {
    ev.preventDefault();
    const r = t.dataset.r;
    V.marcados.has(r) ? V.marcados.delete(r) : V.marcados.add(r);
    t.classList.toggle('on');
    pintarPie();
  }
  else if (a === 'todos') {
    const todos = V.marcados.size === V.archivos.length;
    V.marcados = todos ? new Set() : new Set(V.archivos.map((x) => x.ruta));
    pintar(false);
  }
  else if (a === 'recargar') cargarArchivos();
  else if (a === 'elegir') {
    const r = T ? await T.dialog.open({ multiple: true, title: 'Elige lo que quieres pasar a la tablet' }) : null;
    enviar(Array.isArray(r) ? r : r ? [r] : []);
  }
  else if (a === 'traer') {
    const rutas = [...V.marcados];
    V.ocupado = true; pintarPie();
    try {
      await invoke('traer', { rutas, destino: AJ.destino });
      aviso(`Listo: ${rutas.length} en el Mac.`);
      V.marcados.clear();
      invoke('abrir_carpeta', { destino: AJ.destino });
    } catch (e) { aviso('No se pudo traer: ' + e, true); }
    V.ocupado = false; pintar(false);
  }
  else if (a === 'pant-encender') {
    V.encendiendo = true; V.pantErr = null; pintar(false);
    try {
      const r = await invoke('pantalla_encender', { cable: AJ.cable, extra: AJ.extra, calidad: AJ.calidad, espacio: AJ.espacio });
      V.pant = { activa: true, url: r.url, control: r.control };
      aviso(AJ.cable ? 'Listo: mira la tablet.' : 'Listo: abre la dirección en la tablet.');
    } catch (e) { V.pantErr = e; }
    V.encendiendo = false; pintar(false);
  }
  else if (a === 'pant-apagar') { await invoke('pantalla_apagar'); V.pant = { activa: false }; pintar(false); }
  else if (a === 'instalar') {
    if (V.instalando) return;
    V.instalando = true; pintar(false);
    try { await invoke('instalar_app_tablet'); aviso('Listo: la app ScreenMT quedó en la tablet.'); }
    catch (e) { aviso(String(e), true); }
    V.instalando = false; pintar(false);
  }
  else if (a === 'permiso') invoke('abrir_permiso', { cual: t.dataset.p });
  else if (a === 'carpeta') invoke('abrir_carpeta', { destino: AJ.destino });
  else if (a === 'destino') {
    const d = T ? await T.dialog.open({ directory: true, title: '¿Dónde quieres que lleguen los archivos de la tablet?' }) : null;
    if (d) { AJ.destino = d; guardar(); invoke('fijar_destino', { destino: d }); pintar(false); }
  }
});

// arrastrar archivos del Finder: en la pestaña Archivos se envían a la tablet
if (T) {
  T.webview.getCurrentWebview().onDragDropEvent((e) => {
    const z = $('#soltar');
    const tipo = e.payload.type;
    if (tipo === 'enter' || tipo === 'over') {
      if (V.tab !== 'archivos') { V.tab = 'archivos'; pintar(); if (!V.archivos) cargarArchivos(); }
      $('#soltar')?.classList.add('encima');
    } else if (tipo === 'leave') z?.classList.remove('encima');
    else if (tipo === 'drop') { z?.classList.remove('encima'); enviar(e.payload.paths); }
  });
}

/* ---------- datos de ejemplo (solo en un navegador, para revisar el diseño) ---------- */
function demo(cmd) {
  const ahora = Date.now() / 1000;
  const r = {
    pantalla_estado: { activa: false, url: '' },
    estado: { conectada: true, nombre: 'Lenovo TB336FU', bateria: 83, cargando: true, viendo: false, recibidos: [] },
    fijar_destino: null,
    listar: [
      { ruta: 'Download/Factura-octubre.pdf', bytes: 245760, fecha: ahora - 600 },
      { ruta: 'DCIM/Camera/IMG_20261023_1630.jpg', bytes: 3250000, fecha: ahora - 3600 },
      { ruta: 'Pictures/Screenshots/Captura 2026-10-22.png', bytes: 839000, fecha: ahora - 90000 },
      { ruta: 'Documents/Presupuesto 2026.xlsx', bytes: 57000, fecha: ahora - 300000 },
    ],
  }[cmd];
  return new Promise((ok) => setTimeout(() => ok(r), 300));
}

if (T) invoke('fijar_destino', { destino: AJ.destino });
if (!T && V.tab === 'archivos') cargarArchivos();
pintar(false);
revisar();
setInterval(revisar, 3000);
