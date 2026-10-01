package com.chris.sharkhub.ui.overview.live

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper

/**
 * Filament on a TextureView: the engine, renderer, scene, view and camera, plus the swap chain that
 * follows the surface (UiHelper + DisplayHelper — the same plumbing filament-utils' ModelViewer
 * uses; that class can't host two assets or a constrained camera, so this is its surface half
 * without the manipulator). A Choreographer loop runs [onFrame] and then renders; frame times go
 * to logcat under [LiveSupport.TAG] every two seconds or so, for reading off the unit with adb.
 */
internal class FilamentHost(context: Context) {
    val engine: Engine = Engine.create()
    val renderer: Renderer = engine.createRenderer()
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context.applicationContext)
    private var swapChain: SwapChain? = null
    private var attached: TextureView? = null
    private val choreographer = Choreographer.getInstance()
    private var running = false

    /** The viewport in pixels; zero until the surface has a size. */
    var width = 0; private set
    var height = 0; private set
    var onResize: ((Int, Int) -> Unit)? = null
    /** Called every frame before rendering with the Choreographer's frame time (ns). */
    var onFrame: ((Long) -> Unit)? = null

    // frame-time statistics, logged per window of frames
    private var lastFrameNs = 0L
    private var frames = 0
    private var intervalSumNs = 0L
    private var intervalMaxNs = 0L
    private var slowFrames = 0
    private var cpuSumNs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            frame(frameTimeNanos)
        }
    }

    init {
        view.scene = scene
        view.camera = camera
        // transparent where nothing is drawn, so Compose can paint behind as well as over the scene
        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                attached?.display?.let { displayHelper.attach(renderer, it) }
            }
            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait(); swapChain = null }
            }
            override fun onResized(w: Int, h: Int) {
                view.viewport = Viewport(0, 0, w, h)
                width = w; height = h
                onResize?.invoke(w, h)
            }
        }
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.antiAliasing = View.AntiAliasing.FXAA
        // scale the render below 1080p when a frame runs long, rather than drop frames
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = true; quality = View.QualityLevel.LOW; minScale = 0.6f; maxScale = 1f; sharpness = 0.6f
        }
        view.setShadowType(View.ShadowType.PCF)
    }

    /** Render into [tv] from now on (one surface at a time; a previous one is let go). */
    fun attach(tv: TextureView) {
        if (attached === tv) return
        detach(null)
        attached = tv
        tv.isOpaque = false
        uiHelper.attachTo(tv)
        start()
    }

    /** Stop rendering into [tv] (or whatever is attached when null); a view that isn't the current one is ignored. */
    fun detach(tv: TextureView?) {
        if (tv != null && attached !== tv) return
        stop()
        if (attached != null) {
            uiHelper.detach()
            attached = null
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun frame(frameTimeNanos: Long) {
        val t0 = System.nanoTime()
        onFrame?.invoke(frameTimeNanos)
        val sc = swapChain
        if (sc != null && uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
        val cpu = System.nanoTime() - t0
        if (lastFrameNs != 0L) {
            val dt = frameTimeNanos - lastFrameNs
            intervalSumNs += dt
            if (dt > intervalMaxNs) intervalMaxNs = dt
            if (dt > 20_000_000L) slowFrames++
            frames++
            cpuSumNs += cpu
        }
        lastFrameNs = frameTimeNanos
        if (frames >= 120) {
            Log.i(LiveSupport.TAG, "frame %.1f ms avg · %.1f ms max · %d/%d over 20 ms · cpu %.1f ms/frame · %dx%d".format(
                intervalSumNs / 1e6 / frames, intervalMaxNs / 1e6, slowFrames, frames, cpuSumNs / 1e6 / frames, width, height))
            frames = 0; intervalSumNs = 0L; intervalMaxNs = 0L; slowFrames = 0; cpuSumNs = 0L
        }
    }

    fun destroy() {
        detach(null)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
