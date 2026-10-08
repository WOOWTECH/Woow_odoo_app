package io.woowtech.odoo.ui.login

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W2-4 U4 (Pixel 7a, Play vc5): the server field's fixed `https://` prefix stayed when the user typed a
 * scheme, so "http://host" read "https://http://host". The prefix now hides once the field has a scheme;
 * the http:// rejection itself is unchanged.
 */
class ServerUrlPrefixTest {

    @Test
    fun `Given an empty field or a bare host then the https prefix is shown`() {
        assertTrue(showsHttpsPrefix(""))
        assertTrue(showsHttpsPrefix("demo.example.com"))
        assertTrue(showsHttpsPrefix("httpserver.example.com"))
        assertTrue(showsHttpsPrefix("http:/typo.example.com"))
    }

    @Test
    fun `Given the field starts with a scheme then the prefix is hidden`() {
        assertFalse(showsHttpsPrefix("http://demo.example.com"))
        assertFalse(showsHttpsPrefix("https://demo.example.com"))
        assertFalse(showsHttpsPrefix("HTTP://demo.example.com"))
        assertFalse(showsHttpsPrefix("  https://demo.example.com"))
        assertFalse(showsHttpsPrefix("http://"))
    }

    @Test
    fun `Given http when validated then it is still refused while https is accepted`() {
        assertTrue(ServerUrlInput.isInsecure("http://demo.example.com"))
        assertNull(ServerUrlInput.normalize("http://demo.example.com"))
        assertFalse(ServerUrlInput.isInsecure("https://demo.example.com"))
    }
}
