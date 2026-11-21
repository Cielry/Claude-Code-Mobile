package com.lyuan.cc.proxy

import org.json.JSONObject

/**
 * 上游 SSE 事件 → Anthropic SSE 事件的转换器。
 *
 * [AnthropicSseTranslator] 处理 Chat Completions 事件，
 * [ResponsesSseTranslator] 处理 Responses API 事件；代理按协议选择。
 */
interface ProxySseTranslator {

    fun begin(inputTokens: Int)

    fun consume(json: JSONObject)

    /** 收到 [DONE] 或上游结束事件时调用；重复调用安全。 */
    fun complete()

    fun fail(type: String, message: String)

    val isFinished: Boolean
}