import Foundation
import ComposeApp
import whisper
import Libavformat
import Libavcodec
import Libavutil
import Libswresample

// SAMplay iOS: "Create with AI" (listening). The video's audio is decoded with FFmpeg (from
// MPVKit), resampled to 16 kHz mono and transcribed with whisper.cpp in ~30 s chunks cut at a
// quiet moment, like the Android version (AudioExtractor + WhisperNative).

private var samplayWhisperCancelled = false
private var samplayWhisperProgress: Int32 = 0

private let whisperAbort: @convention(c) (UnsafeMutableRawPointer?) -> Bool = { _ in samplayWhisperCancelled }
private let ffmpegInterrupt: @convention(c) (UnsafeMutableRawPointer?) -> Int32 = { _ in samplayWhisperCancelled ? 1 : 0 }

final class WhisperSpeechBridge: NSObject, AiSpeechEngineBridge {
    static func install() {
        AiSpeechEngine.shared.bridge = WhisperSpeechBridge()
    }

    private let targetRate = 16_000
    private let chunkSeconds = 30
    private let searchSeconds = 5

    func isSupported() -> Bool { true }

    func cancel() { samplayWhisperCancelled = true }

    func transcribe(
        url: String,
        headers: [String: String],
        modelPath: String,
        language: String,
        onProgress: @escaping (KotlinInt, KotlinBoolean) -> Void,
        onSegment: @escaping (String, String) -> KotlinBoolean,
        isCancelled: @escaping () -> KotlinBoolean
    ) -> String {
        samplayWhisperCancelled = false
        var cparams = whisper_context_default_params()
        cparams.use_gpu = true
        guard let ctx = whisper_init_from_file_with_params(modelPath, cparams) else { return "" }
        defer { whisper_free(ctx) }

        var detected: String? = language == "auto" ? nil : language
        var noAudio = false
        var stopped = false
        let threads = Int32(max(2, min(4, ProcessInfo.processInfo.activeProcessorCount)))

        func runChunk(_ samples: [Float], offsetMs: Int64) -> Bool {
            if samples.isEmpty { return true }
            var params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY)
            params.n_threads = threads
            params.print_progress = false
            params.print_realtime = false
            params.print_special = false
            params.print_timestamps = false
            params.translate = false
            params.no_context = true
            params.suppress_nst = true
            params.abort_callback = whisperAbort
            let lang = detected ?? "auto"
            let rc: Int32 = lang.withCString { cLang in
                params.language = cLang
                params.detect_language = false
                return samples.withUnsafeBufferPointer { buf in
                    whisper_full(ctx, params, buf.baseAddress, Int32(buf.count))
                }
            }
            if rc != 0 { return !samplayWhisperCancelled }
            if detected == nil {
                let id = whisper_full_lang_id(ctx)
                if id >= 0, let s = whisper_lang_str(id) { detected = String(cString: s) }
            }
            let n = whisper_full_n_segments(ctx)
            for i in 0..<n {
                let t0 = whisper_full_get_segment_t0(ctx, i) * 10 + offsetMs
                let t1 = whisper_full_get_segment_t1(ctx, i) * 10 + offsetMs
                guard let c = whisper_full_get_segment_text(ctx, i) else { continue }
                let text = String(cString: c).trimmingCharacters(in: .whitespacesAndNewlines)
                if text.isEmpty { continue }
                if !onSegment(detected ?? "", "\(t0)\t\(t1)\t\(text)").boolValue { return false }
            }
            // End of this chunk: the Kotlin side shows its lines together.
            if !onSegment(detected ?? "", "").boolValue { return false }
            return !isCancelled().boolValue && !samplayWhisperCancelled
        }

        let ok = decode(url: url, headers: headers, isCancelled: isCancelled, onProgress: { pct in
            onProgress(KotlinInt(int: Int32(pct)), KotlinBoolean(bool: false))
        }, noAudio: &noAudio) { samples, offsetMs, pct in
            onProgress(KotlinInt(int: Int32(pct)), KotlinBoolean(bool: true))
            let go = runChunk(samples, offsetMs: offsetMs)
            if !go { stopped = true }
            return go
        }
        if noAudio { return "!noaudio" }
        _ = ok
        _ = stopped
        return detected ?? ""
    }

    /// Index of the quietest 100 ms in the last [searchSeconds] of [buf] (where a chunk is cut).
    private func quietestCut(_ buf: [Float], target: Int) -> Int {
        let window = targetRate / 10
        let start = max(window, target - searchSeconds * targetRate)
        var best = target
        var bestEnergy = Float.greatestFiniteMagnitude
        var i = start
        while i + window <= target {
            var e: Float = 0
            for k in i..<(i + window) { e += buf[k] * buf[k] }
            if e < bestEnergy { bestEnergy = e; best = i + window / 2 }
            i += window / 2
        }
        return best
    }

    /// Decodes the best audio track; calls [onChunk] with ~30 s of 16 kHz mono samples.
    private func decode(
        url: String,
        headers: [String: String],
        isCancelled: @escaping () -> KotlinBoolean,
        onProgress: (Int) -> Void,
        noAudio: inout Bool,
        onChunk: ([Float], Int64, Int) -> Bool
    ) -> Bool {
        var fmt: UnsafeMutablePointer<AVFormatContext>? = avformat_alloc_context()
        guard fmt != nil else { return false }
        fmt!.pointee.interrupt_callback = AVIOInterruptCB(callback: ffmpegInterrupt, opaque: nil)
        var opts: OpaquePointer? = nil
        let headerText = headers.filter { $0.key.lowercased() != "user-agent" }
            .map { "\($0.key): \($0.value)\r\n" }.joined()
        if !headerText.isEmpty { av_dict_set(&opts, "headers", headerText, 0) }
        if let ua = headers.first(where: { $0.key.lowercased() == "user-agent" })?.value {
            av_dict_set(&opts, "user_agent", ua, 0)
        }
        av_dict_set(&opts, "reconnect", "1", 0)
        av_dict_set(&opts, "reconnect_streamed", "1", 0)
        av_dict_set(&opts, "rw_timeout", "30000000", 0)
        let input = url.hasPrefix("file://") ? (URL(string: url)?.path ?? url) : url
        let openRc = avformat_open_input(&fmt, input, nil, &opts)
        av_dict_free(&opts)
        guard openRc >= 0, let f = fmt else { return false }
        defer { avformat_close_input(&fmt) }
        if avformat_find_stream_info(f, nil) < 0 { return false }

        let index = av_find_best_stream(f, AVMEDIA_TYPE_AUDIO, -1, -1, nil, 0)
        if index < 0 { noAudio = true; return false }
        for i in 0..<Int(f.pointee.nb_streams) where i != Int(index) {
            f.pointee.streams[i]?.pointee.discard = AVDISCARD_ALL
        }
        guard let stream = f.pointee.streams[Int(index)],
              let par = stream.pointee.codecpar,
              let codec = avcodec_find_decoder(par.pointee.codec_id) else { noAudio = true; return false }
        var dec: UnsafeMutablePointer<AVCodecContext>? = avcodec_alloc_context3(codec)
        guard let d = dec else { return false }
        defer { avcodec_free_context(&dec) }
        if avcodec_parameters_to_context(d, par) < 0 || avcodec_open2(d, codec, nil) < 0 { noAudio = true; return false }

        var outLayout = AVChannelLayout()
        av_channel_layout_default(&outLayout, 1)
        var swr: OpaquePointer? = nil
        if swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_FLT, Int32(targetRate),
                               &d.pointee.ch_layout, d.pointee.sample_fmt, d.pointee.sample_rate, 0, nil) < 0
            || swr_init(swr) < 0 {
            swr_free(&swr)
            return false
        }
        defer { swr_free(&swr) }

        let durationUs = f.pointee.duration > 0 ? f.pointee.duration : 0
        let timeBase = stream.pointee.time_base
        var packet: UnsafeMutablePointer<AVPacket>? = av_packet_alloc()
        var frame: UnsafeMutablePointer<AVFrame>? = av_frame_alloc()
        defer { av_packet_free(&packet); av_frame_free(&frame) }

        let chunk = chunkSeconds * targetRate
        var buffer: [Float] = []
        buffer.reserveCapacity(chunk + targetRate * 2)
        var emittedSamples: Int64 = 0
        var pct = 0
        var anyAudio = false

        func flushChunk(force: Bool) -> Bool {
            while buffer.count >= chunk || (force && !buffer.isEmpty) {
                let cut = buffer.count >= chunk ? quietestCut(buffer, target: chunk) : buffer.count
                let part = Array(buffer[0..<cut])
                buffer.removeFirst(cut)
                let offsetMs = emittedSamples * 1000 / Int64(targetRate)
                emittedSamples += Int64(cut)
                if !onChunk(part, offsetMs, pct) { return false }
            }
            return true
        }

        func convert(_ fr: UnsafeMutablePointer<AVFrame>?) -> Bool {
            let inCount = fr?.pointee.nb_samples ?? 0
            let capacity = Int(swr_get_out_samples(swr, inCount)) + 256
            var out = [Float](repeating: 0, count: max(capacity, 256))
            let produced: Int32 = out.withUnsafeMutableBytes { raw -> Int32 in
                var outPtr: UnsafeMutablePointer<UInt8>? = raw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                return withUnsafePointer(to: &outPtr) { outPP in
                    if let fr = fr, let ext = fr.pointee.extended_data {
                        let inPP = UnsafeRawPointer(ext).assumingMemoryBound(to: UnsafePointer<UInt8>?.self)
                        return swr_convert(swr, outPP, Int32(out.count), inPP, inCount)
                    }
                    return swr_convert(swr, outPP, Int32(out.count), nil, 0)
                }
            }
            if produced > 0 {
                anyAudio = true
                buffer.append(contentsOf: out[0..<Int(produced)])
            }
            return flushChunk(force: false)
        }

        while !isCancelled().boolValue && !samplayWhisperCancelled {
            let rc = av_read_frame(f, packet)
            if rc < 0 { break }
            defer { av_packet_unref(packet) }
            guard packet!.pointee.stream_index == index else { continue }
            if durationUs > 0, packet!.pointee.pts != Int64.min {
                let posUs = Double(packet!.pointee.pts) * Double(timeBase.num) / Double(timeBase.den) * 1_000_000
                let p = Int(min(99, max(0, posUs * 100 / Double(durationUs))))
                if p != pct { pct = p; if buffer.count < chunk { onProgress(p) } }
            }
            if avcodec_send_packet(d, packet) < 0 { continue }
            while avcodec_receive_frame(d, frame) == 0 {
                if !convert(frame) { return false }
                av_frame_unref(frame)
            }
        }
        if isCancelled().boolValue || samplayWhisperCancelled { return false }
        // Drain the decoder and the resampler.
        _ = avcodec_send_packet(d, nil)
        while avcodec_receive_frame(d, frame) == 0 {
            if !convert(frame) { return false }
            av_frame_unref(frame)
        }
        _ = convert(nil)
        if !anyAudio { noAudio = true; return false }
        pct = 99
        return flushChunk(force: true)
    }
}
