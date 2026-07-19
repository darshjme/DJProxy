package ai.darshj.djproxy.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MapDNS YAML emission — the fake-IP DNS block hev parses (keys per hev-config.c parse_mapdns). */
class EngineConfigMapDnsTest {

    @Test
    fun emitsMapDnsBlockByDefault() {
        val yaml = EngineConfig(tunFd = 7, socksPort = 1080).toYaml()
        assertTrue("mapdns block missing", yaml.contains("mapdns:"))
        assertTrue(yaml.contains("address: '198.18.0.2'"))
        assertTrue(yaml.contains("port: 53"))
        assertTrue(yaml.contains("network: '100.64.0.0'"))
        assertTrue(yaml.contains("netmask: '255.192.0.0'"))
        assertTrue(yaml.contains("cache-size: 10000"))
        // The mapdns address MUST equal the tun DNS sentinel (TunBuilder.DNS_SENTINEL = 198.18.0.2),
        // or DNS never lands in hev. Asserted as a literal to keep engine free of a vpn dependency.
        assertTrue(yaml.contains("address: '198.18.0.2'"))
    }

    @Test
    fun omitsMapDnsBlockWhenDisabled() {
        val yaml = EngineConfig(tunFd = 7, socksPort = 1080, mapDns = false).toYaml()
        assertFalse(yaml.contains("mapdns:"))
    }
}
