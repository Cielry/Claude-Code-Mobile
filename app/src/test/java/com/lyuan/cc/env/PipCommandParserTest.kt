package com.lyuan.cc.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipCommandParserTest {

    private fun install(input: String): List<String> {
        val result = PipCommandParser.parse(input)
        assertTrue("expected install for: $input", result is PipCommand.Install)
        return (result as PipCommand.Install).args
    }

    private fun error(input: String): String {
        val result = PipCommandParser.parse(input)
        assertTrue("expected error for: $input", result is PipCommand.Error)
        return (result as PipCommand.Error).message
    }

    @Test
    fun barePackageNameBecomesInstall() {
        assertEquals(listOf("install", "requests"), install("requests"))
        assertEquals(listOf("install", "requests==2.32.0"), install("requests==2.32.0"))
        assertEquals(
            listOf("install", "requests", "flask"),
            install("requests flask"),
        )
    }

    @Test
    fun pipPrefixIsAccepted() {
        assertEquals(listOf("install", "requests"), install("pip install requests"))
        assertEquals(listOf("install", "requests"), install("pip3 install requests"))
        assertEquals(
            listOf("install", "-U", "requests"),
            install("pip3.13 install -U requests"),
        )
        assertEquals(
            listOf("install", "requests"),
            install("sudo pip install requests"),
        )
    }

    @Test
    fun pythonModuleFormIsAccepted() {
        assertEquals(
            listOf("install", "-r", "requirements.txt"),
            install("python -m pip install -r requirements.txt"),
        )
        assertEquals(
            listOf("install", "-i", "https://mirror.example/simple", "flask"),
            install("python3 -m pip install -i https://mirror.example/simple flask"),
        )
    }

    @Test
    fun quotesAreHandledWithoutShell() {
        assertEquals(
            listOf("install", "requests>=2.0"),
            install("pip install \"requests>=2.0\""),
        )
        assertEquals(
            listOf("install", "pkg", "-i", "https://example.com/simple"),
            install("pip install 'pkg' -i \"https://example.com/simple\""),
        )
    }

    @Test
    fun otherSubcommandsAreRejected() {
        assertTrue(error("pip uninstall requests").contains("只用于安装"))
        assertTrue(error("pip list").contains("只用于安装"))
        assertTrue(error("python script.py").contains("只用于 pip 安装"))
    }

    @Test
    fun emptyAndBrokenInputAreRejected() {
        assertTrue(error("").contains("请输入"))
        assertTrue(error("   ").contains("请输入"))
        assertTrue(error("pip install \"unterminated").contains("引号"))
    }

    @Test
    fun displayShowsPipInvocation() {
        assertEquals(
            "python -m pip install requests==2.32.0",
            PipCommandParser.display(listOf("install", "requests==2.32.0")),
        )
    }

    @Test
    fun everyProcessInvocationIncludesModulePip() {
        // 回归：只拼 python + 参数会变成 `python install docx`，
        // Python 会把 install 当成脚本路径并报 can't open file。
        assertEquals(
            listOf("/env/bin/python", "-m", "pip", "install", "docx"),
            PipManager.commandFor("/env/bin/python", listOf("install", "docx")),
        )
        assertEquals(
            listOf("/env/bin/python", "-m", "pip", "list", "--format=json"),
            PipManager.commandFor(
                "/env/bin/python",
                listOf("list", "--format=json"),
            ),
        )
    }

    @Test
    fun pipBootstrapPointsExecutableBackToLauncher() {
        val script = PipManager.PIP_BOOTSTRAP
        assertTrue(script.contains("CCM_PYTHON_LAUNCHER"))
        assertTrue(script.contains("sys.executable"))
    }
}