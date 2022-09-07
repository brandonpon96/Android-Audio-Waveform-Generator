package x.stefanji.audiovisulizer

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.stefanji.library.AudioWaveformGenerator
import java.io.File

private const val TAG = "somethingcool"
private const val MP3 = "heal_promo.mp3"
private const val M4a = "test.m4a"
private const val INPUT_FILE = MP3

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val activity = this
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            GlobalScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val starttime = System.currentTimeMillis()
                        Log.e(TAG, "start: $starttime")
                        val file = File(cacheDir, INPUT_FILE)
                        val ins = assets.open(INPUT_FILE)
                        val ous = file.outputStream()
                        val buffer = ByteArray(4096)
                        var len: Int
                        var counter = 0
                        while (ins.read(buffer).also { read -> len = read } != -1) {
                            //Log.e(TAG, "count: $counter, buffer read: $len")
                            counter++
                            ous.write(buffer, 0, len)
                        }
                        ins.close()
                        ous.close()
                        Log.e(TAG, "finish processing: " + (System.currentTimeMillis() - starttime))
                        val decoder = AudioWaveformGenerator(
                            file.absolutePath,
                            100
                        )
//                        decoder.sampleDataLiveData.observe(activity) {
//                            samples -> activity.findViewById<Wave>(R.id.wave).setValues(samples)
//                         }
                        Log.e(TAG, "start decode: " + (System.currentTimeMillis() - starttime))
                        decoder.startDecode()
                        Log.e(TAG, "finish decode: " + (System.currentTimeMillis() - starttime))
                        val samples = decoder.getSampleData()
                        withContext(Dispatchers.Main) {
                            findViewById<Wave>(R.id.wave).setValues(samples)
                        }
                        Log.d(TAG, "onFinish ${samples.size}")

                        Log.e(TAG, "finish drawing: " + (System.currentTimeMillis() - starttime))
                    } catch (e: Exception) {
                        Log.e(TAG, "copy", e)
                    }
                }
            }
        }
    }
}
