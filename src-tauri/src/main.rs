// ScreenMT: maneja una tablet Android por cable desde el Mac (scrcpy + adb).
// Todo lo que toca la tablet pasa por adb; la ventana solo pide y muestra.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;

// Abierta desde el Dock la app no hereda el PATH de la terminal: rutas absolutas.
fn home() -> String {
    std::env::var("HOME").unwrap_or_default()
}
// adb puede venir del SDK de Android (Android Studio) o de Homebrew (`brew install android-platform-tools`)
fn adb_path() -> String {
    let sdk = format!("{}/Library/Android/sdk/platform-tools/adb", home());
    [sdk.as_str(), "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
        .iter()
        .find(|p| std::path::Path::new(p).exists())
        .map(|p| p.to_string())
        .unwrap_or(sdk)
}
fn scrcpy_path() -> &'static str {
    if std::path::Path::new("/opt/homebrew/bin/scrcpy").exists() {
        "/opt/homebrew/bin/scrcpy"
    } else {
        "/usr/local/bin/scrcpy"
    }
}

fn adb(args: &[&str]) -> Result<String, String> {
    let out = Command::new(adb_path())
        .args(args)
        .output()
        .map_err(|_| "No encuentro adb en este Mac.".to_string())?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

#[derive(Serialize)]
struct Estado {
    conectada: bool,
    nombre: String,
    bateria: Option<u8>,
    cargando: bool,
    viendo: bool,
    recibidos: Vec<String>,
}

#[tauri::command]
async fn estado() -> Estado {
    let viendo = Command::new("pgrep").args(["-x", "scrcpy"]).output().map(|o| o.status.success()).unwrap_or(false);
    let recibidos = std::mem::take(&mut *RECIBIDOS.lock().unwrap());
    if adb(&["get-state"]).as_deref() != Ok("device") {
        *PUENTE_ARCHIVOS.lock().unwrap() = false;
        return Estado { conectada: false, nombre: String::new(), bateria: None, cargando: false, viendo, recibidos };
    }
    // puente para que la app de la tablet pueda mandar archivos al Mac (una vez por conexión)
    let mut puente = PUENTE_ARCHIVOS.lock().unwrap();
    if !*puente {
        let p = format!("tcp:{}", PUERTO_ARCHIVOS);
        *puente = adb(&["reverse", &p, &p]).is_ok();
        if *puente { emparejar_por_cable(); }
    }
    drop(puente);
    let marca = adb(&["shell", "getprop", "ro.product.manufacturer"]).unwrap_or_default();
    let modelo = adb(&["shell", "getprop", "ro.product.model"]).unwrap_or_default();
    let bat = adb(&["shell", "dumpsys", "battery"]).unwrap_or_default();
    let dato = |k: &str| bat.lines().find_map(|l| l.trim().strip_prefix(k).map(|v| v.trim().to_string()));
    let cargando = ["AC powered:", "USB powered:"].iter().any(|k| dato(k).as_deref() == Some("true"));
    let marca = marca.chars().next().map(|c| c.to_uppercase().collect::<String>() + &marca[1..].to_lowercase()).unwrap_or_default();
    Estado {
        conectada: true,
        nombre: format!("{} {}", marca, modelo).trim().to_string(),
        bateria: dato("level:").and_then(|v| v.parse().ok()),
        cargando,
        viendo,
        recibidos,
    }
}

#[tauri::command]
fn ver_tablet(tamano: u32, apagar_pantalla: bool, sin_sonido: bool) -> Result<(), String> {
    let ya = Command::new("pgrep").args(["-x", "scrcpy"]).output().map(|o| o.status.success()).unwrap_or(false);
    if ya {
        // ya está abierta: solo traerla al frente
        let _ = Command::new("osascript").args(["-e", "tell application \"System Events\" to set frontmost of (first process whose name is \"scrcpy\") to true"]).status();
        return Ok(());
    }
    let mut c = Command::new(scrcpy_path());
    c.env("ADB", adb_path())
        .args(["--window-title", "Tablet", "--stay-awake"])
        .arg(format!("--max-size={}", tamano));
    if apagar_pantalla {
        c.arg("--turn-screen-off");
    }
    if sin_sonido {
        c.arg("--no-audio");
    }
    c.stdout(Stdio::null()).stderr(Stdio::null()).spawn().map_err(|_| "No encuentro scrcpy en este Mac.".to_string())?;
    Ok(())
}

#[derive(Serialize)]
struct Archivo {
    ruta: String,
    bytes: u64,
    fecha: i64,
}

#[tauri::command]
async fn listar() -> Result<Vec<Archivo>, String> {
    let txt = adb(&["shell", "cd /sdcard && find Download Documents DCIM Pictures Movies -type f ! -path '*/.*' -exec stat -c '%Y|%s|%n' {} + 2>/dev/null; true"])?;
    let mut v: Vec<Archivo> = txt
        .lines()
        .filter_map(|l| {
            let mut p = l.splitn(3, '|');
            Some(Archivo { fecha: p.next()?.parse().ok()?, bytes: p.next()?.parse().ok()?, ruta: p.next()?.to_string() })
        })
        .collect();
    v.sort_by(|a, b| b.fecha.cmp(&a.fecha));
    v.truncate(500); // ponytail: tope fijo; paginar si alguien tiene miles de fotos y las necesita todas
    Ok(v)
}

#[tauri::command]
async fn enviar(rutas: Vec<String>) -> Result<usize, String> {
    for r in &rutas {
        adb(&["push", r, "/sdcard/Download/"])?;
    }
    Ok(rutas.len())
}

fn carpeta(destino: &str) -> PathBuf {
    if destino.is_empty() {
        PathBuf::from(home()).join("Downloads/Desde la tablet")
    } else {
        PathBuf::from(destino)
    }
}

#[tauri::command]
async fn traer(rutas: Vec<String>, destino: String) -> Result<usize, String> {
    let d = carpeta(&destino);
    std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    for r in &rutas {
        // solo rutas que la propia lista devolvió: relativas y sin subir de carpeta
        if r.starts_with('/') || r.contains("..") {
            return Err(format!("Ruta no válida: {}", r));
        }
        adb(&["pull", "-a", &format!("/sdcard/{}", r), &d.to_string_lossy()])?;
    }
    Ok(rutas.len())
}

#[tauri::command]
fn abrir_carpeta(destino: String) -> Result<(), String> {
    let d = carpeta(&destino);
    std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    Command::new("open").arg(&d).status().map_err(|e| e.to_string())?;
    Ok(())
}

/* ---------- archivos que manda la app de la tablet ---------- */
// La app ScreenMT de la tablet se conecta a localhost:8732 (por cable, con `adb reverse`) y manda
// una línea JSON {"nombre","bytes"} seguida del archivo. Solo escucha en 127.0.0.1.
const PUERTO_ARCHIVOS: u16 = 8732;
static DESTINO: Mutex<String> = Mutex::new(String::new());
static RECIBIDOS: Mutex<Vec<String>> = Mutex::new(Vec::new());
static PUENTE_ARCHIVOS: Mutex<bool> = Mutex::new(false);

#[tauri::command]
fn fijar_destino(destino: String) {
    *DESTINO.lock().unwrap() = destino;
}

fn recibir_uno(c: std::net::TcpStream) -> Result<String, String> {
    use std::io::{BufRead, BufReader, Read, Write};
    let mut r = BufReader::new(c.try_clone().map_err(|e| e.to_string())?);
    let mut cab = String::new();
    r.read_line(&mut cab).map_err(|e| e.to_string())?;
    let v: serde_json::Value = serde_json::from_str(&cab).map_err(|_| "cabecera rara".to_string())?;
    // solo el nombre, sin carpetas: nada puede escribir fuera del destino
    let nombre: String = std::path::Path::new(v["nombre"].as_str().unwrap_or("archivo"))
        .file_name().map(|n| n.to_string_lossy().to_string()).filter(|n| !n.starts_with('.')).unwrap_or_else(|| "archivo".into());
    let bytes = v["bytes"].as_u64().ok_or("falta el tamaño")?;
    let d = carpeta(&DESTINO.lock().unwrap());
    std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    // no pisar: «foto.jpg», «foto (2).jpg», …
    let p = std::path::Path::new(&nombre);
    let (base, ext) = (p.file_stem().unwrap_or_default().to_string_lossy().to_string(), p.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default());
    let mut ruta = d.join(&nombre);
    let mut n = 2;
    while ruta.exists() { ruta = d.join(format!("{} ({}){}", base, n, ext)); n += 1; }
    let parcial = ruta.with_extension("screenmt-parcial");
    let mut f = std::fs::File::create(&parcial).map_err(|e| e.to_string())?;
    let copiados = std::io::copy(&mut r.by_ref().take(bytes), &mut f).map_err(|e| e.to_string())?;
    if copiados != bytes {
        let _ = std::fs::remove_file(&parcial);
        return Err("llegó incompleto".into());
    }
    std::fs::rename(&parcial, &ruta).map_err(|e| e.to_string())?;
    let mut c = c;
    let _ = c.write_all(b"OK\n");
    Ok(ruta.file_name().unwrap().to_string_lossy().to_string())
}

fn escuchar_archivos() {
    let Ok(l) = std::net::TcpListener::bind(("127.0.0.1", PUERTO_ARCHIVOS)) else { return };
    for c in l.incoming().flatten() {
        std::thread::spawn(move || {
            let mut copia = c.try_clone().ok();
            match recibir_uno(c) {
                Ok(n) => RECIBIDOS.lock().unwrap().push(n),
                Err(e) => { if let Some(c) = copia.as_mut() { use std::io::Write; let _ = c.write_all(format!("ERROR {}\n", e).as_bytes()); } }
            }
        });
    }
}

/* ---------- segunda pantalla: la tablet muestra el Mac ---------- */
// El trabajo lo hace `screenmt-pantalla` (Swift, va dentro de la app). Aquí solo se abre, se cierra
// y, por cable, se hace el puente `adb reverse` para que la tablet llegue a él por `localhost`.
const PUERTO: u16 = 8730;
// la app de la tablet (carpeta android/); subir APP_VERSION junto con versionCode para que se actualice sola
const APP_TABLET: &str = "io.github.juanp30.screenmt";
const APP_VERSION: u32 = 15;

fn recurso(nombre: &str) -> PathBuf {
    std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join("../Resources").join(nombre))).unwrap_or_default()
}

// instala o actualiza la app de la tablet si hace falta (por cable)
fn preparar_app_tablet() -> Result<(), String> {
    let info = adb(&["shell", "dumpsys", "package", APP_TABLET]).unwrap_or_default();
    let instalada: u32 = info.split("versionCode=").nth(1).and_then(|v| v.split_whitespace().next()).and_then(|v| v.parse().ok()).unwrap_or(0);
    if instalada >= APP_VERSION { return Ok(()); }
    let apk = recurso("screenmt-tablet.apk");
    adb(&["install", "-r", &apk.to_string_lossy()]).map(|_| ()).map_err(|e| format!("No pude instalar la app en la tablet: {}", e))
}

#[derive(Default)]
struct Pantalla {
    hijo: Option<std::process::Child>,
    url: String,
    cable: bool,
    error: Option<serde_json::Value>,
    // lo que la tablet aceptó mostrar: {"ancho","alto","fps"}
    info: Option<serde_json::Value>,
    // el permiso de Accesibilidad llegó con la pantalla ya encendida
    control: bool,
}
static PANTALLA: Mutex<Pantalla> = Mutex::new(Pantalla { hijo: None, url: String::new(), cable: false, error: None, info: None, control: false });

fn ayudante() -> PathBuf {
    std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join("screenmt-pantalla"))).unwrap_or_default()
}

// ancho × alto de la tablet acostada, en píxeles reales
fn tamano_tablet() -> (u32, u32) {
    let s = adb(&["shell", "wm", "size"]).unwrap_or_default();
    let v: Vec<u32> = s.rsplit(':').next().unwrap_or("").trim().split('x').filter_map(|n| n.trim().parse().ok()).collect();
    if v.len() == 2 { (v[0].max(v[1]), v[0].min(v[1])) } else { (2560, 1600) }
}

fn apagar_todo(p: &mut Pantalla) {
    if let Some(mut h) = p.hijo.take() {
        let _ = h.kill();
        let _ = h.wait();
    }
    if p.url.is_empty() { return; } // no había nada encendido
    // cierra la app de la tablet si está conectada (por wifi sin cable simplemente se queda esperando)
    let _ = adb(&["shell", "am", "force-stop", APP_TABLET]);
    if p.cable {
        let _ = adb(&["reverse", "--remove", &format!("tcp:{}", PUERTO)]);
    }
    p.url.clear();
    p.cable = false;
    p.info = None;
    p.control = false;
}

#[tauri::command]
async fn pantalla_encender(cable: bool, extra: bool, calidad: String, espacio: String, desplazar: String, invertir: bool) -> Result<serde_json::Value, serde_json::Value> {
    use std::io::{BufRead, BufReader};
    let falla = |m: &str| serde_json::json!({ "error": m, "codigo": "error" });
    apagar_todo(&mut PANTALLA.lock().unwrap());
    let conectada = adb(&["get-state"]).as_deref() == Ok("device");
    if cable && !conectada {
        return Err(falla("Conecta la tablet por cable o elige «Por wifi»."));
    }
    let (ancho, alto) = if conectada { tamano_tablet() } else { (2560, 1600) };
    let mut hijo = Command::new(ayudante())
        .args(["--modo", if extra { "extra" } else { "copia" }, "--red", if cable { "cable" } else { "wifi" }, "--calidad", &calidad, "--espacio", &espacio, "--desplazar", &desplazar, "--invertir", if invertir { "si" } else { "no" }])
        .args(if cable { vec![] } else { vec!["--clave".to_string(), clave_wifi()] })
        .args(["--ancho", &ancho.to_string(), "--alto", &alto.to_string(), "--puerto", &PUERTO.to_string()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| falla("Falta el programa de la segunda pantalla dentro de ScreenMT."))?;
    let mut lineas = BufReader::new(hijo.stdout.take().unwrap()).lines();
    // la primera línea dice si arrancó; las siguientes solo llegan si algo falla después
    let primera: serde_json::Value = lineas
        .next()
        .and_then(|l| l.ok())
        .and_then(|l| serde_json::from_str(&l).ok())
        .unwrap_or_else(|| falla("La segunda pantalla se cerró al arrancar."));
    if primera.get("error").is_some() {
        let _ = hijo.kill();
        return Err(primera);
    }
    let host = primera["host"].as_str().unwrap_or("127.0.0.1").to_string();
    let clave = primera["clave"].as_str().unwrap_or("").to_string();
    // lo que lleva el código QR por wifi: abre la app de la tablet ya apuntando a este Mac
    let url = format!("screenmt://abrir?h={}&p={}&k={}", host, PUERTO, clave);
    if cable {
        let p = format!("tcp:{}", PUERTO);
        adb(&["reverse", &p, &p]).map_err(|e| { let _ = hijo.kill(); falla(&e) })?;
    }
    if conectada {
        // siempre la app ScreenMT de la tablet, nunca el navegador
        preparar_app_tablet().map_err(|e| { let _ = hijo.kill(); falla(&e) })?;
        let _ = adb(&["shell", "am", "start", "-n", &format!("{}/.Principal", APP_TABLET),
                      "--es", "host", &host, "--ei", "puerto", &PUERTO.to_string(), "--es", "clave", &clave]);
    }
    std::thread::spawn(move || {
        for l in lineas.map_while(Result::ok) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&l) {
                if v.get("error").is_some() { PANTALLA.lock().unwrap().error = Some(v); }
                else if v.get("ajuste").is_some() { PANTALLA.lock().unwrap().info = Some(v); }
                else if v.get("control").is_some() { PANTALLA.lock().unwrap().control = true; }
            }
        }
    });
    let mut p = PANTALLA.lock().unwrap();
    p.hijo = Some(hijo);
    p.url = url;
    p.cable = cable;
    p.error = None;
    Ok(primera)
}

// clave fija para wifi: la tablet la guarda (por QR una vez, o sola por cable) y se conecta sin escanear.
// Vive en un archivo solo legible por este usuario; «Olvidar tabletas» la cambia.
fn archivo_clave() -> PathBuf {
    PathBuf::from(home()).join("Library/Application Support/io.github.juanp30.screenmt/clave-wifi")
}
fn clave_wifi() -> String {
    if let Ok(k) = std::fs::read_to_string(archivo_clave()) {
        let k = k.trim().to_string();
        if k.len() >= 12 { return k; }
    }
    let letras = b"abcdefghjkmnpqrstuvwxyz23456789";
    let mut azar = [0u8; 20];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") { use std::io::Read; let _ = f.read_exact(&mut azar); }
    let k: String = azar.iter().map(|b| letras[(*b as usize) % letras.len()] as char).collect();
    let a = archivo_clave();
    let _ = std::fs::create_dir_all(a.parent().unwrap());
    let _ = std::fs::write(&a, &k);
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(&a, std::fs::Permissions::from_mode(0o600));
    k
}
fn ip_del_mac() -> String {
    for i in ["en0", "en1", "en2"] {
        if let Ok(o) = Command::new("ipconfig").args(["getifaddr", i]).output() {
            let s = String::from_utf8_lossy(&o.stdout).trim().to_string();
            if !s.is_empty() { return s; }
        }
    }
    String::new()
}
// le pasa a la app de la tablet, por cable y sin abrirla, cómo encontrar a este Mac por wifi
fn emparejar_por_cable() {
    let _ = adb(&["shell", "am", "broadcast", "-n", &format!("{}/.Emparejar", APP_TABLET),
                  "--es", "clave", &clave_wifi(), "--es", "host", &ip_del_mac(), "--ei", "puerto", &PUERTO.to_string()]);
}

#[tauri::command]
fn olvidar_tabletas() {
    let _ = std::fs::remove_file(archivo_clave());
    if adb(&["get-state"]).as_deref() == Ok("device") { emparejar_por_cable(); }
}

#[tauri::command]
fn pantalla_apagar() {
    apagar_todo(&mut PANTALLA.lock().unwrap());
}

#[tauri::command]
fn pantalla_estado() -> serde_json::Value {
    let mut p = PANTALLA.lock().unwrap();
    let viva = p.hijo.as_mut().map(|h| matches!(h.try_wait(), Ok(None))).unwrap_or(false);
    if !viva && p.hijo.is_some() { apagar_todo(&mut p); }
    serde_json::json!({ "activa": viva, "url": p.url, "error": p.error, "info": p.info, "control": p.control })
}

// BlackHole es el micrófono virtual que hace falta para usar el micrófono de la tablet en el Mac
#[tauri::command]
fn hay_blackhole() -> bool {
    ["/Library/Audio/Plug-Ins/HAL/BlackHole2ch.driver", "/Library/Audio/Plug-Ins/HAL/BlackHole16ch.driver"]
        .iter()
        .any(|p| std::path::Path::new(p).exists())
}

#[tauri::command]
async fn instalar_app_tablet() -> Result<(), String> {
    if adb(&["get-state"]).as_deref() != Ok("device") { return Err("Conecta la tablet por cable.".into()); }
    let apk = recurso("screenmt-tablet.apk");
    adb(&["install", "-r", &apk.to_string_lossy()]).map(|_| ()).map_err(|e| format!("No pude instalarla: {}", e))
}

#[tauri::command]
fn abrir_permiso(cual: String) {
    let url = match cual.as_str() {
        "control" => "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
        "pantallas" => "x-apple.systempreferences:com.apple.Displays-Settings.extension",
        _ => "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture",
    };
    let _ = Command::new("open").arg(url).status();
}

fn main() {
    std::thread::spawn(escuchar_archivos);
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .invoke_handler(tauri::generate_handler![
            estado, ver_tablet, listar, enviar, traer, abrir_carpeta, fijar_destino,
            pantalla_encender, pantalla_apagar, pantalla_estado, abrir_permiso, instalar_app_tablet, hay_blackhole, olvidar_tabletas
        ])
        .build(tauri::generate_context!())
        .expect("no pudo arrancar ScreenMT")
        .run(|_, ev| {
            // al cerrar ScreenMT se apaga la segunda pantalla (y desaparece la pantalla virtual)
            if let tauri::RunEvent::Exit = ev { apagar_todo(&mut PANTALLA.lock().unwrap()); }
        });
}
