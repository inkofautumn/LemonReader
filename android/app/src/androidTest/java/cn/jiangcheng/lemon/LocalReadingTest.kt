package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import cn.jiangcheng.lemon.data.LocalProfile
import cn.jiangcheng.lemon.reader.Novel
import cn.jiangcheng.lemon.reader.Chapter
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Reading and first-use OCR work with the device network disabled. */
object LocalReadingTest {
    fun run(test: Instrumentation): String {
        val context = test.targetContext
        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 15000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            check(condition()) { label }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        await("测试设备仍有活动网络") { connectivity.activeNetwork == null }
        @Suppress("DEPRECATION")
        check(context.packageManager.resolveContentProvider("${context.packageName}.mlkitinitprovider", 0) == null)
        val app = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        check(app.profileId == LocalProfile.ID)
        val original = "雨停了，窗边的书页映着午后的光。"
        val book = app.library.import(LocalProfile.ID,
            Novel("窗边小记", listOf(Chapter("午后", original)), "UTF-8"), original.toByteArray(), "窗边小记.txt")
        try {
            test.runOnMainSync { app.openBook(book) }
            await("本地阅读器未完成加载") {
                var ready = false
                test.runOnMainSync { ready = app.reader != null }
                ready
            }
            test.waitForIdleSync()
            check(app.library.get(LocalProfile.ID, book.id)?.chapters?.single()?.text == original)

            val bitmap = Bitmap.createBitmap(1100, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("LOCAL BOOK", 40f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 120f; typeface = Typeface.DEFAULT_BOLD
                })
            }
            MlKit.initialize(context)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
                check(result.text.contains("LOCAL", ignoreCase = true)) { "本地识字结果不完整" }
            } finally {
                recognizer.close(); bitmap.recycle()
            }
        } finally {
            test.runOnMainSync { app.reader?.stop(); app.reader = null; app.finish() }
            app.library.deleteBook(LocalProfile.ID, book)
        }
        return "离线启动、本地正文阅读、OCR 按需初始化与离线识字通过"
    }
}
