package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


class EglRenderer(private val frameDrawer: VideoFrameDrawer = VideoFrameDrawer()) {
    private var logger: Logger? = null
    private val TAG = "EglRenderer"

    interface FrameListener {
        fun onFrame(frame: Bitmap?)
    }

    /** Callback for clients to be notified about errors encountered during rendering.  */
    interface ErrorCallback {
        /** Called if GLES20.GL_OUT_OF_MEMORY is encountered during rendering.  */
        fun onGlOutOfMemory()
    }

    private class FrameListenerAndParams(
        val listener: FrameListener,
        val scale: Float,
        val drawer: GlDrawer,
        val applyFpsReduction: Boolean
    )

    // |renderThreadHandler| is a handler for communicating with |renderThread|, and is synchronized
    // on |handlerLock|.
    private val handlerLock = Any()

    private var handler: Handler? = null

    // Variables for fps reduction.
    private val fpsReductionLock = Any()

    // Time for when next frame should be rendered.
    private var nextFrameTimeNs: Long = 0

    // Minimum duration between frames when fps reduction is active, or -1 if video is completely
    // paused.
    private val minRenderPeriodNs: Long = 0

    // EGL and GL resources for drawing YUV/OES textures. After initialization, these are only
    // accessed from the render thread.
    private var eglCore: EglCore? = null
    private val surface: Any? = null

    private lateinit var drawer: GlDrawer
    private var usePresentationTimeStamp = false
    private val drawMatrix: Matrix = Matrix()

    // Pending frame to render. Serves as a queue with size 1. Synchronized on |frameLock|.
    private val frameLock = Any()

    private var pendingFrame: VideoFrame? = null

    // These variables are synchronized on |layoutLock|.
    private val layoutLock = Any()
    private var layoutAspectRatio = 0f

    // If true, mirrors the video stream horizontally.
    private val mirrorHorizontally = false

    // If true, mirrors the video stream vertically.
    private val mirrorVertically = false

    /**
     * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
     * for drawing frames on the EGLSurface. This class is responsible for calling release() on
     * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
     * init()/release() cycle. If usePresentationTimeStamp is true, eglPresentationTimeANDROID will be
     * set with the frame timestamps, which specifies desired presentation time and might be useful
     * for e.g. syncing audio and video.
     */
    fun init(
        eglContext: EGLContext = EGL14.EGL_NO_CONTEXT,
        drawer: GlDrawer,
        usePresentationTimeStamp: Boolean,
        logger: Logger
    ) {
        val thread = HandlerThread("SurfaceTextureVideoSource")
        thread.start()
        this.handler = Handler(thread.looper)

        this.drawer = drawer
        this.usePresentationTimeStamp = usePresentationTimeStamp
        this.logger = logger

        val handler = this.handler ?: throw UnknownError("No handler in init")
        // Create EGL context on the newly created render thread. It should be possibly to create the
        // context on this thread and make it current on the render thread, but this causes failure on
        // some Marvel based JB devices. https://bugs.chromium.org/p/webrtc/issues/detail?id=6350.
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                EglCore(
                    eglContext,
                    logger = logger
                )
            surface?.let { createEglSurfaceInternal(it) }
        }
        this.logger?.info(TAG, "Renderer initialized")
    }

    /**
     * Same as above with usePresentationTimeStamp set to false.
     *
     * @see .init
     */
    fun init(
        eglContext: EGLContext = EGL14.EGL_NO_CONTEXT,
        drawer: GlDrawer,
        logger: Logger
    ) {
        init(eglContext, drawer,  /* usePresentationTimeStamp= */false, logger)
    }

    fun createEglSurface(surface: Surface) {
        createEglSurfaceInternal(surface)
    }

    fun createEglSurface(surfaceTexture: SurfaceTexture) {
        createEglSurfaceInternal(surfaceTexture)
    }

    private fun createEglSurfaceInternal(surface: Any) {
        val handler = this.handler ?: throw UnknownError("No handler in call to create EGL Surface")
        handler.post {
            if (eglCore != null && eglCore?.hasSurface() == false) {
                eglCore?.createWindowSurface(surface)
                eglCore?.makeCurrent()

                /* clear the color buffer */
                GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                eglCore?.swapBuffers()


                // Necessary for YUV frames with odd width.
                // GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                logger?.info(TAG, "Created window surface for EGLRenderer")

            }
        }
    }

    fun releaseEglSurface() {
        val handler = this.handler ?: return
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL surface")

            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface()
        }
    }

    fun render(frame: VideoFrame) {
        synchronized(frameLock) {
            if (pendingFrame != null) {
                pendingFrame?.release()
            }
            pendingFrame = frame
            pendingFrame?.retain()
            val handler = handler ?: throw UnknownError("No handler in render function")
            handler.post(::renderFrameOnRenderThread)
        }
    }

    fun setLayoutAspectRatio(layoutAspectRatio: Float) {
        synchronized(layoutLock) { this.layoutAspectRatio = layoutAspectRatio }
    }
    /**
     * Block until any pending frame is returned and all GL resources released, even if an interrupt
     * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
     * should be called before the Activity is destroyed and the EGLContext is still valid. If you
     * don't call this function, the GL resources might leak.
     */
    fun release() {
        val handler = handler ?: throw UnknownError("No handler in release")
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL resources")
            // Detach current shader program.
            GLES20.glUseProgram( /* program= */0)
            frameDrawer.release()

            eglCore?.makeNothingCurrent()
            eglCore?.release()
            eglCore = null
        }
        val renderLooper: Looper = handler.looper
        renderLooper.quitSafely()
        this.handler = null

        synchronized (frameLock) {
            if (pendingFrame != null) {
                pendingFrame?.release();
                pendingFrame = null;
            }
        }
    }

    private fun renderFrameOnRenderThread() {
        // Fetch and render |pendingFrame|.
        var frame: VideoFrame
        synchronized(frameLock) {
            if (pendingFrame == null) {
                return
            }
            frame = pendingFrame as VideoFrame
            pendingFrame = null
        }
        // Check if fps reduction is active.
        var shouldRenderFrame: Boolean
        synchronized(fpsReductionLock) {
            if (minRenderPeriodNs == Long.MAX_VALUE) {
                // Rendering is paused.
                shouldRenderFrame = false
            } else if (minRenderPeriodNs <= 0) {
                // FPS reduction is disabled.
                shouldRenderFrame = true
            } else {
                val currentTimeNs = System.nanoTime()
                if (currentTimeNs < nextFrameTimeNs) {
                    shouldRenderFrame = false
                } else {
                    nextFrameTimeNs += minRenderPeriodNs
                    // The time for the next frame should always be in the future.
                    nextFrameTimeNs = Math.max(nextFrameTimeNs, currentTimeNs)
                    shouldRenderFrame = true
                }
            }
        }
        val startTimeNs = System.nanoTime()
        val frameAspectRatio =
            frame.getRotatedWidth() / frame.getRotatedHeight().toFloat()
        var drawnAspectRatio: Float
        synchronized(
            layoutLock
        ) {
            drawnAspectRatio =
                if (layoutAspectRatio != 0f) layoutAspectRatio else frameAspectRatio
        }
        val scaleX: Float
        val scaleY: Float
        if (frameAspectRatio > drawnAspectRatio) {
            scaleX = drawnAspectRatio / frameAspectRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = frameAspectRatio / drawnAspectRatio
        }
        drawMatrix.reset()
        drawMatrix.preTranslate(0.5f, 0.5f)
        drawMatrix.preScale(if (mirrorHorizontally) -1f else 1f, if (mirrorVertically) -1f else 1f)
        drawMatrix.preScale(scaleX, scaleY)
        drawMatrix.preTranslate(-0.5f, -0.5f)
        if (shouldRenderFrame) {
            GLES20.glClearColor(1f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            eglCore?.let {
                frameDrawer.drawFrame(
                    frame, drawer, drawMatrix, 0 /* viewportX */, 0 /* viewportY */,
                    it.surfaceWidth(), it.surfaceHeight()
                )
                val swapBuffersStartTimeNs = System.nanoTime()
                if (usePresentationTimeStamp) {
//                    it.swapBuffers(frame.getTimestampNs())
                } else {
                    it.swapBuffers()
                }
            }
        }
        frame.release()
    }
}