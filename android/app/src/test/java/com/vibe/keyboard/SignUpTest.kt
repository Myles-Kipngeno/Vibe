package com.vibe.keyboard

import com.vibe.keyboard.remote.BackendClient
import com.vibe.keyboard.remote.BackendException
import com.vibe.keyboard.remote.HttpResult
import com.vibe.keyboard.remote.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignUpTest {

    private val sent = mutableListOf<Triple<String, Map<String, String>, String?>>()

    private fun client(reply: HttpResult) = BackendClient(
        object : HttpTransport {
            override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
                sent += Triple(url, headers, body)
                return reply
            }
        },
        Dispatchers.Unconfined,
    )

    @Test fun `a project that asks for email confirmation gives no session yet`() = runTest {
        // Supabase answers with the new user and no tokens.
        val r = client(HttpResult(200, """{"id":"u1","email":"a@b.co","confirmation_sent_at":"2026-09-30T10:00:00Z"}"""))
            .signUp("https://x.supabase.co", "anon", " a@b.co ", "secret1")
        assertNull(r.session)
        assertEquals("a@b.co", r.email)
        val (url, headers, body) = sent.single()
        assertEquals("https://x.supabase.co/auth/v1/signup", url)
        assertEquals("anon", headers["apikey"])
        assertTrue(body!!.contains("\"email\":\"a@b.co\""))
    }

    @Test fun `a project without confirmation signs the user straight in`() = runTest {
        val r = client(HttpResult(200, """{"access_token":"at","refresh_token":"rt","expires_in":3600,"user":{"id":"u1"}}"""))
            .signUp("https://x.supabase.co", "anon", "a@b.co", "secret1")
        assertEquals("at", r.session!!.accessToken)
        assertEquals("rt", r.session!!.refreshToken)
    }

    @Test fun `supabase's reason reaches the user`() = runTest {
        val e = runCatching {
            client(HttpResult(422, """{"code":422,"error_code":"weak_password","msg":"Password should be at least 6 characters."}"""))
                .signUp("https://x.supabase.co", "anon", "a@b.co", "x")
        }.exceptionOrNull() as BackendException
        assertEquals("Password should be at least 6 characters.", e.message)
    }
}
