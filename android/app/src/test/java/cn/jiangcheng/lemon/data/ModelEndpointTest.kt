package cn.jiangcheng.lemon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelEndpointTest {
    @Test fun acceptsModelList() {
        assertEquals("GET", ModelEndpoint.method("/models", false))
    }

    @Test fun acceptsConversation() {
        assertEquals("POST", ModelEndpoint.method("/chat/completions", true))
    }

    @Test fun rejectsUnexpectedRequestBodies() {
        assertThrows(IllegalArgumentException::class.java) { ModelEndpoint.method("/models", true) }
        assertThrows(IllegalArgumentException::class.java) { ModelEndpoint.method("/chat/completions", false) }
    }

    @Test fun rejectsOtherRoutesAndPathSuffixes() {
        for (path in listOf("/", "/other", "//models", "/models?extra=1", "/models/extra")) {
            assertThrows(IllegalArgumentException::class.java) { ModelEndpoint.method(path, false) }
        }
    }
}
