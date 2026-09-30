package com.vibe.keyboard

import com.vibe.keyboard.remote.HttpResult
import com.vibe.keyboard.remote.HttpTransport
import com.vibe.keyboard.remote.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {

    private fun checker(respond: () -> HttpResult) = UpdateChecker(
        http = object : HttpTransport {
            override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
                assertEquals(UpdateChecker.VERSION_URL, url)
                return respond()
            }
        },
        dispatcher = Dispatchers.Unconfined,
    )

    private val published = HttpResult(200, """{"versionCode": 105, "versionName": "0.2.105"}""")

    @Test fun `a newer published build is offered`() = runTest {
        val update = checker { published }.check(installedVersionCode = 102)!!
        assertEquals("0.2.105", update.versionName)
        assertEquals(UpdateChecker.APK_URL, update.downloadUrl)
    }

    @Test fun `the same or an older build is not`() = runTest {
        assertNull(checker { published }.check(105))
        assertNull(checker { published }.check(150))
    }

    @Test fun `any failure just means no update shown`() = runTest {
        assertNull(checker { HttpResult(404, "Not Found") }.check(1))
        assertNull(checker { HttpResult(200, "<html>not json</html>") }.check(1))
        assertNull(checker { throw IOException("offline") }.check(1))
    }
}
