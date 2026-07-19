package ai.darshj.djproxy.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoParserTest {

    @Test
    fun `parses ipinfo loc plus label`() {
        val json = """{"ip":"104.28.1.1","city":"San Francisco","region":"California","country":"US","loc":"37.7749,-122.4194","org":"AS13335"}"""
        val g = GeoParser.parse(json)!!
        assertEquals(37.7749, g.lat, 1e-6)
        assertEquals(-122.4194, g.lng, 1e-6)
        assertEquals("San Francisco, California, US", g.label)
    }

    @Test
    fun `parses ipwho latitude longitude`() {
        val json = """{"ip":"8.8.8.8","success":true,"city":"Mountain View","region":"California","country":"United States","latitude":37.386,"longitude":-122.0838}"""
        val g = GeoParser.parse(json)!!
        assertEquals(37.386, g.lat, 1e-6)
        assertEquals(-122.0838, g.lng, 1e-6)
        assertEquals("Mountain View, California, United States", g.label)
    }

    @Test
    fun `parses ipapi with country_name and quoted numbers`() {
        val json = """{"ip":"1.1.1.1","city":"Sydney","region":"New South Wales","country_name":"Australia","country":"AU","latitude":"-33.8688","longitude":"151.2093"}"""
        val g = GeoParser.parse(json)!!
        assertEquals(-33.8688, g.lat, 1e-6)
        assertEquals(151.2093, g.lng, 1e-6)
        // country_name wins over the country code for the label.
        assertEquals("Sydney, New South Wales, Australia", g.label)
    }

    @Test
    fun `lat key does not falsely match latitude`() {
        // Only "latitude"/"longitude" present — the "lat"/"lon" fallbacks must not mis-parse them,
        // but the primary latitude/longitude keys must still resolve.
        val json = """{"latitude":51.5074,"longitude":-0.1278,"city":"London"}"""
        val g = GeoParser.parse(json)!!
        assertEquals(51.5074, g.lat, 1e-6)
        assertEquals(-0.1278, g.lng, 1e-6)
        assertEquals("London", g.label)
    }

    @Test
    fun `rejects null island`() {
        assertNull(GeoParser.parse("""{"loc":"0,0","city":"Nowhere"}"""))
    }

    @Test
    fun `rejects out of range`() {
        assertNull(GeoParser.parse("""{"latitude":999.0,"longitude":5.0}"""))
    }

    @Test
    fun `returns null on missing coordinates`() {
        assertNull(GeoParser.parse("""{"city":"Berlin","country":"DE"}"""))
    }

    @Test
    fun `returns null on garbage`() {
        assertNull(GeoParser.parse("not json at all"))
        assertNull(GeoParser.parse(""))
    }

    @Test
    fun `blank label when no place fields`() {
        val g = GeoParser.parse("""{"loc":"10.0,20.0"}""")!!
        assertEquals("", g.label)
    }
}

class ExitGeoResolverTest {

    /** Records the (host,path) it was asked for and replays canned bodies per host. */
    private class FakeTransport(private val bodies: Map<String, String?>) : GeoHttpTransport {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun get(host: String, path: String): String? {
            calls.add(host to path)
            return bodies[host]
        }
    }

    @Test
    fun `resolves through first endpoint using explicit exit ip path`() = runBlocking {
        val fake = FakeTransport(
            mapOf("ipinfo.io" to """{"loc":"48.8566,2.3522","city":"Paris","country":"FR"}"""),
        )
        val resolver = ExitGeoResolver(fake)
        val loc = resolver.resolve("104.28.1.1")
        assertNotNull(loc)
        assertEquals(48.8566, loc!!.lat, 1e-6)
        assertEquals(2.3522, loc.lng, 1e-6)
        assertEquals("Paris, FR", loc.label)
        assertTrue(loc.source.startsWith("exit-geo:"))
        // Explicit IP → the /<ip>/json path, not the self path.
        assertEquals("ipinfo.io" to "/104.28.1.1/json", fake.calls.first())
    }

    @Test
    fun `uses self path when exit ip is null`() = runBlocking {
        val fake = FakeTransport(mapOf("ipinfo.io" to """{"loc":"1.0,2.0"}"""))
        ExitGeoResolver(fake).resolve(null)
        assertEquals("ipinfo.io" to "/json", fake.calls.first())
    }

    @Test
    fun `falls through to a later endpoint when the first is down or garbage`() = runBlocking {
        val fake = FakeTransport(
            mapOf(
                "ipinfo.io" to null,                       // down
                "ipwho.is" to "garbage",                   // reachable but unparseable
                "ipapi.co" to """{"latitude":35.68,"longitude":139.69,"city":"Tokyo","country_name":"Japan"}""",
            ),
        )
        val loc = ExitGeoResolver(fake).resolve("203.0.113.7")
        assertNotNull(loc)
        assertEquals(35.68, loc!!.lat, 1e-6)
        assertEquals("Tokyo, Japan", loc.label)
        // All three endpoints were attempted in order.
        assertEquals(listOf("ipinfo.io", "ipwho.is", "ipapi.co"), fake.calls.map { it.first })
    }

    @Test
    fun `returns null when every endpoint fails`() = runBlocking {
        val fake = FakeTransport(mapOf("ipinfo.io" to null, "ipwho.is" to null, "ipapi.co" to null))
        assertNull(ExitGeoResolver(fake).resolve("203.0.113.7"))
    }

    @Test
    fun `non-ip exit string falls back to self path`() = runBlocking {
        val fake = FakeTransport(mapOf("ipinfo.io" to """{"loc":"5.0,6.0"}"""))
        ExitGeoResolver(fake).resolve("not-an-ip")
        assertEquals("ipinfo.io" to "/json", fake.calls.first())
    }
}
