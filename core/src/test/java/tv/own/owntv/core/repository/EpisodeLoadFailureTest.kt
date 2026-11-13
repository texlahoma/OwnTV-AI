package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.network.HttpClient
import java.net.SocketTimeoutException

/**
 * A show whose episode request failed used to read "No episodes available", exactly like a show that
 * truly has none — so a provider refusing the request could not be told apart from a parser losing
 * the answer. The screen now carries a short technical reason; these are its two forms.
 */
class EpisodeLoadFailureTest {

    @Test
    fun aRefusedRequestNamesTheProvidersStatus() {
        assertEquals("HTTP 407", episodeLoadFailure(HttpClient.HttpStatusException(407, "HTTP 407 for http://host/x")))
    }

    @Test
    fun anyOtherFailureNamesOnlyItsKindNeverItsMessage() {
        assertEquals("SocketTimeoutException", episodeLoadFailure(SocketTimeoutException("timeout to provider.example")))
    }
}
