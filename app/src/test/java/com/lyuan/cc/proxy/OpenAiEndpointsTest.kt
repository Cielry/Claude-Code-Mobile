package com.lyuan.cc.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiEndpointsTest {

    @Test
    fun baseWithoutV1TriesBothEndpoints() {
        assertEquals(
            listOf(
                "https://api.deepseek.com/v1/chat/completions",
                "https://api.deepseek.com/chat/completions",
            ),
            OpenAiEndpoints.chatCandidates("https://api.deepseek.com"),
        )
    }

    @Test
    fun baseWithV1UsesSingleEndpoint() {
        assertEquals(
            listOf("https://api.siliconflow.cn/v1/chat/completions"),
            OpenAiEndpoints.chatCandidates("https://api.siliconflow.cn/v1/"),
        )
    }

    @Test
    fun fullEndpointIsPreserved() {
        assertEquals(
            listOf("https://example.com/openai/chat/completions"),
            OpenAiEndpoints.chatCandidates("https://example.com/openai/chat/completions"),
        )
    }

    @Test
    fun modelCandidatesFollowBaseShape() {
        assertEquals(
            listOf("https://api.deepseek.com/v1/models", "https://api.deepseek.com/models"),
            OpenAiEndpoints.modelsCandidates("https://api.deepseek.com"),
        )
        assertEquals(
            listOf("https://api.moonshot.cn/v1/models"),
            OpenAiEndpoints.modelsCandidates("https://api.moonshot.cn/v1"),
        )
        assertEquals(
            listOf("https://example.com/models"),
            OpenAiEndpoints.modelsCandidates("https://example.com/chat/completions"),
        )
    }

    @Test
    fun escapedSlashesAreNormalized() {
        assertEquals(
            "https://api.deepseek.com",
            OpenAiEndpoints.normalizeBase(" https:\\/\\/api.deepseek.com/ "),
        )
    }

    @Test
    fun validationRejectsUnsupportedUrls() {
        assertNotNull(OpenAiEndpoints.validate(""))
        assertNotNull(OpenAiEndpoints.validate("ftp://example.com"))
        assertNotNull(OpenAiEndpoints.validate("not a url"))
        assertNull(OpenAiEndpoints.validate("http://127.0.0.1:8080/v1"))
        assertNull(OpenAiEndpoints.validate("https://api.openai.com/v1"))
    }
}