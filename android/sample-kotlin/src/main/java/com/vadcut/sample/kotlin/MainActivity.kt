package com.vadcut.sample.kotlin

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vadcut.android.TrimPreset
import com.vadcut.android.TrimRequest
import com.vadcut.android.TrimTask
import com.vadcut.android.TrimConfig
import com.vadcut.android.VadCut
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var inputUri: Uri? = null
    private var task: TrimTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "选择一段长录音，SDK 将完全在本机删除非语音部分。"
            textSize = 16f
        }
        val choose = Button(this).apply {
            text = "选择录音并处理"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                    },
                    REQUEST_INPUT,
                )
            }
        }
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { task?.cancel() }
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(choose, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(cancel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
    }

    @Deprecated("Sample intentionally uses the compact Activity result API for minSdk 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_INPUT -> {
                inputUri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(inputUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/mp4"
                        putExtra(Intent.EXTRA_TITLE, "vadcut-output.m4a")
                    },
                    REQUEST_OUTPUT,
                )
            }

            REQUEST_OUTPUT -> process(inputUri ?: return, data?.data ?: return)
        }
    }

    private fun process(input: Uri, output: Uri) {
        val request = TrimRequest.Builder(input, output)
            .setConfig(TrimConfig.fromPreset(TrimPreset.VOICE_MEMO))
            .build()
        task = VadCut.with(this).trimAsync(
            request,
            object : com.vadcut.android.TrimListener {
                override fun onProgress(progress: com.vadcut.android.TrimProgress) {
                    status.text = "${progress.phase}: ${progress.percent}%"
                }

                override fun onSuccess(result: com.vadcut.android.TrimResult) {
                    task = null
                    status.text = "完成：删除 ${result.removedDurationMs / 1000.0} 秒，输出 $output"
                }

                override fun onError(error: com.vadcut.android.TrimException) {
                    task = null
                    deleteCreatedOutput(output)
                    status.text = "失败 [${error.code}]：${error.message}"
                }

                override fun onCancelled() {
                    task = null
                    deleteCreatedOutput(output)
                    status.text = "已取消"
                }
            },
        )
    }

    private fun deleteCreatedOutput(output: Uri) {
        // ACTION_CREATE_DOCUMENT created this URI specifically for the current task.
        runCatching {
            when (output.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    if (DocumentsContract.isDocumentUri(this, output)) {
                        DocumentsContract.deleteDocument(contentResolver, output)
                    } else {
                        contentResolver.delete(output, null, null)
                    }
                }
                ContentResolver.SCHEME_FILE -> output.path?.let { File(it).delete() }
            }
        }
    }

    companion object {
        private const val REQUEST_INPUT = 100
        private const val REQUEST_OUTPUT = 101
    }
}
