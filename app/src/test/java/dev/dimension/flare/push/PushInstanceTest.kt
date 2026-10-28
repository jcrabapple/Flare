package dev.dimension.flare.push

import dev.dimension.flare.model.MicroBlogKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushInstanceTest {
    @Test
    fun parsesValidInstance() {
        assertEquals(MicroBlogKey("me", "example.com"), parseInstance("me@example.com"))
    }

    @Test
    fun roundTripsEscapedId() {
        val key = MicroBlogKey("user@name", "example.com")
        assertEquals(key, parseInstance(key.toString()))
    }

    @Test
    fun rejectsInstanceWithoutHost() {
        assertNull(parseInstance("garbage"))
        assertNull(parseInstance(""))
    }

    @Test
    fun rejectsCommaTruncatedInstance() {
        // MicroBlogKey.valueOf stops at the first comma, leaving no host: not an account key.
        assertNull(parseInstance("a,b@example.com"))
    }
}
