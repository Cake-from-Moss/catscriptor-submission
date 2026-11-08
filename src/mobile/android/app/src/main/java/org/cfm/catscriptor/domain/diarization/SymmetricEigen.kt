package org.cfm.catscriptor.domain.diarization

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Собственные значения и векторы симметричной матрицы: Хаусхолдер до трёхдиагональной формы
 * и неявный QL (tred2/tql2 из EISPACK в изложении JAMA, общественное достояние).
 * Сложность O(n³), память O(n²); вызывающий код ограничивает n (см. [SpeakerClusteringSettings.maxPoints]).
 *
 * @property values собственные значения по возрастанию.
 * @property vectors `vectors[i][j]` — i-я компонента j-го собственного вектора (столбцы — векторы).
 */
internal class SymmetricEigen(matrix: Array<DoubleArray>) {
    private val n = matrix.size
    val vectors: Array<DoubleArray> = Array(n) { matrix[it].copyOf() }
    val values = DoubleArray(n)
    private val e = DoubleArray(n)

    init {
        require(matrix.all { it.size == n }) { "Матрица должна быть квадратной" }
        if (n > 0) {
            tred2()
            tql2()
        }
    }

    private fun tred2() {
        val v = vectors
        val d = values
        for (j in 0 until n) d[j] = v[n - 1][j]
        for (i in n - 1 downTo 1) {
            var scale = 0.0
            var h = 0.0
            for (k in 0 until i) scale += abs(d[k])
            if (scale == 0.0) {
                e[i] = d[i - 1]
                for (j in 0 until i) {
                    d[j] = v[i - 1][j]
                    v[i][j] = 0.0
                    v[j][i] = 0.0
                }
            } else {
                for (k in 0 until i) {
                    d[k] /= scale
                    h += d[k] * d[k]
                }
                var f = d[i - 1]
                var g = sqrt(h)
                if (f > 0) g = -g
                e[i] = scale * g
                h -= f * g
                d[i - 1] = f - g
                for (j in 0 until i) e[j] = 0.0
                for (j in 0 until i) {
                    f = d[j]
                    v[j][i] = f
                    g = e[j] + v[j][j] * f
                    for (k in j + 1 until i) {
                        g += v[k][j] * d[k]
                        e[k] += v[k][j] * f
                    }
                    e[j] = g
                }
                f = 0.0
                for (j in 0 until i) {
                    e[j] /= h
                    f += e[j] * d[j]
                }
                val hh = f / (h + h)
                for (j in 0 until i) e[j] -= hh * d[j]
                for (j in 0 until i) {
                    f = d[j]
                    g = e[j]
                    for (k in j until i) v[k][j] -= (f * e[k] + g * d[k])
                    d[j] = v[i - 1][j]
                    v[i][j] = 0.0
                }
            }
            d[i] = h
        }
        for (i in 0 until n - 1) {
            v[n - 1][i] = v[i][i]
            v[i][i] = 1.0
            val h = d[i + 1]
            if (h != 0.0) {
                for (k in 0..i) d[k] = v[k][i + 1] / h
                for (j in 0..i) {
                    var g = 0.0
                    for (k in 0..i) g += v[k][i + 1] * v[k][j]
                    for (k in 0..i) v[k][j] -= g * d[k]
                }
            }
            for (k in 0..i) v[k][i + 1] = 0.0
        }
        for (j in 0 until n) {
            d[j] = v[n - 1][j]
            v[n - 1][j] = 0.0
        }
        v[n - 1][n - 1] = 1.0
        e[0] = 0.0
    }

    private fun tql2() {
        val v = vectors
        val d = values
        for (i in 1 until n) e[i - 1] = e[i]
        e[n - 1] = 0.0
        var f = 0.0
        var tst1 = 0.0
        val eps = Math.ulp(1.0)
        for (l in 0 until n) {
            tst1 = maxOf(tst1, abs(d[l]) + abs(e[l]))
            var m = l
            while (m < n) {
                if (abs(e[m]) <= eps * tst1) break
                m++
            }
            if (m > l) {
                var iterations = 0
                do {
                    check(++iterations <= MAX_ITERATIONS) { "QL не сошёлся" }
                    var g = d[l]
                    var p = (d[l + 1] - g) / (2.0 * e[l])
                    var r = hypot(p, 1.0)
                    if (p < 0) r = -r
                    d[l] = e[l] / (p + r)
                    d[l + 1] = e[l] * (p + r)
                    val dl1 = d[l + 1]
                    var h = g - d[l]
                    for (i in l + 2 until n) d[i] -= h
                    f += h
                    p = d[m]
                    var c = 1.0
                    var c2 = c
                    var c3 = c
                    val el1 = e[l + 1]
                    var s = 0.0
                    var s2 = 0.0
                    for (i in m - 1 downTo l) {
                        c3 = c2
                        c2 = c
                        s2 = s
                        g = c * e[i]
                        h = c * p
                        r = hypot(p, e[i])
                        e[i + 1] = s * r
                        s = e[i] / r
                        c = p / r
                        p = c * d[i] - s * g
                        d[i + 1] = h + s * (c * g + s * d[i])
                        for (k in 0 until n) {
                            h = v[k][i + 1]
                            v[k][i + 1] = s * v[k][i] + c * h
                            v[k][i] = c * v[k][i] - s * h
                        }
                    }
                    p = -s * s2 * c3 * el1 * e[l] / dl1
                    e[l] = s * p
                    d[l] = c * p
                } while (abs(e[l]) > eps * tst1)
            }
            d[l] = d[l] + f
            e[l] = 0.0
        }
        // Сортировка по возрастанию вместе со столбцами векторов.
        for (i in 0 until n - 1) {
            var k = i
            var p = d[i]
            for (j in i + 1 until n) if (d[j] < p) { k = j; p = d[j] }
            if (k != i) {
                d[k] = d[i]
                d[i] = p
                for (j in 0 until n) {
                    val t = v[j][i]
                    v[j][i] = v[j][k]
                    v[j][k] = t
                }
            }
        }
    }

    private companion object {
        const val MAX_ITERATIONS = 300
    }
}
