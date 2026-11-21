package com.betslippro.app

import kotlin.math.exp

/**
 * Score-line distribution: independent Poisson goals with the Dixon-Coles low-score correction.
 * The correction (rho < 0) fixes the well-known under-estimation of 0-0 and 1-1 draws by plain Poisson.
 * Mirrors tools/model.py, which is used to validate/tune the constants with a walk-forward backtest.
 */
object ScoreModel {
    const val MAX_GOALS = 10
    const val DEFAULT_RHO = -0.10

    fun poisson(lambda: Double): DoubleArray {
        val p = DoubleArray(MAX_GOALS + 1)
        p[0] = exp(-lambda)
        for (i in 1..MAX_GOALS) p[i] = p[i - 1] * lambda / i
        val sum = p.sum().coerceAtLeast(1e-12)
        for (i in p.indices) p[i] /= sum
        return p
    }

    private fun tau(h: Int, a: Int, lh: Double, la: Double, rho: Double): Double = when {
        h == 0 && a == 0 -> 1.0 - lh * la * rho
        h == 0 && a == 1 -> 1.0 + lh * rho
        h == 1 && a == 0 -> 1.0 + la * rho
        h == 1 && a == 1 -> 1.0 - rho
        else -> 1.0
    }

    /** Normalised (MAX_GOALS+1)^2 matrix: matrix[h][a] = P(home scores h, away scores a). */
    fun matrix(lambdaHome: Double, lambdaAway: Double, rho: Double = DEFAULT_RHO): Array<DoubleArray> {
        require(lambdaHome.isFinite() && lambdaAway.isFinite() && lambdaHome > 0 && lambdaAway > 0)
        val r = rho.coerceIn(-0.3, 0.3)
        val ph = poisson(lambdaHome)
        val pa = poisson(lambdaAway)
        val grid = Array(MAX_GOALS + 1) { h ->
            DoubleArray(MAX_GOALS + 1) { a -> ph[h] * pa[a] * tau(h, a, lambdaHome, lambdaAway, r).coerceAtLeast(0.0) }
        }
        val total = grid.sumOf { it.sum() }.coerceAtLeast(1e-12)
        for (h in grid.indices) for (a in grid[h].indices) grid[h][a] /= total
        return grid
    }
}
