package app.lumen.launcher

import android.animation.TimeInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed-form damped spring, normalised so it has settled (to ~0.2%) at the end of the
 * animation's duration. Works with ValueAnimator and ViewPropertyAnimator, so no
 * physics library is needed.
 *
 * dampingRatio < 1 overshoots (bouncy), 1 = critically damped (no overshoot).
 */
class SpringInterpolator(dampingRatio: Float) : TimeInterpolator {
    private val zeta = dampingRatio.toDouble().coerceIn(0.2, 1.0)
    private val omega: Double
    private val omegaD: Double

    init {
        if (zeta >= 0.999) {
            omega = 8.6          // (1 + w) e^-w ≈ 0.002 at t = 1
            omegaD = 0.0
        } else {
            omega = ln(500.0) / zeta
            omegaD = omega * sqrt(1 - zeta * zeta)
        }
    }

    override fun getInterpolation(input: Float): Float {
        if (input >= 1f) return 1f
        if (input <= 0f) return 0f
        val t = input.toDouble()
        val value = if (omegaD == 0.0) {
            1 - (1 + omega * t) * exp(-omega * t)
        } else {
            1 - exp(-zeta * omega * t) * (cos(omegaD * t) + (zeta * omega / omegaD) * sin(omegaD * t))
        }
        return value.toFloat()
    }
}

/** One-shot bump 0 → 1 → 0, used for the "you hit the edge" nudge. */
class BumpInterpolator : TimeInterpolator {
    override fun getInterpolation(input: Float): Float {
        val t = input.coerceIn(0f, 1f).toDouble()
        return (sin(PI * t) * exp(-2.2 * t)).toFloat() * 1.6f
    }
}

object Springs {
    /** Focus lift: a little bounce, like tvOS. */
    val focus = SpringInterpolator(0.62f)
    /** Scrolling and re-ordering: almost no overshoot. */
    val move = SpringInterpolator(0.86f)
    /** Zoom open/close and fades: no overshoot. */
    val smooth = SpringInterpolator(1.0f)
    val bump = BumpInterpolator()
}
