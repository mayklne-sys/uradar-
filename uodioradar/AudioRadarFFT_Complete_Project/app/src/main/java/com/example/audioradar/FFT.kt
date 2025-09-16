package com.example.audioradar
import kotlin.math.*

object FFT {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "size must be power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; val ti = im[i]
                re[i] = re[j]; im[i] = im[j]
                re[j] = tr; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenCos = cos(ang); val wlenSin = sin(ang)
            for (i in 0 until n step len) {
                var wCos = 1.0; var wSin = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len/2] * wCos - im[i + k + len/2] * wSin
                    val vIm = re[i + k + len/2] * wSin + im[i + k + len/2] * wCos
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len/2] = uRe - vRe; im[i + k + len/2] = uIm - vIm
                    val tCos = wCos * wlenCos - wSin * wlenSin
                    wSin = wCos * wlenSin + wSin * wlenCos
                    wCos = tCos
                }
            }
            len = len shl 1
        }
    }
    fun ifft(re: DoubleArray, im: DoubleArray) {
        for (i in im.indices) im[i] = -im[i]
        fft(re, im)
        val nInv = 1.0 / re.size
        for (i in re.indices) { re[i] *= nInv; im[i] = -im[i] * nInv }
    }
}
