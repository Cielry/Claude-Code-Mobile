package com.lyuan.cc.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 可选的端到端测试：让真实 claude-native 通过本机代理访问 mock OpenAI 上游。
 *
 * 默认跳过。运行方式（需要 Termux 内可执行的 claude-native 与 glibc loader）：
 * CCM_E2E_CLAUDE_BIN=... CCM_E2E_CLAUDE_LOADER=... CCM_E2E_CLAUDE_LIBDIR=... \
 *   gradle --no-daemon :app:testDebugUnitTest --tests '*RealClaudeCliE2eTest'
 */
class RealClaudeCliE2eTest {

    @Test
    fun realCliStreamsThroughProxy() {
        val (binary, loader, libDir) = requireToolchain()
        val root = Files.createTempDirectory("ccm-e2e").toFile()
        val mock = MockOpenAiServer().apply { includeToolCall = false }
        val proxy = startProxy(mock)
        try {
            val result = runCli(
                binary, loader, libDir, proxy, root,
                listOf("-p", "Say hi", "--permission-mode", "manual"),
            )
            assertTrue("claude-native 未在 90 秒内结束，输出：\n${result.output}", result.finished)
            assertEquals("claude-native 退出码异常，输出：\n${result.output}", 0, result.exitCode)
            assertTrue("claude-native 输出缺少 Hi：\n${result.output}", result.output.contains("Hi"))
            assertTrue("代理没有收到上游请求", mock.chatRequests >= 1)
            assertEquals("Bearer sk-e2e-provider", mock.lastAuthorization)
            assertFalse("真实 Key 泄漏到子进程输出", result.output.contains("sk-e2e-provider"))
        } finally {
            proxy.close()
            mock.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun realCliStreamsThroughResponsesProxy() {
        val (binary, loader, libDir) = requireToolchain()
        val root = Files.createTempDirectory("ccm-e2e-responses").toFile()
        val mock = MockOpenAiServer().apply { includeToolCall = false }
        val proxy = startProxy(mock, responsesApi = true)
        try {
            val result = runCli(
                binary, loader, libDir, proxy, root,
                listOf("-p", "Say hi", "--permission-mode", "manual"),
            )
            assertTrue("claude-native 未在 90 秒内结束，输出：\n${result.output}", result.finished)
            assertEquals("claude-native 退出码异常，输出：\n${result.output}", 0, result.exitCode)
            assertTrue("claude-native 输出缺少 Hi：\n${result.output}", result.output.contains("Hi"))
            assertTrue("代理没有收到 Responses 请求", mock.responsesRequests >= 1)
            assertEquals("Bearer sk-e2e-provider", mock.lastAuthorization)
            assertFalse("真实 Key 泄漏到子进程输出", result.output.contains("sk-e2e-provider"))
        } finally {
            proxy.close()
            mock.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun realCliExecutesToolThroughResponsesProxy() {
        val (binary, loader, libDir) = requireToolchain()
        val root = Files.createTempDirectory("ccm-e2e-responses-tool").toFile()
        val mock = MockOpenAiServer().apply { toolThenText = true }
        val proxy = startProxy(mock, responsesApi = true)
        try {
            val result = runCli(
                binary, loader, libDir, proxy, root,
                listOf(
                    "-p", "Run the tool once.",
                    "--permission-mode", "manual",
                    "--allowedTools", "Bash",
                ),
            )
            assertTrue("claude-native 未在 90 秒内结束，输出：\n${result.output}", result.finished)
            assertEquals("claude-native 退出码异常，输出：\n${result.output}", 0, result.exitCode)
            assertTrue("CLI 输出缺少 tool-ok：\n${result.output}", result.output.contains("tool-ok"))
            val input = mock.lastBody?.optJSONArray("input")
                ?: error("最后一次上游请求缺少 input")
            val toolOutput = (0 until input.length())
                .mapNotNull { input.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "function_call_output" }
            assertNotNull("上游没有收到 function_call_output", toolOutput)
            assertTrue(
                "工具结果缺少命令输出：${toolOutput?.optString("output")}",
                toolOutput?.optString("output").orEmpty().contains("proxy-tool-ok"),
            )
            assertFalse("真实 Key 泄漏到子进程输出", result.output.contains("sk-e2e-provider"))
        } finally {
            proxy.close()
            mock.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun realCliExecutesToolThroughProxy() {
        val (binary, loader, libDir) = requireToolchain()
        val root = Files.createTempDirectory("ccm-e2e-tool").toFile()
        val mock = MockOpenAiServer().apply { toolThenText = true }
        val proxy = startProxy(mock)
        try {
            val result = runCli(
                binary, loader, libDir, proxy, root,
                listOf(
                    "-p", "Run the tool once.",
                    "--permission-mode", "manual",
                    "--allowedTools", "Bash",
                ),
            )
            assertTrue("claude-native 未在 90 秒内结束，输出：\n${result.output}", result.finished)
            assertEquals("claude-native 退出码异常，输出：\n${result.output}", 0, result.exitCode)
            assertTrue("CLI 输出缺少 tool-ok：\n${result.output}", result.output.contains("tool-ok"))
            val messages = mock.lastBody?.optJSONArray("messages")
                ?: error("最后一次上游请求缺少 messages")
            val toolMessage = (0 until messages.length())
                .mapNotNull { messages.optJSONObject(it) }
                .firstOrNull { it.optString("role") == "tool" }
            assertNotNull("上游没有收到工具结果", toolMessage)
            assertTrue(
                "工具结果缺少命令输出：${toolMessage?.optString("content")}",
                toolMessage?.optString("content").orEmpty().contains("proxy-tool-ok"),
            )
            assertFalse("真实 Key 泄漏到子进程输出", result.output.contains("sk-e2e-provider"))
        } finally {
            proxy.close()
            mock.close()
            root.deleteRecursively()
        }
    }

    private fun requireToolchain(): Triple<String, String, String> {
        val binary = System.getenv("CCM_E2E_CLAUDE_BIN").orEmpty()
        val loader = System.getenv("CCM_E2E_CLAUDE_LOADER").orEmpty()
        val libDir = System.getenv("CCM_E2E_CLAUDE_LIBDIR").orEmpty()
        assumeTrue("未设置 CCM_E2E_CLAUDE_BIN", binary.isNotBlank())
        assumeTrue("未设置 CCM_E2E_CLAUDE_LOADER", loader.isNotBlank())
        assumeTrue("未设置 CCM_E2E_CLAUDE_LIBDIR", libDir.isNotBlank())
        assumeTrue("claude-native 不存在", File(binary).isFile)
        assumeTrue("loader 不存在", File(loader).isFile)
        assumeTrue("lib 目录不存在", File(libDir).isDirectory)
        return Triple(binary, loader, libDir)
    }

    private fun startProxy(
        mock: MockOpenAiServer,
        responsesApi: Boolean = false,
    ): OpenAiProxyServer =
        OpenAiProxyServer.start(
            ProxyConfig(
                providerBaseUrl = mock.baseUrl,
                providerApiKey = "sk-e2e-provider",
                modelId = "model-a",
                models = listOf("model-a"),
                responsesApi = responsesApi,
            )
        )

    private data class CliResult(
        val finished: Boolean,
        val exitCode: Int,
        val output: String,
    )

    private fun runCli(
        binary: String,
        loader: String,
        libDir: String,
        proxy: OpenAiProxyServer,
        root: File,
        args: List<String>,
    ): CliResult {
        val home = File(root, "home").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        val command = mutableListOf(loader, "--library-path", libDir, binary)
        command.addAll(args)
        val prefix = System.getenv("PREFIX").orEmpty().ifBlank {
            "/data/data/com.termux/files/usr"
        }
        val bash = File(prefix, "bin/bash")
        val shell = if (bash.canExecute()) bash.absolutePath else "/system/bin/sh"
        val process = ProcessBuilder(command)
            .directory(work)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("TMPDIR", tmp.absolutePath)
                    put("PATH", "$prefix/bin:/system/bin:/system/xbin")
                    put("SHELL", shell)
                    put("ANTHROPIC_BASE_URL", proxy.baseUrl)
                    put("ANTHROPIC_AUTH_TOKEN", proxy.authToken)
                    put("ANTHROPIC_MODEL", "model-a")
                    put("DISABLE_AUTOUPDATER", "1")
                    put("CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT", "1")
                    put("CLAUDE_CODE_HARBOR_KITE", "0")
                }
            }
            .start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CliResult(finished, if (finished) process.exitValue() else -1, output)
    }
}