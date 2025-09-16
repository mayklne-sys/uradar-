package com.example.audioradar
import kotlin.math.*

class FftCorrelator(
    private val fs: Int,
    template: ShortArray,
    private val maxLag: Int,
    private val minLagMs: Double = 3.0
) {
    private val n: Int
    private val Hre: DoubleArray
    private val Him: DoubleArray
    private val tplEnergy: Double
    private val re: DoubleArray
    private val im: DoubleArray
    private val corr: DoubleArray
    private val minLag: Int

    init {
        val tpl = template.map { it.toDouble() }.toDoubleArray()
        tplEnergy = max(1e-9, tpl.sumOf { it * it })
        var nPow2 = 1
        while (nPow2 < template.size + maxLag) nPow2 = nPow2 shl 1
        n = nPow2
        val tre = DoubleArray(n); val tim = DoubleArray(n)
        System.arraycopy(tpl, 0, tre, 0, tpl.size)
        FFT.fft(tre, tim)
        Hre = DoubleArray(n) { tre[it] }
        Him = DoubleArray(n) { -tim[it] }
        re = DoubleArray(n); im = DoubleArray(n); corr = DoubleArray(n)
        minLag = (minLagMs * 1e-3 * fs).toInt().coerceAtLeast(0)
    }

    fun correlateWithTrace(frame: DoubleArray): Triple<Int, Double, DoubleArray> {
        java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
        val m = min(frame.size, n)
        val denom = (m - 1.0).coerceAtLeast(1.0)
        for (i in 0 until m) {
            val w = 0.5 * (1 - cos(2.0 * Math.PI * i / denom))
            re[i] = frame[i] * w
        }
        FFT.fft(re, im)
        for (i in 0 until n) {
            val r = re[i]*Hre[i] - im[i]*Him[i]
            val ii = re[i]*Him[i] + im[i]*Hre[i]
            re[i] = r; im[i] = ii
        }
        FFT.ifft(re, im)
        System.arraycopy(re, 0, corr, 0, n)

        val searchStart = minLag
        val searchEnd = maxLag.coerceAtMost(n - 1)
        var bestLag = searchStart; var best = Double.NEGATIVE_INFINITY
        for (lag in searchStart..searchEnd) {
            val v = corr[lag]
            if (v > best) { best = v; bestLag = lag }
        }
        val frameEnergy = max(1e-9, frame.sumOf { it * it })
        val score = best / sqrt(frameEnergy * tplEnergy)
        return Triple(bestLag, score, corr)
    }

    fun snrDbFromCorr(corr: DoubleArray, peakIdx: Int, guard: Int = 64): Double {
        val peak = kotlin.math.abs(corr[peakIdx]) + 1e-12
        var noiseSum = 0.0; var cnt = 0
        for (i in corr.indices) {
            if (i in (peakIdx-guard)..(peakIdx+guard)) continue
            noiseSum += kotlin.math.abs(corr[i]); cnt++
        }
        val noise = (noiseSum / kotlin.math.max(1, cnt)) + 1e-12
        return 20.0 * kotlin.math.log10(peak / noise)
    }

    fun correlate(frame: DoubleArray): Pair<Int, Double> {
        val (lag, score, _) = correlateWithTrace(frame)
        return lag to score
    }
}
