package tv.own.owntv.core.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient

@RunWith(AndroidJUnit4::class)
class XtreamClientTest {

    private fun createClient(jsonResponse: String): XtreamClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toByteArray(Charsets.UTF_8).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return XtreamClient(HttpClient(okHttpClient))
    }

    private val testSource = SourceEntity(
        id = 1,
        name = "Test Source",
        type = SourceType.XTREAM,
        url = "http://example.com",
        username = "user",
        password = "pass",
    )

    @Test
    fun fetchAccountDetails_detectsHlsSupport() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "exp_date": "1796598000",
                "allowed_output_formats": ["m3u8", "ts", "rtmp"]
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertTrue(details.hlsSupported)
        assertEquals(1796598000000L, details.expiryMs)
    }

    @Test
    fun fetchAccountDetails_hlsNotSupportedWhenOmitted() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "exp_date": "1796598000"
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertFalse(details.hlsSupported)
        assertEquals(1796598000000L, details.expiryMs)
    }

    @Test
    fun fetchAccountDetails_hlsNotSupportedWhenTsOnly() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "allowed_output_formats": ["ts"]
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertFalse(details.hlsSupported)
    }

    @Test
    fun getSeriesInfo_readsAnOrdinaryShow() = runBlocking {
        val json = """
            {
              "episodes": {
                "1": [
                  {"id": "101", "episode_num": 1, "title": "Pilot", "container_extension": "mkv", "added": "1700000000"},
                  {"id": 102, "episode_num": "2", "title": "Second", "container_extension": "mp4", "info": {"release_date": "2026-01-02"}}
                ]
              }
            }
        """.trimIndent()

        val episodes = createClient(json).getSeriesInfo(testSource, "7").episodes
        assertEquals(listOf("101", "102"), episodes.map { it.id })
        assertEquals(listOf("Pilot", "Second"), episodes.map { it.title })
        assertEquals(listOf("mkv", "mp4"), episodes.map { it.containerExt })
        assertEquals(1700000000000L, episodes[0].airDateMs)
    }

    /**
     * A panel that writes `false`, `[]` or `{}` where a text field belongs used to throw on the first
     * one, and the whole show came out with no episodes at all — TiviMate showed them on the same
     * account. The odd value now reads as missing and every episode is kept.
     */
    @Test
    fun getSeriesInfo_oddFieldValuesDoNotLoseTheShow() = runBlocking {
        val json = """
            {
              "episodes": {
                "1": [
                  {"id": "201", "episode_num": 1, "title": false, "container_extension": "mkv", "added": false},
                  {"id": "202", "episode_num": 2, "title": "Two", "container_extension": [], "release_date": {}},
                  {"id": "203", "episode_num": 3, "title": "Three", "container_extension": "mkv", "info": {"release_date": false}}
                ]
              }
            }
        """.trimIndent()

        val episodes = createClient(json).getSeriesInfo(testSource, "7").episodes
        assertEquals(listOf("201", "202", "203"), episodes.map { it.id })
        assertEquals(listOf("", "Two", "Three"), episodes.map { it.title })
        assertEquals(listOf("mkv", null, "mkv"), episodes.map { it.containerExt })
    }

    @Test
    fun getSeriesInfo_anEpisodeWithNoUsableIdIsSkippedAlone() = runBlocking {
        val json = """
            {"episodes": {"1": [{"id": false, "episode_num": 1}, {"id": "302", "episode_num": 2}]}}
        """.trimIndent()

        val episodes = createClient(json).getSeriesInfo(testSource, "7").episodes
        assertEquals(listOf("302"), episodes.map { it.id })
    }
}
