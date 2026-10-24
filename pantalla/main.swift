// screenmt-pantalla: convierte la tablet en una pantalla del Mac.
//
// 1. Crea una pantalla virtual (modo «extra») o toma la pantalla principal (modo «copia»).
// 2. La graba con ScreenCaptureKit y la comprime en H.264 por hardware (VideoToolbox), sin cuadros
//    reordenados, para que la latencia sea mínima.
// 3. La manda por TCP a la app ScreenMT de la tablet, que la decodifica por hardware (MediaCodec).
// 4. Por la misma conexión recibe los toques y los convierte en clics y desplazamientos del Mac.
//
// Por cable escucha solo en 127.0.0.1 y la app del Mac hace `adb reverse`; por wifi escucha en la red
// local y exige la clave aleatoria. Escribe su estado en stdout como líneas JSON.
//
// Protocolo (cada paquete): [u32 largo, big endian][u8 tipo][datos]   (largo = 1 + datos)
//   tablet → Mac   0 hola (la clave, texto)   11 ajuste ("ancho alto fps": lo máximo que decodifica)   10 toque (JSON)
//   Mac → tablet   0 bienvenida ("ancho alto nitidez|fluidez")   1 SPS+PPS (Annex B)   2 cuadro   3 cuadro clave
//
// Cada tablet tiene un tope de decodificación (p. ej. MediaTek: 2560×1440, menos cuadros a más tamaño):
// el Mac propone su tamaño, la tablet responde el mayor que puede mostrar y el Mac se ajusta a eso.
import AppKit
import Network
import ScreenCaptureKit
import VideoToolbox

setvbuf(stdout, nil, _IOLBF, 0)

func decir(_ d: [String: Any]) {
    if let j = try? JSONSerialization.data(withJSONObject: d), let s = String(data: j, encoding: .utf8) { print(s) }
}
func morir(_ msg: String, _ codigo: String = "error") -> Never {
    decir(["error": msg, "codigo": codigo])
    exit(1)
}

/* ---------- opciones ---------- */
var op: [String: String] = ["modo": "extra", "ancho": "2560", "alto": "1600", "calidad": "media", "puerto": "8730", "red": "cable"]
var args = CommandLine.arguments.dropFirst()
while let k = args.popFirst() { if k.hasPrefix("--"), let v = args.popFirst() { op[String(k.dropFirst(2))] = v } }
let modoExtra = op["modo"] == "extra"
let porWifi = op["red"] == "wifi"
let anchoTablet = Int(op["ancho"]!) ?? 2560, altoTablet = Int(op["alto"]!) ?? 1600
let puerto = UInt16(op["puerto"]!) ?? 8730
// escala de la imagen y megabits por segundo de cada nivel (por cable USB 2.0 caben unos 280)
let niveles: [String: (Double, Int)] = ["fluida": (0.5, 8), "media": (0.75, 16), "nitida": (1.0, 30), "maxima": (1.0, 50)]
let (escala, mbps) = niveles[op["calidad"] ?? ""] ?? niveles["nitida"]!
// tamaño de lo que se ve en la pantalla extra: puntos de macOS por píxel de la tablet
//   grande = 1280×800 puntos en una tablet de 2560×1600 · medio = 1600×1000 · maximo = 2560×1600 (letra pequeña)
let espacio = op["espacio"] ?? "grande"
var fps = 60
// con «nítida» y «máxima» manda el tamaño; con «fluida» y «equilibrada», los 60 cuadros por segundo
let preferencia = (op["calidad"] == "fluida" || op["calidad"] == "media") ? "fluidez" : "nitidez"
let clave = porWifi ? (0..<16).map { _ in String("abcdefghjkmnpqrstuvwxyz23456789".randomElement()!) }.joined() : ""

/* ---------- permisos ---------- */
if !CGPreflightScreenCaptureAccess() {
    CGRequestScreenCaptureAccess()
    morir("Falta el permiso de «Grabación de pantalla» para ScreenMT.", "permiso_pantalla")
}
// «--control no»: solo mostrar, sin pedir el permiso de Accesibilidad
let sinControl = op["control"] == "no"
let puedeControlar = !sinControl &&
    AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
// el permiso se puede dar con la segunda pantalla ya encendida: se vuelve a mirar (como mucho cada 2 s)
var controlOk = puedeControlar, controlVisto = Date()
func hayControl() -> Bool {
    if sinControl { return false }
    if !controlOk && Date().timeIntervalSince(controlVisto) > 2 {
        controlVisto = Date()
        controlOk = AXIsProcessTrusted()
        if controlOk { decir(["control": true]) }
    }
    return controlOk
}

/* ---------- la pantalla ---------- */
var virtual: CGVirtualDisplay?  // mientras viva este objeto (y este proceso), existe la pantalla
var idPantalla: CGDirectDisplayID = CGMainDisplayID()
if modoExtra {
    let d = CGVirtualDisplayDescriptor()
    d.queue = DispatchQueue.main
    d.name = "Tablet (ScreenMT)"
    // siempre en modo Retina (macOS ignora los modos sin Retina en pantallas virtuales y baja a la mitad);
    // con más puntos que píxeles de la tablet, la imagen se reduce al grabarla
    let (pw, ph, retina): (Int, Int, Bool) = espacio == "maximo" ? (anchoTablet, altoTablet, true)
        : espacio == "medio" ? (anchoTablet * 5 / 8, altoTablet * 5 / 8, true) : (anchoTablet / 2, altoTablet / 2, true)
    d.maxPixelsWide = UInt32(retina ? pw * 2 : pw)
    d.maxPixelsHigh = UInt32(retina ? ph * 2 : ph)
    d.sizeInMillimeters = CGSize(width: 250, height: 156)  // ~11 pulgadas, solo afecta cómo la nombra macOS
    d.productID = 0x5354
    d.vendorID = 0x4D54
    d.serialNum = 1
    d.terminationHandler = { _, _ in }
    guard let v = CGVirtualDisplay(descriptor: d) else { morir("macOS no dejó crear la pantalla extra. Prueba el modo «copia».") }
    let s = CGVirtualDisplaySettings()
    s.hiDPI = retina ? 1 : 0
    s.modes = [CGVirtualDisplayMode(width: UInt(pw), height: UInt(ph), refreshRate: 60)]
    if !v.apply(s) { morir("macOS no dejó crear la pantalla extra. Prueba el modo «copia».") }
    virtual = v
    idPantalla = v.displayID
}

/* ---------- la tablet conectada (una a la vez: la nueva reemplaza a la anterior) ---------- */
let cola = DispatchQueue(label: "screenmt")
var tablet: NWConnection?
var pendientes = 0        // paquetes saliendo: mientras haya, el siguiente cuadro se salta en vez de amontonarse
var pedirClave = true     // el próximo cuadro debe ser completo (al conectar o tras saltarse uno)
var ancho = 0, alto = 0
var configGrabacion: SCStreamConfiguration?

// la tablet dijo hasta dónde llega: se rehace el compresor y se cambia el tamaño de la grabación
func ajustar(_ w: Int, _ h: Int, _ f: Int) {
    let w = min(w, ancho) & ~1, h = min(h, alto) & ~1, f = max(10, min(60, f))
    guard w >= 64, h >= 64 else { return }
    if w != ancho || h != alto || f != fps, let c = configGrabacion {
        ancho = w; alto = h; fps = f
        if let s = sesion { VTCompressionSessionInvalidate(s) }
        crearCompresor(w, h)
        c.width = w
        c.height = h
        c.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(f))
        stream?.updateConfiguration(c) { _ in }
        ultimoPx = nil  // el último cuadro guardado es del tamaño viejo
    }
    decir(["ajuste": true, "ancho": ancho, "alto": alto, "fps": fps])
}
func paquete(_ tipo: UInt8, _ datos: Data) -> Data {
    var largo = UInt32(datos.count + 1).bigEndian
    var p = Data(bytes: &largo, count: 4)
    p.append(tipo)
    p.append(datos)
    return p
}
func mandar(_ tipo: UInt8, _ datos: Data) {
    guard let c = tablet else { return }
    pendientes += 1
    c.send(content: paquete(tipo, datos), completion: .contentProcessed { err in
        cola.async {
            pendientes -= 1
            if err != nil, tablet === c { tablet = nil; c.cancel() }
            if pedirClave && pendientes == 0 { recomprimir() }  // se saltó un cuadro: manda uno completo de lo último que hay
        }
    })
}

/* ---------- compresión H.264 por hardware ---------- */
var sesion: VTCompressionSession?
var ultimoPx: CVPixelBuffer?
var reloj: Int64 = 0

func crearCompresor(_ w: Int, _ h: Int) {
    let espec = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary
    var s: VTCompressionSession?
    let r = VTCompressionSessionCreate(allocator: nil, width: Int32(w), height: Int32(h), codecType: kCMVideoCodecType_H264,
                                       encoderSpecification: espec, imageBufferAttributes: nil, compressedDataAllocator: nil,
                                       outputCallback: nil, refcon: nil, compressionSessionOut: &s)
    guard r == noErr, let s = s else { morir("No pude preparar el video (VideoToolbox \(r)).") }
    let props: [CFString: Any] = [
        kVTCompressionPropertyKey_RealTime: true,
        kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_High_AutoLevel,
        kVTCompressionPropertyKey_AllowFrameReordering: false,
        kVTCompressionPropertyKey_ExpectedFrameRate: fps,
        kVTCompressionPropertyKey_MaxKeyFrameInterval: fps * 4,
        kVTCompressionPropertyKey_AverageBitRate: mbps * 1_000_000,
    ]
    for (k, v) in props { VTSessionSetProperty(s, key: k, value: v as CFTypeRef) }
    VTCompressionSessionPrepareToEncodeFrames(s)
    sesion = s
}

// AVCC (largo + NAL) → Annex B (00 00 00 01 + NAL), que es lo que entiende MediaCodec
let inicio = Data([0, 0, 0, 1])
func annexB(_ sb: CMSampleBuffer) -> Data? {
    guard let bb = sb.dataBuffer else { return nil }
    var largo = 0
    var ptr: UnsafeMutablePointer<CChar>?
    guard CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &largo, dataPointerOut: &ptr) == noErr, let p = ptr else { return nil }
    var out = Data(capacity: largo + 16)
    var i = 0
    while i + 4 <= largo {
        var n: UInt32 = 0
        memcpy(&n, p + i, 4)
        let tam = Int(UInt32(bigEndian: n))
        i += 4
        guard i + tam <= largo else { break }
        out.append(inicio)
        out.append(Data(bytes: p + i, count: tam))
        i += tam
    }
    return out
}
func parametros(_ sb: CMSampleBuffer) -> Data? {
    guard let fd = sb.formatDescription else { return nil }
    var cuantos = 0
    CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                                                       parameterSetCountOut: &cuantos, nalUnitHeaderLengthOut: nil)
    var out = Data()
    for i in 0..<cuantos {
        var p: UnsafePointer<UInt8>?
        var t = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &p, parameterSetSizeOut: &t,
                                                           parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
        if let p = p { out.append(inicio); out.append(p, count: t) }
    }
    return out
}

func comprimir(_ px: CVPixelBuffer) {
    guard let s = sesion, tablet != nil else { return }
    if pendientes > 0 { pedirClave = true; return }  // la conexión va atrasada: saltar este cuadro
    let clave = pedirClave
    pedirClave = false
    reloj += 1
    let opciones = clave ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
    VTCompressionSessionEncodeFrame(s, imageBuffer: px, presentationTimeStamp: CMTime(value: reloj, timescale: CMTimeScale(fps)),
                                    duration: .invalid, frameProperties: opciones, infoFlagsOut: nil) { estado, _, sb in
        guard estado == noErr, let sb = sb, let datos = annexB(sb) else { return }
        let adj = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]]
        let esClave = !(adj?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        cola.async {
            if esClave, let ps = parametros(sb) { mandar(1, ps) }
            mandar(esClave ? 3 : 2, datos)
        }
    }
}
// la pantalla solo manda cuadros cuando algo cambia: al conectar o tras saltarse uno se repite el último
func recomprimir() { if let px = ultimoPx { comprimir(px) } }

/* ---------- grabar la pantalla ---------- */
final class Grabadora: NSObject, SCStreamOutput, SCStreamDelegate {
    func stream(_ s: SCStream, didOutputSampleBuffer b: CMSampleBuffer, of tipo: SCStreamOutputType) {
        guard tipo == .screen, let px = b.imageBuffer else { return }
        if let info = (CMSampleBufferGetSampleAttachmentsArray(b, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]])?.first,
           let st = info[.status] as? Int, st != SCFrameStatus.complete.rawValue { return }
        cola.async { ultimoPx = px; comprimir(px) }
    }
    func stream(_ s: SCStream, didStopWithError e: Error) { morir("Se detuvo la grabación de la pantalla: \(e.localizedDescription)") }
}
let grabadora = Grabadora()
var stream: SCStream?

func empezarGrabacion(intento: Int = 0) {
    SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { contenido, err in
        // la pantalla virtual tarda un momento en aparecer en la lista
        guard let pantalla = contenido?.displays.first(where: { $0.displayID == idPantalla }) else {
            if intento < 40 { DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { empezarGrabacion(intento: intento + 1) } }
            else { morir("No encontré la pantalla para grabarla. \(err?.localizedDescription ?? "")", err == nil ? "error" : "permiso_pantalla") }
            return
        }
        let modo = CGDisplayCopyDisplayMode(idPantalla)
        let pxAncho = modo?.pixelWidth ?? pantalla.width * 2, pxAlto = modo?.pixelHeight ?? pantalla.height * 2
        // nunca más grande que la tablet; medidas pares, como exige H.264
        let tope = min(1.0, Double(max(anchoTablet, altoTablet)) / Double(max(pxAncho, pxAlto)))
        ancho = Int(Double(pxAncho) * tope * escala) & ~1
        alto = Int(Double(pxAlto) * tope * escala) & ~1
        crearCompresor(ancho, alto)
        let c = SCStreamConfiguration()
        configGrabacion = c
        c.width = ancho
        c.height = alto
        c.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        c.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange  // lo que come VideoToolbox sin convertir
        c.showsCursor = true
        c.queueDepth = 5
        let s = SCStream(filter: SCContentFilter(display: pantalla, excludingWindows: []), configuration: c, delegate: grabadora)
        do { try s.addStreamOutput(grabadora, type: .screen, sampleHandlerQueue: DispatchQueue(label: "cuadros")) } catch { morir("\(error)") }
        s.startCapture { e in
            if let e = e { morir("No pude grabar la pantalla: \(e.localizedDescription)", "permiso_pantalla") }
            stream = s
            decir(["listo": true, "host": porWifi ? (ipLocal() ?? "") : "127.0.0.1", "puerto": Int(puerto), "clave": clave,
                   "control": puedeControlar, "ancho": ancho, "alto": alto])
        }
    }
}

func ipLocal() -> String? {
    var lista: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&lista) == 0, let primero = lista else { return nil }
    defer { freeifaddrs(lista) }
    var r: String?
    for p in sequence(first: primero, next: { $0.pointee.ifa_next }) {
        let a = p.pointee
        guard a.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
        let nombre = String(cString: a.ifa_name)
        guard nombre.hasPrefix("en") else { continue }
        var h = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        getnameinfo(a.ifa_addr, socklen_t(a.ifa_addr.pointee.sa_len), &h, socklen_t(h.count), nil, 0, NI_NUMERICHOST)
        r = String(cString: h)
        if nombre == "en0" { break }
    }
    return r
}

/* ---------- toques de la tablet → ratón del Mac ---------- */
var abajo = false
func punto(_ x: Double, _ y: Double) -> CGPoint {
    let b = CGDisplayBounds(idPantalla)
    return CGPoint(x: b.minX + max(0, min(1, x)) * b.width, y: b.minY + max(0, min(1, y)) * b.height)
}
func evento(_ j: [String: Any]) {
    guard hayControl(), let t = j["t"] as? String else { return }
    let p = punto(j["x"] as? Double ?? 0, j["y"] as? Double ?? 0)
    func raton(_ tipo: CGEventType, _ boton: CGMouseButton = .left) {
        CGEvent(mouseEventSource: nil, mouseType: tipo, mouseCursorPosition: p, mouseButton: boton)?.post(tap: .cghidEventTap)
    }
    switch t {
    case "d": abajo = true; raton(.leftMouseDown)
    case "m": raton(abajo ? .leftMouseDragged : .mouseMoved)
    case "u": abajo = false; raton(.leftMouseUp)
    case "r": raton(.mouseMoved); raton(.rightMouseDown, .right); raton(.rightMouseUp, .right)
    case "s":
        raton(.mouseMoved)
        let dy = Int32(j["dy"] as? Double ?? 0), dx = Int32(j["dx"] as? Double ?? 0)
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?.post(tap: .cghidEventTap)
    default: break
    }
}

/* ---------- servidor ---------- */
func leerPaquete(_ c: NWConnection, _ listo: @escaping (UInt8, Data) -> Void) {
    c.receive(minimumIncompleteLength: 5, maximumLength: 5) { cab, _, _, err in
        guard err == nil, let cab = cab, cab.count == 5 else { c.cancel(); return }
        let largo = Int(cab.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
        let tipo = cab[cab.startIndex + 4]
        guard largo >= 1, largo < 65536 else { c.cancel(); return }
        if largo == 1 { listo(tipo, Data()); return }
        c.receive(minimumIncompleteLength: largo - 1, maximumLength: largo - 1) { datos, _, _, err in
            guard err == nil, let datos = datos else { c.cancel(); return }
            listo(tipo, datos)
        }
    }
}

let params = NWParameters.tcp
if let t = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { t.noDelay = true }
params.allowLocalEndpointReuse = true
// por cable el puerto va en requiredLocalEndpoint (solo 127.0.0.1); repetirlo hace fallar al NWListener
let servidor: NWListener
do {
    if porWifi { servidor = try NWListener(using: params, on: NWEndpoint.Port(rawValue: puerto)!) }
    else {
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: puerto)!)
        servidor = try NWListener(using: params)
    }
} catch { morir("No pude abrir el puerto \(puerto): \(error.localizedDescription)") }
servidor.stateUpdateHandler = { if case .failed(let e) = $0 { morir("El puerto \(puerto) está ocupado (\(e)). Cierra la otra segunda pantalla y vuelve a intentar.") } }
servidor.newConnectionHandler = { c in
    c.start(queue: cola)
    leerPaquete(c) { tipo, datos in
        guard tipo == 0, String(data: datos, encoding: .utf8) == clave else { c.cancel(); return }
        // la tablet recibe cuadros solo después de decir su tope (paquete 11)
        c.send(content: paquete(0, Data("\(ancho) \(alto) \(preferencia)".utf8)), completion: .contentProcessed { _ in })
        func seguir() {
            leerPaquete(c) { tipo, datos in
                if tipo == 10, let j = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] { DispatchQueue.main.async { evento(j) } }
                if tipo == 11 {
                    let n = (String(data: datos, encoding: .utf8) ?? "").split(separator: " ").compactMap { Int($0) }
                    if n.count == 3 { ajustar(n[0], n[1], n[2]) }
                    if tablet !== c { tablet?.cancel() }
                    tablet = c
                    pendientes = 0
                    pedirClave = true
                    recomprimir()
                }
                seguir()
            }
        }
        seguir()
    }
}
servidor.start(queue: cola)

/* ---------- arrancar y quedarse vivo hasta que la app lo cierre ---------- */
signal(SIGTERM) { _ in exit(0) }
signal(SIGINT) { _ in exit(0) }
// si la app que lo abrió se cierra de golpe, stdin se cierra y este proceso también
FileHandle.standardInput.readabilityHandler = { h in if h.availableData.isEmpty { exit(0) } }
empezarGrabacion()
NSApplication.shared.setActivationPolicy(.prohibited)
NSApplication.shared.run()
