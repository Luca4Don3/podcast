package com.podcastapp

import com.podcastapp.data.ServerAddress
import com.podcastapp.data.serversetup.ServerSetupLink
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSetupLinkTest {
    @Test fun plainAddressesUseServerAddressWithoutCredentials() {
        for (raw in listOf(" example.test ", "HTTP://[::1]:8080/proxy", "https://example.test/proxy/@api/")) {
            val link = ServerSetupLink.parse(raw)
            assertEquals(ServerAddress.optional(raw), link.baseUrl)
            assertNull(link.email)
            assertNull(link.password)
        }
    }

    @Test fun userInfoIsDecodedAndRemovedFromBaseUrl() {
        val link = ServerSetupLink.parse(" HTTPS://alice%40example.test:p%40ss%3a%2F@EXAMPLE.TEST:8443/proxy/ ")
        assertEquals("https://example.test:8443/proxy/", link.baseUrl)
        assertEquals("alice@example.test", link.email)
        assertEquals("p@ss:/", link.password)
    }

    @Test fun pasteFragmentSupportsEitherOrderAndLiteralPlus() {
        for (fragment in listOf(
            "username=alice+tag%40example.test&password=p+a%26%3D%23%25%252B%E9%9B%AA",
            "password=p+a%26%3D%23%25%252B%E9%9B%AA&username=alice+tag%40example.test",
        )) {
            val link = ServerSetupLink.parse("https://example.test/#$fragment")
            assertEquals("https://example.test/api/v1/", link.baseUrl)
            assertEquals("alice+tag@example.test", link.email)
            assertEquals("p+a&=#%%2B雪", link.password)
        }
    }

    @Test fun credentialsRequireExplicitHttpsAndOneCarrier() {
        assertInvalid(
            "http://alice%40example.test:p@example.test/",
            "http://example.test/#username=alice%40example.test&password=p",
            "alice%40example.test:p@example.test/",
            "example.test/#username=alice%40example.test&password=p",
            "https://alice%40example.test:p@example.test/#username=alice%40example.test&password=p",
            "https://alice%40example.test:p@example.test/#",
            "https://alice@example.test:p@example.test/",
            "https://alice%40example.test:p@first.test@second.test/",
        )
    }

    @Test fun rejectsBlankPartialAndUnknownCredentials() {
        assertInvalid(
            "", "   ", "https://", "ftp://example.test", "https://example.test:65536",
            "https://alice%40example.test@example.test/",
            "https://alice%40example.test:@example.test/",
            "https://:p@example.test/",
            "https://alice:p@example.test/",
            "https://alice%40tag%40example.test:p@example.test/",
            "https://example.test/#",
            "https://example.test/#username=alice%40example.test",
            "https://example.test/#username=alice%40example.test&password",
            "https://example.test/#username=&password=p",
            "https://example.test/#username=alice%40example.test&password=",
            "https://example.test/#username=alice%40example.test&username=bob%40example.test",
            "https://example.test/#password=p&password=q",
            "https://example.test/#username=alice%40example.test&password=p&password=q",
            "https://example.test/#username=alice%40example.test&password=p&",
            "https://example.test/#email=alice%40example.test&password=p",
            "https://example.test/#user%6Eame=alice%40example.test&password=p",
        )
    }

    @Test fun rejectsQueriesWhitespacesControlsAndBackslashes() {
        assertInvalid(
            "https://example.test/?",
            "https://example.test/?password=fixture",
            "https://example.test/?x=1#username=alice%40example.test&password=fixture",
            "https://alice%40example.test:fixture@example.test/?x=1",
            "https://exam ple.test/", "https://example.test/a\u00a0b",
            "https://exa\tmple.test/", "https://exa\nmple.test/", "https://ex\u007fample.test/",
            "https://example.test/\n", "https://example.test/a\\b",
            "https://example.test/#username=alice%40example.test&password=two words",
        )
    }

    @Test fun rejectsBadPercentEncodingInBothCredentialFormats() {
        for (encoded in listOf("%", "%A", "%GG", "%FF", "%C0%AF", "%E2%82", "%ED%A0%80", "%00", "%C2%85", "%20", "%5c")) {
            assertInvalid(
                "https://alice%40example.test:$encoded@example.test/",
                "https://example.test/#username=alice%40example.test&password=$encoded",
            )
        }
    }

    @Test fun lengthLimitAppliesBeforeTrimming() {
        val address = "https://example.test/"
        val atLimit = " ".repeat(4096 - address.length) + address
        assertEquals("https://example.test/api/v1/", ServerSetupLink.parse(atLimit).baseUrl)
        assertInvalid(" $atLimit", "https://alice%40example.test:" + "p".repeat(4096) + "@example.test/")
    }

    @Test fun printableRepresentationDoesNotExposeCredentials() {
        val link = ServerSetupLink.parse("https://fixture.test/#username=alice%40example.test&password=fixture%2Etest")
        assertFalse(link.toString().contains("fixture.test"))
        assertFalse(link.toString().contains("fixture%2Etest"))
        assertFalse(link.toString().contains("alice"))
    }

    @Test fun privateConstructorAlsoValidatesInput() {
        val constructor = ServerSetupLink::class.java.getDeclaredConstructor(String::class.java)
        assertTrue(Modifier.isPrivate(constructor.modifiers))
        constructor.isAccessible = true
        val wrapped = assertThrows(InvocationTargetException::class.java) {
            constructor.newInstance("http://alice%40example.test:fixture@example.test/")
        }
        assertTrue(wrapped.cause is IllegalArgumentException)
        assertEquals("服务器设置链接无效", wrapped.cause?.message)
    }

    private fun assertInvalid(vararg values: String) {
        values.forEachIndexed { index, value ->
            val error = assertThrows("case $index", IllegalArgumentException::class.java) {
                ServerSetupLink.parse(value)
            }
            assertEquals("case $index", "服务器设置链接无效", error.message)
            assertNull("case $index", error.cause)
        }
    }
}
