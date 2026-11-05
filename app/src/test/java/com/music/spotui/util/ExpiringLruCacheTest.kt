package com.music.spotui.util
import org.junit.Assert.*
import org.junit.Test
class ExpiringLruCacheTest {
 @Test fun expiresAtBoundaryWithoutExtendingOnRead(){var now=0L;val c=ExpiringLruCache<String,Int>(2,10){now};c.put("a",1);now=9;assertEquals(1,c.get("a"));now=10;assertNull(c.get("a"))}
 @Test fun readingProtectsMostRecentlyUsedEntry(){val c=ExpiringLruCache<String,Int>(2,100);c.put("a",1);c.put("b",2);c.get("a");c.put("c",3);assertNull(c.get("b"));assertEquals(1,c.get("a"));assertEquals(3,c.get("c"))}
 @Test fun overwriteRefreshesLifetime(){var now=0L;val c=ExpiringLruCache<String,Int>(1,10){now};c.put("a",1);now=9;c.put("a",2);now=11;assertEquals(2,c.get("a"));now=19;assertNull(c.get("a"))}
 @Test fun clockRegressionCannotReturnStaleEntry(){var now=2L;val c=ExpiringLruCache<String,Int>(1,10){now};c.put("a",1);now=1;assertNull(c.get("a"))}
}
