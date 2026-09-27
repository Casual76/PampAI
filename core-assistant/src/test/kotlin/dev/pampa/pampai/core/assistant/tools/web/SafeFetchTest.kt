package dev.pampa.pampai.core.assistant.tools.web

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafeFetchTest {

  private fun ip(text: String): InetAddress = InetAddress.getByName(text)

  @Test
  fun `la rete di casa e il telefono non si leggono`() {
    listOf("127.0.0.1", "10.0.0.8", "192.168.1.1", "172.16.4.2", "169.254.10.1", "100.64.0.1", "0.0.0.0", "::1", "fd12:3456::1", "fe80::1", "::ffff:192.168.1.1")
      .forEach { assertTrue(it, SafeFetch.isPrivate(ip(it))) }
  }

  @Test
  fun `il web pubblico si`() {
    listOf("8.8.8.8", "151.101.1.140", "2a00:1450:4002:80a::200e").forEach { assertFalse(it, SafeFetch.isPrivate(ip(it))) }
  }

  @Test
  fun `solo http e https`() {
    listOf("file:///sdcard/x", "content://contacts", "ftp://example.com", "http://localhost:8080/admin", "http://127.0.0.1/").forEach { url ->
      try {
        SafeFetch.check(url)
        fail("doveva rifiutare $url")
      } catch (e: SafeFetch.Refused) {
        // atteso
      }
    }
  }
}
