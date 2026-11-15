package com.tonybenoy.smbdrop

import com.tonybenoy.smbdrop.data.RoutingRule
import com.tonybenoy.smbdrop.routing.*
import org.junit.Assert.*
import org.junit.Test

class RoutingTest {
    private val rules = listOf(
        RoutingRule(1, 0, "APP", "com.scanner", 2, "Scans"),
        RoutingRule(2, 1, "MIME", "image/,video/", 1, "Photos/{yyyy}/{MM}"),
        RoutingRule(3, 2, "MIME", "application/pdf", 1, "Documents/Inbox"),
        RoutingRule(4, 3, "EXT", "docx,xlsx", 1, "Documents/Office"),
    )

    @Test fun photoGoesByDate() {
        val r = Router.resolve(rules, FileInfo("a.jpg", "image/jpeg", 1763000000000L), 1, false)!!
        assertEquals(1L, r.destinationId); assertTrue(r.dir.startsWith("Photos/2025/"))
    }
    @Test fun appRuleWinsFirst() = assertEquals(2L, Router.resolve(rules, FileInfo("a.pdf", "application/pdf", 0, "com.scanner"), 1, false)!!.destinationId)
    @Test fun extRule() = assertEquals("Documents/Office", Router.resolve(rules, FileInfo("x.DOCX", null, 0), 1, false)!!.dir)
    @Test fun fallbackAndAsk() {
        assertEquals("Drop", Router.resolve(rules, FileInfo("a.zip", "application/zip", 0), 1, false)!!.dir)
        assertNull(Router.resolve(rules, FileInfo("a.zip", "application/zip", 0), 1, true))
        assertNull(Router.resolve(rules, FileInfo("a.zip", "application/zip", 0), 0, false))
    }
    @Test fun sanitize() {
        assertEquals("a_b_c_.txt", Names.sanitize("a:b?c*.txt"))
        assertEquals(255, Names.sanitize("x".repeat(300) + ".jpg").length)
        assertTrue(Names.sanitize("x".repeat(300) + ".jpg").endsWith(".jpg"))
        assertEquals("_CON.txt", Names.sanitize("CON.txt"))
        assertEquals("photo (2).jpg", Names.numbered("photo.jpg", 2))
    }
}
