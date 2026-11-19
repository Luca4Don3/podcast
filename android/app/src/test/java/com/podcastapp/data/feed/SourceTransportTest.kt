package com.podcastapp.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 传输材料的纯函数校验、PEM 解析与错误路径；证书为一次性测试夹具，不含真实凭据。 */
class SourceTransportTest {

    private val secure = SourceTransport(
        requireHttps = true,
        customCaPem = TEST_CERT,
        clientCertPem = TEST_CERT,
        clientKeyPem = TEST_KEY,
        clientKeyPassword = "fixture-password",
        digest = true,
    )

    @Test fun requireHttpsRejectsHttpButAllowsHttps() {
        SourceTransportSecurity.assertSchemeAllowed("https://feeds.example.com/rss", secure)
        SourceTransportSecurity.assertSchemeAllowed("http://feeds.example.com/rss", SourceTransport())
        val error = runCatching {
            SourceTransportSecurity.assertSchemeAllowed("http://feeds.example.com/rss", secure)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("requireHttps") == true)
    }

    @Test fun transportIsStrippedOnCrossHostAndHttpsDowngrade() {
        val sameOrigin = SourceTransportSecurity.transportForRedirect(
            "https://feeds.example.com/a.xml", "https://feeds.example.com/b.xml", secure,
        )
        assertEquals(secure, sameOrigin)
        for (target in listOf("https://other.example.com/b.xml", "http://feeds.example.com/b.xml")) {
            val stripped = SourceTransportSecurity.transportForRedirect(
                "https://feeds.example.com/a.xml", target, secure,
            )
            assertTrue(stripped.customCaPem.isEmpty())
            assertTrue(stripped.clientCertPem.isEmpty())
            assertTrue(stripped.clientKeyPem.isEmpty())
            assertTrue(stripped.clientKeyPassword.isEmpty())
            assertFalse(stripped.digest)
            // requireHttps 是本地策略，跨 host 后仍阻止 http 跳转。
            assertTrue(stripped.requireHttps)
        }
        assertEquals(SourceTransport(), transportForRedirect(
            "https://feeds.example.com/a.xml", "https://other.example.com/b.xml", SourceTransport(),
        ))
    }

    @Test fun validCertificateAndPkcs8KeyAreParsed() {
        val certs = SourceTransportSecurity.parseCertificates(TEST_CERT, "测试 CA")
        assertEquals(1, certs.size)
        assertTrue(certs.single().subjectX500Principal.name.contains("podcastapp-test.invalid"))
        val key = SourceTransportSecurity.parsePrivateKey(TEST_KEY, "")
        assertEquals("RSA", key.algorithm)
    }

    @Test fun trustManagerKeepsSystemCertificatesAndAddsCustomCa() {
        val manager = SourceTransportSecurity.trustManager(TEST_CERT)
        assertTrue(manager.acceptedIssuers.isNotEmpty())
        assertTrue(manager.acceptedIssuers.any {
            it.subjectX500Principal.name.contains("podcastapp-test.invalid")
        })
    }

    @Test fun keyManagerIsBuiltFromCertificateAndKey() {
        val manager = SourceTransportSecurity.keyManager(TEST_CERT, TEST_KEY, "")
        assertTrue(manager.getPrivateKey("client") != null)
    }

    @Test fun malformedPemReturnsClearSecurityError() {
        expectSecurityError({ SourceTransportSecurity.parseCertificates("not a certificate", "自定义 CA 证书") }, "证书")
        expectSecurityError({ SourceTransportSecurity.parsePrivateKey("not a key", "") }, "PKCS8")
        expectSecurityError({ SourceTransportSecurity.keyManager(TEST_CERT, "not a key", "") }, "PKCS8")
        expectSecurityError({
            SourceTransportSecurity.parseCertificates(TEST_CERT.replace("CERTIFICATE", "CERTIFICATX"), "自定义 CA 证书")
        }, "证书")
        expectSecurityError({
            SourceTransportSecurity.parseCertificates("x".repeat(70 * 1024), "自定义 CA 证书")
        }, "大小限制")
    }

    private fun expectSecurityError(action: () -> Unit, messagePart: String) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected SecurityException containing " + messagePart, error is SecurityException)
        assertTrue(error?.message?.contains(messagePart) == true)
    }

    private companion object {
        val TEST_CERT = """
-----BEGIN CERTIFICATE-----
MIIDJTCCAg2gAwIBAgIUQ6xCLIFIqN9HNqBx9ikw2BuLf3cwDQYJKoZIhvcNAQEL
BQAwIjEgMB4GA1UEAwwXcG9kY2FzdGFwcC10ZXN0LmludmFsaWQwHhcNMjYxMTE5
MDI1OTIwWhcNMzYxMTE2MDI1OTIwWjAiMSAwHgYDVQQDDBdwb2RjYXN0YXBwLXRl
c3QuaW52YWxpZDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALbHvl8O
Xl53qptqe21//nXE5C4i/b4T3tycGlJytNiehhbBBv75/yOQJ9JNaZ76EIqe/oIn
knT3yCTISn0G6Fwq/jM2Rru0rytsxrIMEzctxkW9tJU2gILYkzvOkJm/YOGecYcz
FBrKy6FcyJ1FfVhSdm36wxgLq/FLWZwlB4RS/d2dId46xa54USYfzYAs9LVFIw8n
aHZ5tlw3rKza+3GalHhGKyVteoV+dLm13ukOPROvVPGuv0lcm9JriZBtOrFFR59h
KewR42MILfuwAuGijZR9AXyCliw5Dan2JaUIOK1cym/VaMONlWiGn1dUnXYXiCPc
bAN+ny5AxndIJLMCAwEAAaNTMFEwHQYDVR0OBBYEFP7QSBDdNf9IBw86lXRgA1PY
ntz+MB8GA1UdIwQYMBaAFP7QSBDdNf9IBw86lXRgA1PYntz+MA8GA1UdEwEB/wQF
MAMBAf8wDQYJKoZIhvcNAQELBQADggEBAB0ICEnbx0q0bim0LZkwjUlkKqP4YkyX
CvhDCZBOex69FUl+tcpwpXg6RLSzXvrOE5P79MhJynagNeMmmwSJMuaQAUkpxc71
rgekIzw1E3rH96+sc56205KCTgnNfp3mC33cKdMjgP/juQETSKLriDu2wOM28Ftx
8y2bHCEq4AMdAspVTYCmtHdrUbPF2t7fXLYRcZBbNX0RmurD93RQN6yzzeOln7PZ
53oCX0KkLNDJEG8RZVXlXKQgjJQTKo6rMZjiTGAufp/Z2iMStxJ2h7xiv6ab3jc8
Ki02UIfuyMeA9JQkt22+nxJHxc6yDx+jw5X0kgxGddLirt6WtEAFlT8=
-----END CERTIFICATE-----
""".trimIndent()
        val TEST_KEY = """
-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2x75fDl5ed6qb
anttf/51xOQuIv2+E97cnBpScrTYnoYWwQb++f8jkCfSTWme+hCKnv6CJ5J098gk
yEp9BuhcKv4zNka7tK8rbMayDBM3LcZFvbSVNoCC2JM7zpCZv2DhnnGHMxQaysuh
XMidRX1YUnZt+sMYC6vxS1mcJQeEUv3dnSHeOsWueFEmH82ALPS1RSMPJ2h2ebZc
N6ys2vtxmpR4RislbXqFfnS5td7pDj0Tr1Txrr9JXJvSa4mQbTqxRUefYSnsEeNj
CC37sALhoo2UfQF8gpYsOQ2p9iWlCDitXMpv1WjDjZVohp9XVJ12F4gj3GwDfp8u
QMZ3SCSzAgMBAAECggEAAXG4QtxZtDRiMDfaAXun6+VM35fI2nt3YNXeQrWDpqHb
XGBVE6cFCZJmMNFfcBv4t9JZdMzRlEQFnFN1fIxql0PYXgOOm9q/IZWKnQs0YaoR
GCmkvM3BRodl48HrlD4WCrernPkIg80DaTh9oKR/wOFnQdI1QBeOLxS8gLEtizGb
QtDMNbSxw5NO1NLOUXGQ/+9FUk9d/lA/pWPOG2rbkeOcxHgdxmE8KspI9Rmt3bLC
/9DXK0zf+7vebgi6iPumn4LGj8nbRieHhCUq3IHz5vQJmaMvgYfpdI7Iv+IOzuUG
p1wbpzMRE7/VEak/BG+T3ms6NdL4Cj+mUJSIzxJlvQKBgQDeLgWhmyxkssixd0Yp
XcbUPe93YDP6iErWxqJqJoAheXn2uUpoI5d8NZH16rRbb1kDDCWsS5UN0JQN19HC
68t7F86TavwmdfarZEVxAgGPoaZH2FcTIMP/vxtDLfRHU238cHv4l5zCuxfie975
nF/MFQQJuphiXjo2gdH3E8/OZwKBgQDSmmMq+1Z9MYlbOwSUxJvLL+35V2/cKt7j
Y23LWskaCFn3w1NPFHPBfhakMKiLR/X5Op6DFvWL7bEn8KKO1vcjoQL8btpQACHO
8Csg/4p9BNsflQjGX1P5zyFDr8OQ5C6aotXv7nEI1Sw12ko0wn1cIilY0FXR1tt9
Pa/KuM+v1QKBgD5UxvupnvC5P5iaD7L3Phq5LX9eG/cDWYubumDxYC0D+XWAUiGZ
KpyMIuDM8cBdIqm6NK97nNt9Uqv3ij+XC0UDTs8Iyqi3m3ZF8T1dBLcY8J9jUDpr
jNEo+isEr217OHYD0KPTVQVEm9TMT6fTeQS2OKXKFYozST2VeiRJCJmtAoGBALEI
GmhdElz2sRHBUJ6FjriEN7SrbVpTpzzZrceNLxL1BjuhM3x8Irn59apbRLKf8QJp
5iknuYzAZ3S9cMhFp0WJ6Wqs/FLXaZP8iySX6qAg3tlzfAbUx1Bsbvfo3aoERbCz
RMcFVnsoRpuWPV6QRrSV+s+vgRR9Wc+zdPwh0mNpAoGBAKT2gmBS6KonJHDXImen
Fry6mZgyrgkSoNhBjy7tADLNc4u2MHd1aVySHR2a/j5nHxqp3S7KqHop54SKP/cG
4xI0eovbH/vLuigXlmFm+9AK9mhuelT2hyqcXdVeBeybAdkmEQyTgr4GIXON7CJy
m3iZiiTRRIyhRfBT8Jk9DXAP
-----END PRIVATE KEY-----
""".trimIndent()
    }
}
