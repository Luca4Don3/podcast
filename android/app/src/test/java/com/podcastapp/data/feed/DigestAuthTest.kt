package com.podcastapp.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Digest 的挑战解析与响应计算：只在 401 挑战后生成，算法大小写不敏感。 */
class DigestAuthTest {

    private val credentials = SourceCredentials(
        kind = CredentialKind.DIGEST,
        username = "Mufasa",
        secret = "Circle Of Life",
    )

    private val challengeHeader = "Digest realm=\"testrealm@host.com\", " +
        "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\", " +
        "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", algorithm=MD5, stale=false"

    private fun assertRejected(header: String?) {
        val error = runCatching { DigestAuth.parseChallenge(header) }.exceptionOrNull()
        assertTrue("Expected rejection: " + header, error is IllegalArgumentException)
    }

    @Test fun digestCredentialsNeverProduceStaticAuthorizationHeader() {
        assertTrue(credentialHeaders(credentials).isEmpty())
        assertTrue(credentialHeaders(SourceCredentials()).isEmpty())
        // Basic / Bearer / API Key 行为保持不变。
        assertEquals(
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice:pw".toByteArray(Charsets.UTF_8)),
            credentialHeaders(SourceCredentials(CredentialKind.BASIC, "alice", "pw"))["Authorization"],
        )
        assertEquals(
            "Bearer tok",
            credentialHeaders(SourceCredentials(CredentialKind.BEARER, secret = "tok"))["Authorization"],
        )
    }

    @Test fun parsesDigestChallengeWithMixedCaseAlgorithmAndQuotedValues() {
        val challenge = DigestAuth.parseChallenge(challengeHeader.replace("algorithm=MD5", "algorithm=md5"))
        assertEquals("testrealm@host.com", challenge.realm)
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", challenge.nonce)
        assertEquals("MD5", challenge.algorithm)
        assertEquals("auth", challenge.qop)
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", challenge.opaque)
    }

    @Test fun parsesDigestChallengeWhenOtherSchemesPrecedeIt() {
        val header = "Basic realm=\"simple\", " +
            challengeHeader.replace("algorithm=MD5", "algorithm=Sha-256")
        val challenge = DigestAuth.parseChallenge(header)
        assertEquals("SHA-256", challenge.algorithm)
        assertEquals("testrealm@host.com", challenge.realm)
    }

    @Test fun md5ChallengeResponseMatchesRfc2617Vector() {
        val challenge = DigestAuth.parseChallenge(challengeHeader)
        val value = DigestAuth.authorization(
            credentials = credentials,
            challenge = challenge,
            method = "GET",
            uri = "/dir/index.html",
            nc = "00000001",
            cnonce = "0a4f113b",
        )
        assertTrue(value.startsWith("Digest "))
        assertTrue(value.contains("username=\"Mufasa\""))
        assertTrue(value.contains("nc=00000001"))
        assertTrue(value.contains("cnonce=\"0a4f113b\""))
        assertTrue(value.contains("qop=auth"))
        assertTrue(value.contains("algorithm=MD5"))
        assertTrue(value.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(value.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test fun sha256ChallengeResponseUsesSha256Digest() {
        val header = challengeHeader.replace("algorithm=MD5", "algorithm=Sha-256")
        val value = DigestAuth.authorization(
            credentials = credentials,
            challenge = DigestAuth.parseChallenge(header),
            method = "GET",
            uri = "/dir/index.html",
            nc = "00000001",
            cnonce = "0a4f113b",
        )
        assertTrue(value.contains("algorithm=SHA-256"))
        assertTrue(value.contains("response=\"5abdd07184ba512a22c53f41470e5eea7dcaa3a93a59b630c13dfe0a5dc6e38b\""))
    }

    @Test fun legacyChallengeWithoutQopOmitsNcAndCnonce() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\"",
        )
        assertNull(challenge.qop)
        assertNull(challenge.opaque)
        val value = DigestAuth.authorization(
            credentials = credentials,
            challenge = challenge,
            method = "GET",
            uri = "/dir/index.html",
            nc = "unused",
            cnonce = "unused",
        )
        assertFalse(value.contains("nc="))
        assertFalse(value.contains("cnonce="))
        assertTrue(value.contains("response=\"670fd8c2df070c60b045671b8b24ff02\""))
    }

    @Test fun rejectsNonDigestMissingParametersAndUnsupportedAlgorithms() {
        assertRejected(null)
        assertRejected("")
        assertRejected("Basic realm=\"simple\"")
        assertRejected("Bearer token123")
        assertRejected("Digest realm=\"r\"")
        assertRejected("Digest nonce=\"n\"")
        assertRejected("Digest realm=\"r\", nonce=\"n\", algorithm=SHA-1")
        assertRejected("Digest realm=\"r\", nonce=\"n\", qop=auth-int")
    }

    @Test fun generatedNonceAndCounterAreHexEncoded() {
        assertTrue(DigestAuth.nextNc().matches(Regex("[0-9a-f]{8}")))
        assertTrue(DigestAuth.newCnonce().matches(Regex("[0-9a-f]{16}")))
    }
}
