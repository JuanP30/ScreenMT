// Sonido entre el Mac y la tablet.
//
// Bocinas: ScreenCaptureKit entrega el sonido del Mac (48 kHz, estéreo, float). Se pasa a PCM de 16 bits
// intercalado y va a la tablet en paquetes tipo 4. Mientras suena en la tablet, el Mac se silencia
// (y se le devuelve el volumen al apagar).
//
// Micrófono: la tablet manda PCM de 16 bits, mono, 48 kHz (paquetes tipo 12). macOS no deja crear un
// micrófono desde una app, así que se reproduce en BlackHole (driver libre de micrófono virtual): en Zoom,
// Claude, etc. se elige «BlackHole 2ch» como micrófono. Sin BlackHole se avisa a la tablet.
import AVFoundation
import CoreAudio
import CoreMedia

/* ---------- bocinas: Mac → tablet ---------- */

// float de ScreenCaptureKit (normalmente un búfer por canal) → Int16 intercalado
func pcmTablet(_ sb: CMSampleBuffer) -> Data? {
    // primero se pregunta cuánto ocupa la lista: con dos canales no cabe en un AudioBufferList simple
    var tam = 0
    CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sb, bufferListSizeNeededOut: &tam, bufferListOut: nil, bufferListSize: 0,
                                                            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
    guard tam > 0 else { return nil }
    let crudo = UnsafeMutableRawPointer.allocate(byteCount: tam, alignment: 16)
    defer { crudo.deallocate() }
    let lista = crudo.bindMemory(to: AudioBufferList.self, capacity: 1)
    var bloque: CMBlockBuffer?
    guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sb, bufferListSizeNeededOut: nil, bufferListOut: lista, bufferListSize: tam,
                                                                  blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
                                                                  flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
                                                                  blockBufferOut: &bloque) == noErr else { return nil }
    let bufs = UnsafeMutableAudioBufferListPointer(lista)
    guard let primero = bufs.first, let d0 = primero.mData else { return nil }
    let canalesEnUno = Int(max(1, primero.mNumberChannels))  // 2 = intercalado en un solo búfer
    let muestras = Int(primero.mDataByteSize) / 4 / canalesEnUno
    let a = d0.assumingMemoryBound(to: Float.self)
    let b: UnsafeMutablePointer<Float>? = bufs.count > 1 ? bufs[1].mData?.assumingMemoryBound(to: Float.self) : nil
    var out = Data(count: muestras * 4)
    out.withUnsafeMutableBytes { (p: UnsafeMutableRawBufferPointer) in
        let o = p.bindMemory(to: Int16.self)
        for i in 0..<muestras {
            let izq = canalesEnUno >= 2 ? a[i * canalesEnUno] : a[i]
            let der = canalesEnUno >= 2 ? a[i * canalesEnUno + 1] : (b?[i] ?? a[i])
            o[2 * i] = Int16(max(-1, min(1, izq)) * 32767)
            o[2 * i + 1] = Int16(max(-1, min(1, der)) * 32767)
        }
    }
    return out
}

// silenciar la salida del Mac mientras suena en la tablet; se recuerda cómo estaba para devolverlo
var muteAntes: UInt32?
func salidaPorDefecto() -> AudioDeviceID? {
    var id = AudioDeviceID(0)
    var t = UInt32(MemoryLayout<AudioDeviceID>.size)
    var dir = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    return AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &dir, 0, nil, &t, &id) == noErr ? id : nil
}
func silenciarMac(_ si: Bool) {
    guard let dev = salidaPorDefecto() else { return }
    var dir = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyMute, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
    guard AudioObjectHasProperty(dev, &dir) else { return }
    var t = UInt32(MemoryLayout<UInt32>.size)
    if si {
        var actual: UInt32 = 0
        AudioObjectGetPropertyData(dev, &dir, 0, nil, &t, &actual)
        if muteAntes == nil { muteAntes = actual }
        var uno: UInt32 = 1
        AudioObjectSetPropertyData(dev, &dir, 0, nil, t, &uno)
    } else if var antes = muteAntes {
        AudioObjectSetPropertyData(dev, &dir, 0, nil, t, &antes)
        muteAntes = nil
    }
}

/* ---------- micrófono: tablet → Mac (por BlackHole) ---------- */

func dispositivoLlamado(_ parte: String) -> AudioDeviceID? {
    var dir = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var t: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &dir, 0, nil, &t) == noErr else { return nil }
    var ids = [AudioDeviceID](repeating: 0, count: Int(t) / MemoryLayout<AudioDeviceID>.size)
    guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &dir, 0, nil, &t, &ids) == noErr else { return nil }
    for id in ids {
        var nd = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var nombre: Unmanaged<CFString>?
        var tn = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        if AudioObjectGetPropertyData(id, &nd, 0, nil, &tn, &nombre) == noErr, let n = nombre?.takeRetainedValue() as String?, n.contains(parte) { return id }
    }
    return nil
}

final class Microfono {
    private let motor = AVAudioEngine()
    private let reproductor = AVAudioPlayerNode()
    private let formato = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
    private(set) var activo = false

    // false si no hay BlackHole
    func encender() -> Bool {
        if activo { return true }
        guard var dev = dispositivoLlamado("BlackHole"), let au = motor.outputNode.audioUnit else { return false }
        AudioUnitSetProperty(au, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &dev, UInt32(MemoryLayout<AudioDeviceID>.size))
        motor.attach(reproductor)
        motor.connect(reproductor, to: motor.mainMixerNode, format: formato)
        do { try motor.start() } catch { return false }
        reproductor.play()
        activo = true
        return true
    }

    func apagar() {
        guard activo else { return }
        reproductor.stop()
        motor.stop()
        activo = false
    }

    // PCM 16 bits mono de la tablet → al micrófono virtual
    func sonar(_ d: Data) {
        guard activo else { return }
        let n = d.count / 2
        guard n > 0, let b = AVAudioPCMBuffer(pcmFormat: formato, frameCapacity: AVAudioFrameCount(n)) else { return }
        b.frameLength = AVAudioFrameCount(n)
        let o = b.floatChannelData![0]
        d.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            let s = p.bindMemory(to: Int16.self)
            for i in 0..<n { o[i] = Float(s[i]) / 32768 }
        }
        reproductor.scheduleBuffer(b, completionHandler: nil)
    }
}
