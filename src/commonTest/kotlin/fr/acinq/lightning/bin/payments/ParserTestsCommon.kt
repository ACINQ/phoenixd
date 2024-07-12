package fr.acinq.lightning.bin.payments

import kotlin.test.Test
import kotlin.test.assertEquals

class ParserTestsCommon {

    @Test
    fun `test address parsing`() {
        data class TestCase(val address: String, val user: String, val domain: String)

        val testcases = listOf(
            TestCase("foo@bar.com", "foo", "bar.com"),
            TestCase("₿foo@bar.com", "foo", "bar.com"),
            TestCase("₿₿foo@bar.com", "foo", "bar.com"),
        )

        testcases.forEach { testCase -> assertEquals(testCase.user to testCase.domain, Parser.parseEmailLikeAddress(testCase.address)) }
    }
}