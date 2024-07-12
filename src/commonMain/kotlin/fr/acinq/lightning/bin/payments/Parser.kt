package fr.acinq.lightning.bin.payments

object Parser {
    fun parseEmailLikeAddress(input: String): Pair<String, String>? {
        if (!input.contains("@", ignoreCase = true)) return null

        // Ignore excess input, including additional lines, and leading/trailing whitespace
        val line = input.lines().firstOrNull { it.isNotBlank() }?.trim()
        val token = line?.split("\\s+".toRegex())?.firstOrNull()

        if (token.isNullOrBlank()) return null

        val components = token.split("@")
        if (components.size != 2) {
            return null
        }

        val username = components[0].lowercase().dropWhile { it == '₿' }
        val domain = components[1]
        return username to domain
    }
}