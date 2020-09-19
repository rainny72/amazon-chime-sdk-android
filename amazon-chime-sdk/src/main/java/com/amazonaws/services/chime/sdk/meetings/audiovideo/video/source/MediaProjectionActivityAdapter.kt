package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatActivity


class MediaProjectionActivityAdapter : AppCompatActivity() {
    interface Callback  {
        fun onScreenCaptureActivityResult(resultCode: Int, intent: Intent, metrics: DisplayMetrics)
    }
    companion object {
        lateinit var callback: Callback
    }

    private val REQUEST_CODE_MEDIA_PROJECTION = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_MEDIA_PROJECTION -> {
                if (data != null) {
                    callback.onScreenCaptureActivityResult(resultCode, data, resources.displayMetrics)
                }
                finishAndRemoveTask()
            }
        }
    }

}
