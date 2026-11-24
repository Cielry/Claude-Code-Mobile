package com.lyuan.cc.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvRegistryRepairTest {

    private val base = "/data/user/0/com.lyuan.cc/files/env/libbase"

    @Test
    fun removesRelativeAndAbsoluteBaseFromLibraryPath() {
        val text = """
            PKG=cmake
            VERSION=4.5.1
            BIN=pkgs/cmake/4.5.1/bin/cmake
            LIBS=pkgs/cmake/4.5.1/lib:libbase:pkgs/openssl/3.0/lib
            ENV_LD_LIBRARY_PATH=/data/user/0/com.lyuan.cc/files/env/pkgs/cmake/4.5.1/lib:$base:/data/user/0/com.lyuan.cc/files/env/pkgs/openssl/3.0/lib
            ENV_SSL_CERT_FILE=/data/user/0/com.lyuan.cc/files/env/etc/cert.pem
        """.trimIndent()

        assertTrue(EnvRegistryRepair.needsRepair(text, base))
        val repaired = EnvRegistryRepair.stripBase(text, base)
        assertFalse(repaired.contains("libbase"))
        assertTrue(repaired.contains("LIBS=pkgs/cmake/4.5.1/lib:pkgs/openssl/3.0/lib"))
        assertTrue(
            repaired.contains(
                "ENV_LD_LIBRARY_PATH=/data/user/0/com.lyuan.cc/files/env/pkgs/cmake/4.5.1/lib:" +
                    "/data/user/0/com.lyuan.cc/files/env/pkgs/openssl/3.0/lib"
            )
        )
        assertTrue(repaired.contains("ENV_SSL_CERT_FILE="))
    }

    @Test
    fun dropsEmptyLibraryLines() {
        val text = "PKG=x\nLIBS=libbase\nENV_LD_LIBRARY_PATH=$base\n"
        val repaired = EnvRegistryRepair.stripBase(text, base)
        assertEquals("PKG=x\n", repaired)
    }

    @Test
    fun leavesAlreadyCleanConfigAlone() {
        val text = "PKG=x\nLIBS=pkgs/x/lib\nENV_LD_LIBRARY_PATH=/data/user/0/com.lyuan.cc/files/env/pkgs/x/lib\n"
        assertFalse(EnvRegistryRepair.needsRepair(text, base))
        assertEquals(text, EnvRegistryRepair.stripBase(text, base))
    }
}