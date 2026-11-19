package com.example.snowfall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Qor, yomg'ir, kuz, momaqaldiroq, bulutlar, qor uyumi va qorbobo chizadigan view.
 * Hech qanday tegishni ushlamaydi (oyna FLAG_NOT_TOUCHABLE bilan qo'shiladi).
 */
class WeatherView(context: Context) : View(context) {

    private class Particle {
        var x = 0f
        var y = 0f
        var size = 0f     // radius (qor, barg) yoki uzunlik (yomg'ir)
        var speed = 0f    // px/s
        var drift = 0f
        var phase = 0f
        var sway = 0f
        var alpha = 0
        var angle = 0f
        var spin = 0f
        var color = Color.WHITE
    }

    private class Cloud {
        var x = 0f
        var y = 0f
        var width = 0f
        var speed = 0f
        var alpha = 0
    }

    private val d = resources.displayMetrics.density

    private val particles = ArrayList<Particle>()
    private val clouds = ArrayList<Cloud>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private var lastNanos = 0L
    private var time = 0f
    private var activeMode = -1

    // ---- Qor uyumi ----
    private val colW = 8f * d
    private val baseHeight = 6f * d
    private var heights = FloatArray(0)
    private var heightSum = 0f
    private var pileMax = 0f

    // ---- Qorbobo holat mashinasi ----
    private var state = ST_ACCUM
    private var stateTime = 0f
    private var manX = 0f

    // ---- Chaqmoq ----
    private var nextBolt = 4f
    private var flash = 0f
    private var boltTime = 0f
    private val boltX = FloatArray(24)
    private val boltY = FloatArray(24)
    private var boltPoints = 0

    // ---- Yomg'ir sachrashi ----
    private val spX = FloatArray(32)
    private val spY = FloatArray(32)
    private val spAge = FloatArray(32) { -1f }
    private var spIdx = 0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ------------------------------------------------------------------
    // O'lcham o'zgarishi (ekran burilishi)
    // ------------------------------------------------------------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        pileMax = h * 0.10f
        rebuildPile(w)

        if (oldw > 0 && oldh > 0) {
            val sx = w.toFloat() / oldw
            val sy = h.toFloat() / oldh
            for (p in particles) {
                p.x *= sx
                p.y *= sy
            }
            for (c in clouds) {
                c.x *= sx
                c.y *= sy
            }
            if (state != ST_ACCUM) resetPile()
        }
    }

    private fun rebuildPile(w: Int) {
        val n = (w / colW).toInt() + 2
        val old = heights
        val fresh = FloatArray(n)
        if (old.isNotEmpty()) {
            for (i in 0 until n) {
                val src = min(old.size - 1, i * old.size / n)
                fresh[i] = min(old[src], pileMax)
            }
        }
        heights = fresh
        heightSum = fresh.sum()
    }

    private fun resetPile() {
        java.util.Arrays.fill(heights, 0f)
        heightSum = 0f
        state = ST_ACCUM
        stateTime = 0f
    }

    // ------------------------------------------------------------------
    // Zarrachalar
    // ------------------------------------------------------------------
    private fun targetCount(): Int {
        val base = when (SnowConfig.mode) {
            SnowConfig.MODE_SNOW -> 90
            SnowConfig.MODE_RAIN -> 160
            SnowConfig.MODE_AUTUMN -> 28
            else -> 220
        }
        return (base * SnowConfig.countPct / 100f).toInt().coerceIn(3, 900)
    }

    private fun syncParticles(w: Int, h: Int) {
        val mode = SnowConfig.mode
        if (mode != activeMode) {
            particles.clear()
            activeMode = mode
            resetPile()
            nextBolt = 2f + Random.nextFloat() * 4f
            flash = 0f
            boltTime = 0f
        }
        val target = targetCount()
        while (particles.size < target) {
            val p = Particle()
            respawn(p, w, h, true)
            particles.add(p)
        }
        while (particles.size > target) {
            particles.removeAt(particles.size - 1)
        }
    }

    /** scatter=false bo'lsa zarracha tepadan (y = -20f) yangi gorizontal koordinata bilan boshlanadi. */
    private fun respawn(p: Particle, w: Int, h: Int, scatter: Boolean) {
        p.x = Random.nextFloat() * w
        p.y = if (scatter) Random.nextFloat() * h else -20f

        when (SnowConfig.mode) {
            SnowConfig.MODE_RAIN, SnowConfig.MODE_STORM -> {
                p.size = (9f + Random.nextFloat() * 14f) * d
                p.speed = (650f + Random.nextFloat() * 350f) * d
                p.drift = 0f
                p.phase = 0f
                p.sway = 0f
                p.alpha = 90 + Random.nextInt(110)
                p.color = 0xFFB3CDE8.toInt()
            }
            SnowConfig.MODE_AUTUMN -> {
                p.size = (5f + Random.nextFloat() * 5f) * d
                p.speed = (45f + Random.nextFloat() * 60f) * d
                p.drift = (20f + Random.nextFloat() * 40f) * d
                p.phase = Random.nextFloat() * TWO_PI
                p.sway = 0.6f + Random.nextFloat() * 1.0f
                p.alpha = 200 + Random.nextInt(55)
                p.angle = Random.nextFloat() * 360f
                p.spin = -120f + Random.nextFloat() * 240f
                p.color = LEAVES[Random.nextInt(LEAVES.size)]
            }
            else -> {
                p.size = (1.5f + Random.nextFloat() * 3.5f) * d
                p.speed = (40f + p.size / d * 18f + Random.nextFloat() * 30f) * d
                p.drift = (6f + Random.nextFloat() * 18f) * d
                p.phase = Random.nextFloat() * TWO_PI
                p.sway = 0.5f + Random.nextFloat() * 1.0f
                p.alpha = 120 + Random.nextInt(120)
                p.color = Color.HSVToColor(floatArrayOf(Random.nextFloat() * 360f, 0.35f, 1f))
            }
        }
    }

    // ------------------------------------------------------------------
    // Bulutlar
    // ------------------------------------------------------------------
    private fun initClouds(w: Int, h: Int) {
        clouds.clear()
        repeat(6) {
            val c = Cloud()
            c.width = (110f + Random.nextFloat() * 120f) * d
            c.x = Random.nextFloat() * w
            c.y = h * (0.03f + Random.nextFloat() * 0.10f)
            c.speed = (5f + Random.nextFloat() * 12f) * d
            c.alpha = 130 + Random.nextInt(70)
            clouds.add(c)
        }
    }

    private fun drawClouds(canvas: Canvas, storm: Boolean) {
        val color = if (storm) 0xFF4A5A66.toInt() else 0xFFD7DFE6.toInt()
        for (c in clouds) {
            val wd = c.width
            canvas.saveLayerAlpha(
                c.x - 10f, c.y - wd * 0.4f, c.x + wd + 10f, c.y + wd * 0.4f, c.alpha
            )
            setFill(color, 255)
            rect.set(c.x, c.y, c.x + wd, c.y + wd * 0.28f)
            canvas.drawRoundRect(rect, wd * 0.14f, wd * 0.14f, paint)
            canvas.drawCircle(c.x + wd * 0.28f, c.y + wd * 0.02f, wd * 0.20f, paint)
            canvas.drawCircle(c.x + wd * 0.52f, c.y - wd * 0.06f, wd * 0.26f, paint)
            canvas.drawCircle(c.x + wd * 0.76f, c.y + wd * 0.03f, wd * 0.17f, paint)
            canvas.restore()
        }
    }

    // ------------------------------------------------------------------
    // Chaqmoq
    // ------------------------------------------------------------------
    private fun updateLightning(dt: Float, w: Int, h: Int) {
        nextBolt -= dt
        if (nextBolt <= 0f) {
            nextBolt = 4f + Random.nextFloat() * 8f
            flash = 1f
            boltTime = 0.28f
            var x = w * (0.15f + Random.nextFloat() * 0.7f)
            var y = h * 0.06f
            boltPoints = 0
            while (boltPoints < boltX.size && y < h * 0.55f) {
                boltX[boltPoints] = x
                boltY[boltPoints] = y
                boltPoints++
                x += (Random.nextFloat() - 0.5f) * 60f * d
                y += (25f + Random.nextFloat() * 35f) * d
            }
        }
        if (flash > 0f) flash = max(0f, flash - dt * 4f)
        if (boltTime > 0f) boltTime -= dt
    }

    private fun drawBolt(canvas: Canvas) {
        if (boltTime <= 0f || boltPoints < 2) return
        path.reset()
        path.moveTo(boltX[0], boltY[0])
        for (i in 1 until boltPoints) path.lineTo(boltX[i], boltY[i])
        setStroke(0xFFBBDEFB.toInt(), 90, 9f * d)
        canvas.drawPath(path, strokePaint)
        setStroke(0xFFFFFFFF.toInt(), 255, 3f * d)
        canvas.drawPath(path, strokePaint)
    }

    // ------------------------------------------------------------------
    // Sachrashlar
    // ------------------------------------------------------------------
    private fun addSplash(x: Float, y: Float) {
        spX[spIdx] = x
        spY[spIdx] = y
        spAge[spIdx] = 0f
        spIdx = (spIdx + 1) % spX.size
    }

    private fun updateAndDrawSplashes(canvas: Canvas, dt: Float) {
        for (i in spX.indices) {
            if (spAge[i] < 0f) continue
            spAge[i] += dt
            val a = spAge[i] / 0.35f
            if (a >= 1f) {
                spAge[i] = -1f
                continue
            }
            val r = (2f + 6f * a) * d
            setStroke(0xFFB3CDE8.toInt(), (180f * (1f - a)).toInt(), 1.2f * d)
            rect.set(spX[i] - r, spY[i] - r * 0.4f, spX[i] + r, spY[i] + r * 0.4f)
            canvas.drawOval(rect, strokePaint)
        }
    }

    // ------------------------------------------------------------------
    // Qor uyumi
    // ------------------------------------------------------------------
    private fun columnOf(x: Float): Int {
        if (x < 0f) return -1
        val c = (x / colW).toInt()
        return if (c >= heights.size) -1 else c
    }

    private fun addSnow(col: Int, amount: Float) {
        val before = heights[col]
        val after = min(pileMax, before + amount)
        heights[col] = after
        heightSum += after - before
        relax(col)
    }

    /** Uyum tik o'smasligi uchun qo'shni ustunlarga yoyiladi (jami balandlik saqlanadi). */
    private fun relax(center: Int) {
        val slope = colW
        val from = max(0, center - 6)
        val to = min(heights.size - 2, center + 6)
        repeat(2) {
            for (i in from..to) {
                val diff = heights[i] - heights[i + 1]
                if (diff > slope) {
                    val m = (diff - slope) / 2f
                    heights[i] -= m
                    heights[i + 1] += m
                } else if (-diff > slope) {
                    val m = (-diff - slope) / 2f
                    heights[i] += m
                    heights[i + 1] -= m
                }
            }
        }
    }

    private fun updatePileState(dt: Float, w: Int) {
        if (heights.isEmpty()) return

        if (state == ST_ACCUM) {
            if (heightSum / heights.size >= pileMax * 0.6f) {
                state = ST_FLATTEN
                stateTime = 0f
                manX = w * (0.25f + Random.nextFloat() * 0.35f)
            }
            return
        }

        stateTime += dt

        if (state == ST_FLATTEN) {
            val k = min(1f, dt * 3f)
            var sum = 0f
            for (i in heights.indices) {
                heights[i] += (baseHeight - heights[i]) * k
                sum += heights[i]
            }
            heightSum = sum
        } else if (state == ST_MELT) {
            val k = min(1f, dt * 1.2f)
            var sum = 0f
            for (i in heights.indices) {
                heights[i] -= heights[i] * k
                sum += heights[i]
            }
            heightSum = sum
        }

        if (stateTime >= DURATIONS[state]) {
            stateTime = 0f
            when (state) {
                ST_FLATTEN -> {
                    java.util.Arrays.fill(heights, baseHeight)
                    heightSum = baseHeight * heights.size
                    state = ST_BALL1
                }
                ST_MELT -> resetPile()
                else -> state++
            }
        }
    }

    private fun drawPile(canvas: Canvas, w: Int, h: Int) {
        if (heights.isEmpty()) return
        if (state == ST_ACCUM && heightSum < 0.5f) return

        val hh = h.toFloat()
        path.reset()
        path.moveTo(-20f, hh + 20f)
        path.lineTo(-20f, hh - heights[0])
        for (i in heights.indices) {
            path.lineTo(i * colW, hh - heights[i])
        }
        path.lineTo(heights.size * colW + 20f, hh - heights[heights.size - 1])
        path.lineTo(heights.size * colW + 20f, hh + 20f)
        path.close()

        val c = if (SnowConfig.colorIdx in 1..3) PALETTE[SnowConfig.colorIdx] else PALETTE[0]
        setFill(c, 240)
        canvas.drawPath(path, paint)
        setStroke(0xFFB7CCE0.toInt(), 200, 1.5f * d)
        canvas.drawPath(path, strokePaint)
    }

    // ------------------------------------------------------------------
    // Qorbobo
    // ------------------------------------------------------------------
    private fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun drawBall(canvas: Canvas, x: Float, y: Float, r: Float, angle: Float, a: Int) {
        setFill(0xFFFFFFFF.toInt(), a)
        canvas.drawCircle(x, y, r, paint)
        // aylanayotganini ko'rsatuvchi donalar
        setFill(0xFFD6E4F0.toInt(), a)
        for (i in 0..2) {
            val an = angle + i * 2.0944f
            canvas.drawCircle(x + cos(an) * r * 0.55f, y + sin(an) * r * 0.55f, r * 0.1f, paint)
        }
        setStroke(0xFF9FB6C9.toInt(), a, 1.6f * d)
        canvas.drawCircle(x, y, r, strokePaint)
    }

    private fun drawSnowman(canvas: Canvas, h: Int) {
        if (heights.isEmpty()) return

        val ci = (manX / colW).toInt().coerceIn(0, heights.size - 1)
        val gy = h - heights[ci] + 3f * d
        val r1 = 34f * d
        val r2 = 25f * d
        val r3 = 18f * d
        val c1y = gy - r1
        val c2y = c1y - r1 - r2 * 0.7f
        val c3y = c2y - r2 - r3 * 0.7f

        val dur = DURATIONS[state]
        val p = if (dur > 0f) (stateTime / dur).coerceIn(0f, 1f) else 0f

        var bodyA = 255
        var squash = 1f
        if (state == ST_MELT) {
            squash = 1f - 0.92f * p
            bodyA = (255f * (1f - p * p)).toInt()
        }

        canvas.save()
        if (squash < 1f) canvas.scale(1f, squash, manX, gy)

        // 1-shar: pastki, yumalab keladi va kattalashadi
        if (state == ST_BALL1) {
            val e = ease(p)
            val r = r1 * (0.35f + 0.65f * e)
            val x = lerp(-r1 * 1.5f, manX, e)
            drawBall(canvas, x, gy - r, r, x / r, bodyA)
        } else if (state > ST_BALL1) {
            drawBall(canvas, manX, c1y, r1, 0.4f, bodyA)
        }

        // 2-shar: o'ng tomondan yumalab keladi, keyin ko'tarilib 1-shar ustiga o'tiradi
        val restX = manX + r1 + r2 + 12f * d
        if (state == ST_BALL2) {
            val e = ease(p)
            val r = r2 * (0.4f + 0.6f * e)
            val x = lerp(width.toFloat() + r2 * 1.5f, restX, e)
            drawBall(canvas, x, gy - r, r, -x / r, bodyA)
        } else if (state == ST_LIFT) {
            val e = ease(p)
            val x = lerp(restX, manX, e)
            val y = lerp(gy - r2, c2y, e) - sin(p * 3.1415927f) * 50f * d
            drawBall(canvas, x, y, r2, -x / r2, bodyA)
        } else if (state > ST_LIFT) {
            drawBall(canvas, manX, c2y, r2, 1.1f, bodyA)
        }

        // 3-shar: bosh, tepadan tushadi
        if (state == ST_HEAD) {
            val y = lerp(-r3 * 2f, c3y, p * p)
            drawBall(canvas, manX, y, r3, 0.8f, bodyA)
        } else if (state > ST_HEAD) {
            drawBall(canvas, manX, c3y, r3, 0.8f, bodyA)
        }

        // Tafsilotlar: ko'z, burun, og'iz, shlyapa, sharf, tugmalar, qo'llar
        if (state >= ST_FACE) {
            val fa = if (state == ST_FACE) p else 1f
            val a = (bodyA * fa).toInt()

            // qo'llar
            setStroke(0xFF5D4037.toInt(), a, 2.5f * d)
            val ay = c2y - 2f * d
            canvas.drawLine(manX - r2 * 0.9f, ay, manX - r2 - 24f * d, ay - 20f * d, strokePaint)
            canvas.drawLine(manX - r2 - 14f * d, ay - 12f * d, manX - r2 - 20f * d, ay - 26f * d, strokePaint)
            canvas.drawLine(manX + r2 * 0.9f, ay, manX + r2 + 24f * d, ay - 20f * d, strokePaint)
            canvas.drawLine(manX + r2 + 14f * d, ay - 12f * d, manX + r2 + 20f * d, ay - 26f * d, strokePaint)

            // tugmalar
            setFill(0xFF263238.toInt(), a)
            canvas.drawCircle(manX, c2y - 9f * d, 2.4f * d, paint)
            canvas.drawCircle(manX, c2y + 1f * d, 2.4f * d, paint)
            canvas.drawCircle(manX, c2y + 11f * d, 2.4f * d, paint)

            // ko'zlar
            canvas.drawCircle(manX - 6f * d, c3y - 4f * d, 2.2f * d, paint)
            canvas.drawCircle(manX + 6f * d, c3y - 4f * d, 2.2f * d, paint)

            // tabassum
            for (i in 0..4) {
                val an = 0.45f + i * 0.56f
                canvas.drawCircle(
                    manX + cos(an) * 9f * d,
                    c3y + 3f * d + sin(an) * 6f * d,
                    1.2f * d,
                    paint
                )
            }

            // sabzi burun
            setFill(0xFFFF7043.toInt(), a)
            path.reset()
            path.moveTo(manX, c3y)
            path.lineTo(manX + 15f * d, c3y + 3f * d)
            path.lineTo(manX, c3y + 5f * d)
            path.close()
            canvas.drawPath(path, paint)

            // sharf
            setFill(0xFFD32F2F.toInt(), a)
            val sy = c3y + r3 * 0.85f
            rect.set(manX - r3 * 0.95f, sy - 3f * d, manX + r3 * 0.95f, sy + 4f * d)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
            rect.set(manX + r3 * 0.3f, sy, manX + r3 * 0.3f + 6f * d, sy + 16f * d)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, paint)

            // shlyapa
            val hy = c3y - r3 * 0.8f
            setFill(0xFF263238.toInt(), a)
            rect.set(manX - r3 * 1.0f, hy, manX + r3 * 1.0f, hy + 4f * d)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, paint)
            rect.set(manX - r3 * 0.62f, hy - 18f * d, manX + r3 * 0.62f, hy + 1f * d)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, paint)
            setFill(0xFFD32F2F.toInt(), a)
            rect.set(manX - r3 * 0.62f, hy - 6f * d, manX + r3 * 0.62f, hy - 1f * d)
            canvas.drawRect(rect, paint)
        }

        canvas.restore()
    }

    // ------------------------------------------------------------------
    // Yordamchi bo'yoq funksiyalari
    // ------------------------------------------------------------------
    private fun setFill(color: Int, alpha: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = alpha.coerceIn(0, 255)
    }

    private fun setStroke(color: Int, alpha: Int, width: Float) {
        strokePaint.color = color
        strokePaint.alpha = alpha.coerceIn(0, 255)
        strokePaint.strokeWidth = width
    }

    // ------------------------------------------------------------------
    // Asosiy chizish sikli
    // ------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f
        else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNanos = now
        time += dt

        syncParticles(w, h)

        val mode = SnowConfig.mode
        val storm = mode == SnowConfig.MODE_STORM
        val sizeMul = SnowConfig.sizePct / 100f
        val speedMul = SnowConfig.speedPct / 100f
        val wind = (SnowConfig.windPct / 100f) * 200f * d + (if (storm) 90f * d else 0f)
        val pileOn = SnowConfig.pile && mode == SnowConfig.MODE_SNOW
        val bottom = h.toFloat()

        // Bulutlar
        if (SnowConfig.clouds || storm) {
            if (clouds.isEmpty()) initClouds(w, h)
            for (c in clouds) {
                c.x += (c.speed + wind * 0.15f) * dt
                if (c.x > w + 20f * d) c.x = -c.width
                else if (c.x + c.width < -60f * d) c.x = w.toFloat()
            }
            drawClouds(canvas, storm)
        } else if (clouds.isNotEmpty()) {
            clouds.clear()
        }

        if (storm) updateLightning(dt, w, h)

        // Qor uyumi holati
        if (pileOn) {
            updatePileState(dt, w)
        } else if (state != ST_ACCUM || heightSum > 0f) {
            resetPile()
        }
        val accumulate = pileOn && state == ST_ACCUM

        // Zarrachalarni yangilash va chizish
        for (p in particles) {
            p.y += p.speed * speedMul * dt
            when (mode) {
                SnowConfig.MODE_AUTUMN -> {
                    p.x += wind * dt * 0.8f
                    p.angle += p.spin * dt
                }
                SnowConfig.MODE_RAIN, SnowConfig.MODE_STORM -> p.x += wind * dt
                else -> p.x += wind * dt * 0.6f
            }
            val margin = 40f * d
            if (p.x > w + margin) p.x = -margin
            else if (p.x < -margin) p.x = w + margin

            when (mode) {
                SnowConfig.MODE_RAIN, SnowConfig.MODE_STORM -> {
                    if (p.y > bottom) {
                        if (Random.nextInt(4) == 0) addSplash(p.x, bottom - 2f * d)
                        respawn(p, w, h, false)
                    }
                    val len = p.size * sizeMul
                    val vx = wind
                    val vy = p.speed * speedMul
                    val norm = sqrt(vx * vx + vy * vy)
                    val ex = p.x - vx / norm * len
                    val ey = p.y - vy / norm * len
                    setStroke(p.color, p.alpha, 1.3f * d * (0.7f + 0.3f * sizeMul))
                    canvas.drawLine(p.x, p.y, ex, ey, strokePaint)
                }

                SnowConfig.MODE_AUTUMN -> {
                    val rad = p.size * sizeMul
                    if (p.y - rad > bottom) respawn(p, w, h, false)
                    val dx = p.x + sin(time * p.sway + p.phase) * p.drift
                    canvas.save()
                    canvas.translate(dx, p.y)
                    canvas.rotate(p.angle)
                    setFill(p.color, p.alpha)
                    rect.set(-rad, -rad * 0.55f, rad, rad * 0.55f)
                    canvas.drawOval(rect, paint)
                    setStroke(0xFF5D4037.toInt(), p.alpha, 1f * d)
                    canvas.drawLine(-rad * 1.2f, 0f, rad * 0.9f, 0f, strokePaint)
                    canvas.restore()
                }

                else -> {
                    val rad = p.size * sizeMul
                    val dx = p.x + sin(time * p.sway + p.phase) * p.drift
                    if (accumulate) {
                        val col = columnOf(dx)
                        if (col >= 0 && p.y + rad * 0.5f >= bottom - heights[col]) {
                            addSnow(col, rad * 0.5f)
                            respawn(p, w, h, false)
                        } else if (p.y - rad > bottom) {
                            respawn(p, w, h, false)
                        }
                    } else if (p.y - rad > bottom) {
                        respawn(p, w, h, false)
                    }
                    val color = if (SnowConfig.colorIdx == 4) p.color
                    else PALETTE[SnowConfig.colorIdx.coerceIn(0, 3)]
                    setFill(color, p.alpha / 4)
                    canvas.drawCircle(dx, p.y, rad * 1.8f, paint)
                    setFill(color, p.alpha)
                    canvas.drawCircle(dx, p.y, rad, paint)
                }
            }
        }

        if (mode == SnowConfig.MODE_RAIN || storm) updateAndDrawSplashes(canvas, dt)

        if (pileOn) {
            drawPile(canvas, w, h)
            if (state >= ST_BALL1) drawSnowman(canvas, h)
        }

        if (storm) {
            if (flash > 0f) {
                setFill(0xFFFFFFFF.toInt(), (flash * 70f).toInt())
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            drawBolt(canvas)
        }

        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastNanos = 0L
    }

    private companion object {
        const val ST_ACCUM = 0
        const val ST_FLATTEN = 1
        const val ST_BALL1 = 2
        const val ST_BALL2 = 3
        const val ST_LIFT = 4
        const val ST_HEAD = 5
        const val ST_FACE = 6
        const val ST_SHOW = 7
        const val ST_MELT = 8

        const val TWO_PI = 6.2831855f

        // Har bir holat davomiyligi (soniya)
        val DURATIONS = floatArrayOf(0f, 1.2f, 2.6f, 2.0f, 1.0f, 0.9f, 1.2f, 12f, 4f)

        val PALETTE = intArrayOf(
            0xFFFFFFFF.toInt(), // oq
            0xFFBFE3FF.toInt(), // ko'k
            0xFFFFC1E3.toInt(), // pushti
            0xFFFFE08A.toInt()  // oltin
        )

        val LEAVES = intArrayOf(
            0xFFD84315.toInt(),
            0xFFEF6C00.toInt(),
            0xFFF9A825.toInt(),
            0xFF8D6E63.toInt(),
            0xFFC62828.toInt()
        )
    }
}
