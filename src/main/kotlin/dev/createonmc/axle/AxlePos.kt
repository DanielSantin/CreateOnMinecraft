package dev.createonmc.axle

data class AxlePos(val worldName: String, val bx: Int, val by: Int, val bz: Int) {
    companion object {
        fun parse(s: String): AxlePos? {
            val p = s.split(",")
            if (p.size < 4) return null
            return runCatching { AxlePos(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt()) }.getOrNull()
        }
    }
}
