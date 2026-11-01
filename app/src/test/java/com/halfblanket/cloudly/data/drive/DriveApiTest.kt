package com.halfblanket.cloudly.data.drive

import com.halfblanket.cloudly.core.CloudlyException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DriveApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    /** Hands out "token-1", then "token-2" after an invalidation; records which account asked. */
    private class FakeTokens : AccessTokenSource {
        var version = 1
        val requested = mutableListOf<String>()
        val invalidated = mutableListOf<String>()
        override suspend fun token(accountId: String): String {
            requested += accountId
            return "$accountId-token-$version"
        }
        override suspend fun invalidate(accountId: String, token: String) {
            invalidated += token
            version++
        }
    }

    private lateinit var tokens: FakeTokens
    private lateinit var api: DriveApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = FakeTokens()
        api = DriveApi(OkHttpClient(), tokens, json, server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `list parses files and tags them with the requesting account`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"f1","name":"Report.pdf","mimeType":"application/pdf","size":"2048","starred":true}],"nextPageToken":"n2"}"""))
        val page = api.list("work", "'root' in parents and trashed = false")
        assertEquals("n2", page.nextPageToken)
        val f = page.items.single()
        assertEquals("work", f.accountId)
        assertEquals(2048L, f.size)
        assertTrue(f.starred)
        val req = server.takeRequest()
        assertEquals("Bearer work-token-1", req.getHeader("Authorization"))
        assertTrue(req.requestUrl!!.queryParameter("q")!!.contains("'root' in parents"))
    }

    @Test fun `401 invalidates the token and retries once with a fresh one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"user":{"emailAddress":"a@b.c","permissionId":"p1"}}"""))
        val about = api.about("acc")
        assertEquals("a@b.c", about.user?.emailAddress)
        assertEquals(listOf("acc-token-1"), tokens.invalidated)
        server.takeRequest()
        assertEquals("Bearer acc-token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `errors map to friendly api exceptions`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"x","errors":[{"reason":"storageQuotaExceeded"}]}}"""))
        val e = runCatching { api.get("acc", "f1") }.exceptionOrNull()
        assertTrue(e is CloudlyException.Api)
        assertEquals("storageQuotaExceeded", (e as CloudlyException.Api).reason)
        assertEquals("The destination account is out of storage.", e.message)
    }

    @Test fun `metadata updates omit null fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"f1","name":"New"}"""))
        api.update("acc", "f1", FileMetadataBody(name = "New"))
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"name":"New"}""", body)
    }

    @Test fun `resumable upload resumes from the offset the server reports`() = runBlocking {
        val data = "0123456789".toByteArray()
        val session = server.url("/upload-session").toString()
        server.enqueue(MockResponse().setHeader("Location", session)) // start session
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-4")) // only 5 bytes stored
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"new","name":"n.txt","mimeType":"text/plain","size":"10"}"""))

        val source = object : UploadSource {
            override val length = data.size.toLong()
            override fun open(offset: Long) = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
        var lastProgress = 0L
        val file = api.uploadResumable("acc", FileMetadataBody(name = "n.txt", parents = listOf("root")), "text/plain", source) { d, _ -> lastProgress = d }
        assertEquals("new", file.id)
        assertEquals("acc", file.accountId)
        assertEquals(10L, lastProgress)

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("10", start.getHeader("X-Upload-Content-Length"))
        val first = server.takeRequest()
        assertEquals("bytes 0-9/10", first.getHeader("Content-Range"))
        val second = server.takeRequest()
        assertEquals("bytes 5-9/10", second.getHeader("Content-Range"))
        assertEquals("56789", second.body.readUtf8())
    }

    @Test fun `download streams body bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello drive"))
        val out = ByteArrayOutputStream()
        val n = api.download("acc", "f1", out)
        assertEquals(11L, n)
        assertEquals("hello drive", out.toString())
        assertEquals("media", server.takeRequest().requestUrl!!.queryParameter("alt"))
    }

    @Test fun `every request uses the token of the account passed in`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        api.list("personal", "q")
        api.list("work", "q")
        assertEquals("Bearer personal-token-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer work-token-1", server.takeRequest().getHeader("Authorization"))
        assertFalse(tokens.requested.isEmpty())
    }
}
