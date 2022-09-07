package x.stefanji.library

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.GlobalScope
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.launch

class AudioWaveformGenerator(
    private val path: String,
    private val expectPoints: Int
) : MediaCodec.Callback() {
    private val thread = HandlerThread("AudioWave").apply { start() }
    private val handler = Handler(thread.looper)
    private var decoder: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var outputcounter = 0
    private var inputcounter = 0
    private var starttime = 0L

    @Volatile
    private var started = false
    private val finishCount = CountDownLatch(1)

    private var onError: (Exception) -> Unit = {}

    fun startDecode(onError: ((Exception) -> Unit)? = null) {
        onError?.let { this.onError = it }
        sampleData.clear()
        handler.post {
            try {
                val format = getFormat(path) ?: error("Not found audio")
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Not found mime")
                decoder = MediaCodec.createDecoderByType(mime).also {
                    it.configure(format, null, null, 0)
                    it.setCallback(this)
                    starttime = System.currentTimeMillis()
                    it.start()
                }
                started = true
            } catch (e: Exception) {
                finishCount.countDown()
                this.onError(e)
            }
        }

        finishCount.await()
    }

    private fun getFormat(path: String): MediaFormat? {
        val mediaExtractor = MediaExtractor()
        this.extractor = mediaExtractor
        mediaExtractor.setDataSource(path)
        val trackCount = mediaExtractor.trackCount
        repeat(trackCount) {
            val format = mediaExtractor.getTrackFormat(it)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.contains("audio")) {
                durationS = format.getLong(MediaFormat.KEY_DURATION) / 1000000
                mediaExtractor.selectTrack(it)
                return format
            }
        }
        return null
    }

    private var inputEof = false
    private var sampleRate = 0
    private var channels = 1
    private var pcmEncodingBit = 16
    private var totalSamples = 0L
    private var durationS = 0L
    private var perSamplePoints = 0L
    private var barsCount = 0

    override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        if (info.size > 0) {
            //Log.e("somethingcool", "output buffer " + outputcounter + ", start processing: " + (System.currentTimeMillis() - starttime))
            codec.getOutputBuffer(index)?.let { buf ->
                val size = info.size
                buf.position(info.offset)
                when (pcmEncodingBit) {
                    8 -> {
                        handle8bit(size, buf)
                    }
                    16 -> {
                        handle16bit(size, buf)
                    }
                    32 -> {
                        handle32bit(size, buf)
                    }
                }
                //Log.e("somethingcool", "output buffer " + outputcounter + ", finish rms: " + (System.currentTimeMillis() - starttime))
                    codec.releaseOutputBuffer(index, false)
            }
            //Log.e("somethingcool", "output buffer " + outputcounter + ", end processing: " + (System.currentTimeMillis() - starttime))
            outputcounter++
        }

        if (info.isEof()) {
            stop()
        }

    }

    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        if (inputEof) return
        val extractor = extractor ?: return
        //Log.e("somethingcool", "input buffer " + inputcounter + ", start processing: " + (System.currentTimeMillis() - starttime))
        codec.getInputBuffer(index)?.let { buf ->
            var size = extractor.readSampleData(buf, 0)
            if (size > 0) {
                GlobalScope.launch {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                }
                //Log.e("somethingcool", "input buffer " + inputcounter + ", queue buffer: " + (System.currentTimeMillis() - starttime))
                extractor.advance()
                extractor.advance()
            } else {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEof = true
            }
        }
        //Log.e("somethingcool", "input buffer " + inputcounter + ", end processing: " + (System.currentTimeMillis() - starttime))
        inputcounter++
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        pcmEncodingBit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 16
                }
            } else {
                16
            }
        } else {
            16
        }
        totalSamples = sampleRate.toLong() * durationS
        perSamplePoints = totalSamples / expectPoints / 2
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        finishCount.countDown()
        this.onError(e)
    }

    private val sampleData = ArrayList<Float>()
    private var sampleCount = 0L
    private var sampleSum = 0.0
    val sampleDataLiveData: MutableLiveData<ArrayList<Float>>
        get() = MutableLiveData((sampleData))

    private fun calRMS(left: Float) {
        if (sampleCount == perSamplePoints) {
            val rms = sqrt(sampleSum / perSamplePoints) * 2 // 0~1
            sampleData.add(rms.toFloat())
            sampleCount = 0
            sampleSum = 0.0
            barsCount++
            if (barsCount == 10) {
                sampleDataLiveData.postValue(sampleData)
                barsCount = 0
            }
        }

        sampleCount++
        sampleSum += left.toDouble().pow(2.0)
    }

    private fun handle8bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 2 else 1) {
            val left = buf.get().toInt() / 128f
//            if (channels == 2) {
//                buf.get()
//            }
            calRMS(left)
        }
    }

    private fun handle16bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 4 else 2) {
            val a = buf.get().toInt()
            val b = buf.get().toInt() shl 8
            // 16 位采样的范围是: -32768 ~ 32768
            val left = (a or b) / 32768f
            if (channels == 2) {
                buf.get()
                buf.get()
            }
            calRMS(left)
        }
    }

    private fun handle32bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 8 else 4) {
            // 左声道
            val a = buf.get().toLong()
            val b = buf.get().toLong() shl 8
            val c = buf.get().toLong() shl 16
            val d = buf.get().toLong() shl 24
            // 32 位采样的范围是: -2147483648 ~ 2147483648
            val left = (a or b or c or d) / 2147483648f
//            if (channels == 2) {
//                buf.get()
//                buf.get()
//                buf.get()
//                buf.get()
//            }
            calRMS(left)
        }
    }

    private fun stop() {
        if (!started) return
        started = false
        decoder?.stop()
        decoder?.release()
        extractor?.release()
        finishCount.countDown()
    }

    fun getSampleData(): List<Float> = sampleData

    fun cancel() {
        if (!started) return
        handler.post { stop() }
        finishCount.await()
    }
}

fun MediaCodec.BufferInfo.isEof() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
fun MediaCodec.BufferInfo.isConfig() = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
