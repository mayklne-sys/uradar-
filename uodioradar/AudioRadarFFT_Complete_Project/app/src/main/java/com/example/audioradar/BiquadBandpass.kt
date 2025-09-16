package com.example.audioradar
import kotlin.math.*

class BiquadBandpass(fs: Int, f0: Double, q: Double) {
    private val a0: Double; private val a1: Double; private val a2: Double
    private val b0: Double; private val b1: Double; private val b2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    init {
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosw = cos(w0)
        b0 =   q * alpha
        b1 =   0.0
        b2 =  -q * alpha
        val a0n = 1.0 + alpha
        a1 =  -2.0 * cosw
        a2 =   1.0 - alpha
        a0 = a0n
    }
    fun process(x: Double): Double {
        val y = (b0/a0)*x + (b1/a0)*x1 + (b2/a0)*x2 - (a1/a0)*y1 - (a2/a0)*y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    fun processBlock(input: ShortArray): DoubleArray {
        val out = DoubleArray(input.size)
        for (i in input.indices) out[i] = process(input[i].toDouble())
        return out
    }
}
