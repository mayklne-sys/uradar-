package com.example.audioradar

import android.Manifest
import android.content.Context
import android.media.*
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.audioradar.databinding.ActivityMainBinding
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    @Volatile private var running = false

    private val fs = 48_000
    private val c = 343.0
    private val maxRangeM = 4.0
    private val maxLag = (2 * maxRangeM / c * fs).toInt()
    private val probe = chirp(fs = fs, durMs = 20, fStart = 18_000.0, fEnd = 16_000.0)

    private lateinit var bpf1: BiquadBandpass
    private lateinit var bpf2: BiquadBandpass
    private lateinit var correlator: FftCorrelator

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted[Manifest.permission.RECORD_AUDIO] == true &&
                 granted[Manifest.permission.CAMERA] == true
        if (ok) startCamera() else Toast.makeText(this, "Нужны разрешения", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        bpf1 = BiquadBandpass(fs, f0 = 18_000.0, q = 6.0)
        bpf2 = BiquadBandpass(fs, f0 = 17_000.0, q = 6.0)
        correlator = FftCorrelator(fs, template = probe, maxLag = maxLag, minLagMs = 3.0)

        requestPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        b.btnStart.setOnClickListener { startRadar() }
        b.btnStop.setOnClickListener { stopRadar() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().apply {
                setSurfaceProvider(b.previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRadar() {
        if (running) return
        running = true
        b.txtStatus.text = "Сканирую…"

        txThread = thread(start = true, name = "tx") {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder()
                    .setSampleRate(fs)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                probe.size * 4, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            val gap = ShortArray(probe.size)
            track.play()
            while (running) {
                track.write(probe, 0, probe.size)
                track.write(gap, 0, gap.size)
            }
            track.stop(); track.release()
        }

        rxThread = thread(start = true, name = "rx") {
            val minBuf = AudioRecord.getMinBufferSize(
                fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            val buf = ShortArray(minBuf)
            rec.startRecording()

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val x1 = bpf1.processBlock(buf)
                    val x2 = DoubleArray(x1.size) { i -> bpf2.process(x1[i]) }

                    val (lag, score, corr) = correlator.correlateWithTrace(x2)
                    val dt = lag.toDouble() / fs
                    val d = 0.5 * c * dt
                    val snrDb = correlator.snrDbFromCorr(corr, peakIdx = lag, guard = 64)

                    val ok = snrDb >= 3.0 && d in 0.1..maxRangeM
                    runOnUiThread {
                        b.txtDistance.text = if (ok)
                            String.format("~%.2f м  (FFT score %.2f, SNR %.1f dB)", d, score, snrDb)
                        else "— м"
                    }
                    if (ok && snrDb > 6.0) vibrate(40)
                }
            }
            rec.stop(); rec.release()
        }
    }

    private fun stopRadar() {
        running = false
        b.txtStatus.text = "Стоп"
    }

    private fun chirp(fs: Int, durMs: Int, fStart: Double, fEnd: Double): ShortArray {
        val n = fs * durMs / 1000
        val out = ShortArray(n)
        val k = (fEnd - fStart) / (durMs / 1000.0)
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            val phase = 2.0 * Math.PI * (fStart * t + 0.5 * k * t * t)
            val s = 0.5 * kotlin.math.sin(phase)
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        txThread?.interrupt(); rxThread?.interrupt()
    }
}
