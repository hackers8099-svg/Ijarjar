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
    private var sunEntity = 0

    /** Light on the model: brightness, soft light from all sides, where the main light comes from, and its size. */
    class Light(val power: Float = 1f, val ambient: Float = 1f, val azimuth: Float = -35f, val elevation: Float = 45f, val size: Float = 1f)

    private fun applyLight(lt: Light) {
        val e = engine ?: return
        val lm = e.lightManager
        val inst = lm.getInstance(sunEntity)
        if (inst != 0) {
            val az = Math.toRadians(lt.azimuth.toDouble()); val el = Math.toRadians(lt.elevation.toDouble())
            // direction the light travels: from (az, el) towards the model
            val dx = -(Math.cos(el) * Math.sin(az)).toFloat(); val dy = -Math.sin(el).toFloat(); val dz = -(Math.cos(el) * Math.cos(az)).toFloat()
            lm.setDirection(inst, dx, dy, dz)
            lm.setIntensity(inst, 90000f * lt.power.coerceAtLeast(0f))
            runCatching { lm.setSunAngularRadius(inst, (0.5f + lt.size * 2f).coerceIn(0.25f, 20f)); lm.setSunHaloSize(inst, 10f) }
        }
        scene?.indirectLight?.intensity = 28000f * lt.ambient.coerceAtLeast(0f)
    }
    private val textures = HashMap<String, Texture>()
    private var assetLen = -1L
    private var pixBuf: ByteBuffer? = null
    private var rawBmp: Bitmap? = null
    /** UV set used by each material's colour / glow picture in the file (so a new picture lines up the same way). */
    private var matUv: Map<String, Int> = emptyMap()

    private fun sizeOf(context: Context, uri: String): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    /** Shape (width / height) of each material's own picture, so a new picture is fitted, not stretched. */
    private var matAspect: Map<String, Float> = emptyMap()
    /** Materials that light themselves with a picture (phone screens). */
    private var matGlow: Set<String> = emptySet()

    private fun readUvSets(bytes: ByteArray): Map<String, Int> = runCatching {
        val g = so.ijarjar.app.media.GlbEdit.read(bytes) ?: return emptyMap()
        val mats = g.json.optJSONArray("materials") ?: return emptyMap()
        val texs = g.json.optJSONArray("textures")
        val imgs = g.json.optJSONArray("images")
        val views = g.json.optJSONArray("bufferViews")
        val out = HashMap<String, Int>()
        val asp = HashMap<String, Float>()
        val glow = HashSet<String>()
        fun imageAspect(texIndex: Int): Float {
            val src = texs?.optJSONObject(texIndex)?.optInt("source", -1) ?: -1
            val im = imgs?.optJSONObject(src) ?: return 0f
            val v = views?.optJSONObject(im.optInt("bufferView", -1)) ?: return 0f
            val off = v.optInt("byteOffset", 0); val len = v.optInt("byteLength", 0)
            if (off + len > g.bin.size) return 0f
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(g.bin, off, len, o)
            return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0f
        }
        for (i in 0 until mats.length()) {
            val m = mats.getJSONObject(i)
            val name = m.optString("name", "")
            val bt = m.optJSONObject("pbrMetallicRoughness")?.optJSONObject("baseColorTexture")
            val et = m.optJSONObject("emissiveTexture")
            out[name] = bt?.optInt("texCoord", 0) ?: et?.optInt("texCoord", 0) ?: 0
            val t = bt ?: et
            if (t != null) imageAspect(t.optInt("index", -1)).takeIf { it > 0f }?.let { asp[name] = it }
            // glowing picture (real phone models); our own "Screen" is unlit and shows its base picture as it is
            if (et != null) glow.add(name)
            if (et != null) imageAspect(et.optInt("index", -1)).takeIf { it > 0f }?.let { asp[name] = it }
        }
        matAspect = asp; matGlow = glow
        out
    }.getOrDefault(emptyMap())

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

    /** Filament reads texture rows bottom-up: flip pictures so they are the right way up on the model. */
    /** Cuts the middle of a picture to [aspect] (width / height) so it fills a screen without stretching. */
    private fun cover(b: Bitmap, aspect: Float): Bitmap {
        if (aspect <= 0f) return b
        val a = b.width.toFloat() / b.height
        return if (a > aspect) { val w = (b.height * aspect).toInt().coerceAtLeast(1); Bitmap.createBitmap(b, (b.width - w) / 2, 0, w, b.height) }
        else { val h = (b.width / aspect).toInt().coerceAtLeast(1); Bitmap.createBitmap(b, 0, (b.height - h) / 2, b.width, h) }
    }

    private fun upright(b: Bitmap, flipV: Boolean = false, flipH: Boolean = false): Bitmap {
        val src = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        val sy = if (flipV) 1f else -1f
        val sx = if (flipH) -1f else 1f
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { preScale(sx, sy) }, false)
    }

    private fun textureFor(context: Context, uri: String, flipV: Boolean = false, flipH: Boolean = false, aspect: Float = 0f): Texture? {
        val tk = "$uri|$flipV|$flipH|$aspect"
        textures[tk]?.let { return it }
        val e = engine ?: return null
        val bmp = so.ijarjar.app.media.MediaUtils.loadBitmap(context, Uri.parse(uri), 2048) ?: return null
        val src = upright(cover(bmp, aspect), flipV, flipH)
        val tex = Texture.Builder().width(src.width).height(src.height).levels(levelsFor(src.width, src.height))
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(e)
        com.google.android.filament.android.TextureHelper.setBitmap(e, tex, 0, src)
        tex.generateMipmaps(e)
        textures[tk] = tex
        return tex
    }

    /** What a part (material) of the model shows: a picture file, a live bitmap (video frame) and / or a colour. */
    class Look(val material: String, val texUri: String? = null, val bitmap: Bitmap? = null, val bitmapKey: String? = null, val color: Int = 0, val hidden: Boolean = false,
               val flipV: Boolean = false, val flipH: Boolean = false, val aspect: Float = 0f)

    private val dynTex = HashMap<String, Texture>()
    private val dynKeys = HashMap<String, String>()
    private var appliedMats: Set<String> = emptySet()
    private var appliedHighlight: String? = null

    // mipmaps + trilinear + anisotropy: pictures and videos on the model stay sharp and don't shimmer
    private fun sampler() = com.google.android.filament.TextureSampler(
        com.google.android.filament.TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, com.google.android.filament.TextureSampler.MagFilter.LINEAR,
        com.google.android.filament.TextureSampler.WrapMode.CLAMP_TO_EDGE).apply { anisotropy = 8f }

    private fun levelsFor(w: Int, h: Int) = (32 - Integer.numberOfLeadingZeros(maxOf(w, h, 1))).coerceIn(1, 12)

    private fun uploadDyn(material: String, bmp: Bitmap, flipV: Boolean = false, flipH: Boolean = false, aspect: Float = 0f): Texture? {
        val e = engine ?: return null
        val src = upright(cover(bmp, aspect), flipV, flipH)
        var tex = dynTex[material]
        if (tex == null || tex.getWidth(0) != src.width || tex.getHeight(0) != src.height) {
            tex?.let { e.destroyTexture(it) }
            tex = Texture.Builder().width(src.width).height(src.height).levels(levelsFor(src.width, src.height))
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(e)
            dynTex[material] = tex
            dynKeys.remove(material)
        }
        com.google.android.filament.android.TextureHelper.setBitmap(e, tex!!, 0, src)
        tex.generateMipmaps(e)
        return tex
    }

    /**
     * Puts a picture on a material. Works for models made with any tool: turns the texture slot on
     * (ubershader "...Index"), clears a dark base colour, and also lights it up through the emissive
     * slot when the part had a glowing (screen) texture, so phone screens show the new picture.
     */
    private fun setPicture(mi: com.google.android.filament.MaterialInstance, tex: Texture, keepColor: Boolean) {
        val m = mi.material
        val name = mi.name ?: ""
        val uv = matUv[name] ?: 0
        val ident = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val screen = name in matGlow && m.hasParameter("emissiveMap")
        if (screen) {
            // a screen shows the picture exactly: only through its own light, no base colour, no reflections
            // (base + glow together made it twice as bright and the tone curve washed it out)
            runCatching {
                mi.setParameter("emissiveMap", tex, sampler())
                if (m.hasParameter("emissiveIndex")) mi.setParameter("emissiveIndex", uv)
                if (m.hasParameter("emissiveUvMatrix")) mi.setParameter("emissiveUvMatrix", com.google.android.filament.MaterialInstance.FloatElement.MAT3, ident, 0, 1)
                if (m.hasParameter("emissiveFactor")) mi.setParameter("emissiveFactor", 1f, 1f, 1f)
                if (m.hasParameter("emissiveStrength")) mi.setParameter("emissiveStrength", 1f)
            }
            if (m.hasParameter("baseColorIndex")) runCatching { mi.setParameter("baseColorIndex", -1) }
            factor(mi, 0f, 0f, 0f)
            if (m.hasParameter("roughnessFactor")) runCatching { mi.setParameter("roughnessFactor", 1f) }
            if (m.hasParameter("metallicFactor")) runCatching { mi.setParameter("metallicFactor", 0f) }
            if (m.hasParameter("clearCoatFactor")) runCatching { mi.setParameter("clearCoatFactor", 0f) }
            return
        }
        if (m.hasParameter("baseColorMap")) runCatching { mi.setParameter("baseColorMap", tex, sampler()) }
        if (m.hasParameter("baseColorIndex")) runCatching { mi.setParameter("baseColorIndex", uv) }
        if (m.hasParameter("baseColorUvMatrix")) runCatching {
            mi.setParameter("baseColorUvMatrix", com.google.android.filament.MaterialInstance.FloatElement.MAT3, ident, 0, 1)
        }
        if (!keepColor) factor(mi, 1f, 1f, 1f)
        if (m.hasParameter("roughnessFactor")) runCatching { mi.setParameter("roughnessFactor", 0.5f) }
        if (m.hasParameter("metallicFactor")) runCatching { mi.setParameter("metallicFactor", 0f) }
    }

    private fun factor(mi: com.google.android.filament.MaterialInstance, r: Float, g: Float, b: Float) {
        if (mi.material.hasParameter("baseColorFactor")) runCatching { mi.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.SRGB, r, g, b, 1f) }
    }

    /** Applies looks; returns false when the model must be reloaded first (a change was removed). */
    private fun applyLooks(context: Context, looks: List<Look>, highlight: String?): Boolean {
        val mats = looks.map { "${it.material}|${it.texUri}|${it.color}|${it.bitmap != null}|${it.hidden}|${it.flipV}|${it.flipH}|${it.aspect}" }.toSet()
        val staticKey = mats.joinToString(";") + "|hl=$highlight"
        if (staticKey != appliedLook) {
            // something was taken away → start from the original materials
            if (!mats.containsAll(appliedMats) || (appliedHighlight != null && highlight == null)) return false
            appliedLook = staticKey; appliedMats = mats; appliedHighlight = highlight
            dynKeys.clear()
            // hidden parts: draw nothing for their pieces (restored by reloading when shown again)
            val hide = looks.filter { it.hidden }.map { it.material }.toSet()
            if (hide.isNotEmpty()) hideParts(hide)
            for ((_, mi) in materialInstances()) {
                val name = mi.name ?: ""
                if (highlight != null) {
                    if (name == highlight) factor(mi, 1f, 0.15f, 0.65f) else factor(mi, 0.16f, 0.16f, 0.18f)
                    continue
                }
                for (lk in looks) {
                    if (lk.material.isNotEmpty() && lk.material != name) continue
                    // the picture fills the part exactly as the model maps it (no extra cropping)
                    val tex = lk.texUri?.let { textureFor(context, it, lk.flipV, lk.flipH, lk.aspect) }
                    if (tex != null) setPicture(mi, tex, lk.color != 0)
                    if (lk.color != 0) factor(mi, android.graphics.Color.red(lk.color) / 255f, android.graphics.Color.green(lk.color) / 255f, android.graphics.Color.blue(lk.color) / 255f)
                }
            }
        }
        // live pictures (video frames, screenshots)
        for (lk in looks) {
            val bmp = lk.bitmap ?: continue
            val key = (lk.bitmapKey ?: bmp.generationId.toString()) + "|${lk.flipV}|${lk.flipH}"
            if (dynKeys[lk.material] == key) continue
            val tex = uploadDyn(lk.material, bmp, lk.flipV, lk.flipH, lk.aspect) ?: continue
            dynKeys[lk.material] = key
            for ((_, mi) in materialInstances()) {
                if (lk.material.isNotEmpty() && mi.name != lk.material) continue
                setPicture(mi, tex, lk.color != 0)
            }
        }
        return true
    }

    private fun hideParts(names: Set<String>) {
        val e = engine ?: return
        val a = asset ?: return
        val rm = e.renderableManager
        for (ent in a.entities) {
            if (!rm.hasComponent(ent)) continue
            val inst = rm.getInstance(ent)
            for (p in 0 until rm.getPrimitiveCount(inst)) {
                val mi = rm.getMaterialInstanceAt(inst, p)
                if (mi.name in names) runCatching { mi.setColorWrite(false); mi.setDepthWrite(false) }
            }
        }
    }

    /** Small pictures of the model with one part lit up (to see which part is which). */
    fun partPreviews(context: Context, uri: String, names: List<String>, size: Int = 160): Map<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        for (n in names) render(context, uri, size, size, 12f, -25f, emptyList(), n)?.let { out[n] = it }
        return out
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
                // straight colours (no film curve): screenshots and videos on screens look exactly like the file
                runCatching { it.colorGrading = com.google.android.filament.ColorGrading.Builder()
                    .toneMapper(com.google.android.filament.ToneMapper.Linear()).build(e) }
            }
            provider = UbershaderProvider(e)
            loader = AssetLoader(e, provider!!, EntityManager.get())
            resources = ResourceLoader(e)
            // soft light from everywhere + a key light
            val sh = floatArrayOf(0.9f, 0.9f, 0.95f)
            scene!!.indirectLight = IndirectLight.Builder().irradiance(1, sh).intensity(28000f).build(e)
            val sun = EntityManager.get().create()
            sunEntity = sun
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, 0.97f, 0.92f).intensity(90000f).direction(-0.4f, -1f, -0.6f).castShadows(false)
                .build(e, sun)
            scene!!.addEntity(sun)
        } catch (t: Throwable) {
            failed = true
        }
    }

    private fun load(context: Context, uri: String): Boolean {
        if (assetUri == uri && asset != null) {
            val len = sizeOf(context, uri)
            if (len < 0 || len == assetLen) return true
        }
        val e = engine ?: return false
        asset?.let { scene?.removeEntities(it.entities); loader?.destroyAsset(it) }
        asset = null; assetUri = null
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return false
        assetLen = sizeOf(context, uri).let { if (it >= 0) it else bytes.size.toLong() }
        matUv = readUvSets(bytes)
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
        radius = kotlin.math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]).coerceAtLeast(0.0001f)
        appliedLook = ""; appliedMats = emptySet(); appliedHighlight = null; dynKeys.clear()
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

    private fun renderNow(context: Context, uri: String, w: Int, h: Int, rx: Float, ry: Float, looks: List<Look>, highlight: String?, light: Light): Bitmap? {
        ensure()
        applyLight(light)
        val e = engine ?: return null
        if (!load(context, uri)) return null
        if (!applyLooks(context, looks, highlight)) {
            assetUri = null
            if (!load(context, uri)) return null
            applyLooks(context, looks, highlight)
        }
        if (swap == null || swapW != w || swapH != h) {
            swap?.let { e.destroySwapChain(it) }
            swap = e.createSwapChain(w, h, com.google.android.filament.SwapChainFlags.CONFIG_READABLE or com.google.android.filament.SwapChainFlags.CONFIG_TRANSPARENT)
            swapW = w; swapH = h
        }
        val v = view!!; val cam = camera!!; val r = renderer!!
        v.viewport = Viewport(0, 0, w, h)
        // the model fits in a unit sphere; from 4.2 away a 30° view always holds the whole sphere
        cam.setProjection(30.0, w.toDouble() / h, 0.05, 50.0, if (w >= h) Camera.Fov.VERTICAL else Camera.Fov.HORIZONTAL)
        cam.lookAt(0.0, 0.0, 4.2, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val a = asset ?: return null
        val tm = e.transformManager
        tm.setTransform(tm.getInstance(a.root), modelMatrix(rx, ry))
        // reuse the pixel buffer and the raw picture between frames (less memory churn = fewer stutters)
        val pixels = pixBuf?.takeIf { it.capacity() == w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).also { pixBuf = it }
        pixels.clear()
        if (!r.beginFrame(swap!!, 0L)) return null
        r.render(v)
        r.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        r.endFrame()
        e.flushAndWait()
        pixels.rewind()
        val raw = rawBmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rawBmp = it }
        raw.copyPixelsFromBuffer(pixels)
        // GL rows start at the bottom
        val flip = Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
    }

    private class Job(val context: Context, val uri: String, val w: Int, val h: Int, val rx: Float, val ry: Float,
                      val looks: List<Look>, val light: Light, val done: (Bitmap?) -> Unit)
    private val jobs = HashMap<String, Job>()
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    /**
     * Renders in the background and calls [done] on the main thread. Only the newest request per [tag]
     * is drawn, so a fast moving / playing model never piles up work or freezes the screen.
     */
    fun renderAsync(context: Context, tag: String, uri: String, w: Int, h: Int, rx: Float, ry: Float,
                    looks: List<Look>, light: Light, done: (Bitmap?) -> Unit) {
        val hd = synchronized(this) {
            if (handler == null) { val t = HandlerThread("ijarjar-3d").also { it.start() }; thread = t; handler = Handler(t.looper) }
            handler!!
        }
        synchronized(jobs) { jobs[tag] = Job(context.applicationContext, uri, w, h, rx, ry, looks, light, done) }
        hd.post {
            val j = synchronized(jobs) { jobs.remove(tag) } ?: return@post
            val b = try { renderNow(j.context, j.uri, j.w.coerceIn(16, 2048), j.h.coerceIn(16, 2048), j.rx, j.ry, j.looks, null, j.light) } catch (t: Throwable) { null }
            mainHandler.post { j.done(b) }
        }
    }

    /** Renders the model; safe to call from any thread (waits up to 2 s). */
    fun render(context: Context, uri: String, w: Int, h: Int, rx: Float, ry: Float,
               looks: List<Look> = emptyList(), highlight: String? = null, light: Light = Light()): Bitmap? {
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
            out = try { renderNow(context.applicationContext, uri, w.coerceIn(16, 2048), h.coerceIn(16, 2048), rx, ry, looks, highlight, light) } catch (t: Throwable) { null }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return out
    }

    fun available() = !failed
}
