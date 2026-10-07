package so.ijarjar.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3D models (.glb) rendered with Google Filament into a transparent bitmap, so they can be used
 * like any other layer (keyframes, 3D rotation, export). All Filament work runs on one thread.
 */
object Model3D {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var loader: AssetLoader? = null
    private var resources: ResourceLoader? = null
    private var provider: UbershaderProvider? = null
    private var swap: SwapChain? = null
    private var swapW = 0
    private var swapH = 0
    private var asset: FilamentAsset? = null
    private var assetUri: String? = null
    private var center = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f
    private var failed = false
    private var appliedLook = ""
    private val textures = HashMap<String, Texture>()

    /** Names of the model's materials (to pick which one gets a new picture). */
    fun materials(context: Context, uri: String): List<String> {
        val hd = synchronized(this) {
            if (handler == null) { val t = HandlerThread("ijarjar-3d").also { it.start() }; thread = t; handler = Handler(t.looper) }
            handler!!
        }
        var out: List<String> = emptyList()
        val latch = CountDownLatch(1)
        hd.post {
            out = try {
                ensure(); if (load(context.applicationContext, uri)) materialInstances().map { it.second.name ?: "?" }.distinct() else emptyList()
            } catch (t: Throwable) { emptyList() }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun materialInstances(): List<Pair<Int, com.google.android.filament.MaterialInstance>> {
        val e = engine ?: return emptyList()
        val a = asset ?: return emptyList()
        val rm = e.renderableManager
        val list = ArrayList<Pair<Int, com.google.android.filament.MaterialInstance>>()
        for (ent in a.entities) {
            if (!rm.hasComponent(ent)) continue
            val inst = rm.getInstance(ent)
            for (p in 0 until rm.getPrimitiveCount(inst)) list.add(Pair(ent, rm.getMaterialInstanceAt(inst, p)))
        }
        return list
    }

    private fun textureFor(context: Context, uri: String): Texture? {
        textures[uri]?.let { return it }
        val e = engine ?: return null
        val bmp = so.ijarjar.app.media.MediaUtils.loadBitmap(context, Uri.parse(uri), 1024) ?: return null
        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val tex = Texture.Builder().width(src.width).height(src.height).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(e)
        com.google.android.filament.android.TextureHelper.setBitmap(e, tex, 0, src)
        textures[uri] = tex
        return tex
    }

    /** New picture on one material (or all), and a colour tint. */
    private fun applyLook(context: Context, texUri: String?, material: String?, color: Int) {
        val look = "$texUri|$material|$color"
        if (look == appliedLook) return
        appliedLook = look
        val tex = texUri?.let { textureFor(context, it) }
        val sampler = com.google.android.filament.TextureSampler(
            com.google.android.filament.TextureSampler.MinFilter.LINEAR, com.google.android.filament.TextureSampler.MagFilter.LINEAR,
            com.google.android.filament.TextureSampler.WrapMode.REPEAT)
        for ((_, mi) in materialInstances()) {
            val mat = mi.material
            val match = material.isNullOrEmpty() || mi.name == material
            if (!match) continue
            if (tex != null && mat.hasParameter("baseColorMap")) runCatching { mi.setParameter("baseColorMap", tex, sampler) }
            if (color != 0 && mat.hasParameter("baseColorFactor")) runCatching {
                mi.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.SRGB,
                    android.graphics.Color.red(color) / 255f, android.graphics.Color.green(color) / 255f, android.graphics.Color.blue(color) / 255f, 1f)
            }
        }
    }

    private fun ensure() {
        if (engine != null || failed) return
        try {
            Gltfio.init()
            val e = Engine.create()
            engine = e
            renderer = e.createRenderer().also {
                it.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = floatArrayOf(0f, 0f, 0f, 0f) }
            }
            scene = e.createScene()
            camera = e.createCamera(EntityManager.get().create()).also { it.setExposure(16f, 1f / 125f, 100f) }
            view = e.createView().also {
                it.scene = scene; it.camera = camera
                it.blendMode = View.BlendMode.TRANSLUCENT
                // smooth edges (no pixel steps)
                it.antiAliasing = View.AntiAliasing.FXAA
                it.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
            }
            provider = UbershaderProvider(e)
            loader = AssetLoader(e, provider!!, EntityManager.get())
            resources = ResourceLoader(e)
            // soft light from everywhere + a key light
            val sh = floatArrayOf(0.9f, 0.9f, 0.95f)
            scene!!.indirectLight = IndirectLight.Builder().irradiance(1, sh).intensity(28000f).build(e)
            val sun = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1f, 0.97f, 0.92f).intensity(90000f).direction(-0.4f, -1f, -0.6f).castShadows(false)
                .build(e, sun)
            scene!!.addEntity(sun)
        } catch (t: Throwable) {
            failed = true
        }
    }

    private fun load(context: Context, uri: String): Boolean {
        if (assetUri == uri && asset != null) return true
        val e = engine ?: return false
        asset?.let { scene?.removeEntities(it.entities); loader?.destroyAsset(it) }
        asset = null; assetUri = null
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return false
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.flip()
        val a = loader?.createAsset(buf) ?: return false
        resources?.loadResources(a)
        a.releaseSourceData()
        scene?.addEntities(a.entities)
        val box = a.boundingBox
        center = box.center
        val h = box.halfExtent
        // bounding sphere: the model never gets cut off, whichever way it is turned
        radius = kotlin.math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]).coerceAtLeast(0.0001f) * 0.92f
        appliedLook = ""
        asset = a; assetUri = uri
        e.flushAndWait()
        return true
    }

    /** Column-major 4x4: rotate Y, then X, around the model centre, scaled to unit size. */
    private fun modelMatrix(rx: Float, ry: Float): FloatArray {
        val m = FloatArray(16)
        android.opengl.Matrix.setIdentityM(m, 0)
        android.opengl.Matrix.rotateM(m, 0, rx, 1f, 0f, 0f)
        android.opengl.Matrix.rotateM(m, 0, ry, 0f, 1f, 0f)
        val s = 1f / radius
        android.opengl.Matrix.scaleM(m, 0, s, s, s)
        android.opengl.Matrix.translateM(m, 0, -center[0], -center[1], -center[2])
        return m
    }

    private fun renderNow(context: Context, uri: String, w: Int, h: Int, rx: Float, ry: Float, tex: String?, mat: String?, color: Int): Bitmap? {
        ensure()
        val e = engine ?: return null
        if (!load(context, uri)) return null
        applyLook(context, tex, mat, color)
        if (swap == null || swapW != w || swapH != h) {
            swap?.let { e.destroySwapChain(it) }
            swap = e.createSwapChain(w, h, com.google.android.filament.SwapChainFlags.CONFIG_READABLE or com.google.android.filament.SwapChainFlags.CONFIG_TRANSPARENT)
            swapW = w; swapH = h
        }
        val v = view!!; val cam = camera!!; val r = renderer!!
        v.viewport = Viewport(0, 0, w, h)
        cam.setProjection(35.0, w.toDouble() / h, 0.05, 50.0, Camera.Fov.VERTICAL)
        cam.lookAt(0.0, 0.0, 3.4, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val a = asset ?: return null
        val tm = e.transformManager
        tm.setTransform(tm.getInstance(a.root), modelMatrix(rx, ry))
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        if (!r.beginFrame(swap!!, 0L)) return null
        r.render(v)
        r.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        r.endFrame()
        e.flushAndWait()
        pixels.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(pixels)
        // GL rows start at the bottom
        val flip = Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
    }

    /** Renders the model; safe to call from any thread (waits up to 2 s). */
    fun render(context: Context, uri: String, w: Int, h: Int, rx: Float, ry: Float,
               tex: String? = null, mat: String? = null, color: Int = 0): Bitmap? {
        val hd = synchronized(this) {
            if (handler == null) {
                val t = HandlerThread("ijarjar-3d").also { it.start() }
                thread = t; handler = Handler(t.looper)
            }
            handler!!
        }
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        hd.post {
            out = try { renderNow(context.applicationContext, uri, w.coerceIn(16, 2048), h.coerceIn(16, 2048), rx, ry, tex, mat, color) } catch (t: Throwable) { null }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return out
    }

    fun available() = !failed
}
