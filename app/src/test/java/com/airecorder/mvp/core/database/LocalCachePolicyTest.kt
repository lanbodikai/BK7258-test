package com.airecorder.mvp.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCachePolicyTest {
    @Test fun evictsLeastRecentlyUsedEntriesUntilUnderLimit() {
        val candidates = listOf(
            LocalCacheCandidate("new", 40, 300),
            LocalCacheCandidate("old", 40, 100),
            LocalCacheCandidate("middle", 40, 200)
        )

        assertEquals(listOf("old", "middle"), LocalCachePolicy.selectEvictions(120, 50, candidates))
    }

    @Test fun keepsCacheWhenAlreadyWithinLimit() {
        val candidate = LocalCacheCandidate("recording", 100, 100)

        assertEquals(emptyList<String>(), LocalCachePolicy.selectEvictions(100, 100, listOf(candidate)))
    }
}
