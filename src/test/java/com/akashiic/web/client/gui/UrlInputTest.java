package com.akashiic.web.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UrlInputTest {

    @Test
    void addsHttpsToBareAddresses() {
        assertEquals("https://youtube.com/live", UrlInput.normalize("youtube.com/live"));
        assertEquals("https://www.twitch.tv/x", UrlInput.normalize("  www.twitch.tv/x  "));
        assertEquals("https://example.com:8443/a", UrlInput.normalize("example.com:8443/a"));
    }

    @Test
    void keepsHttpAndHttps() {
        assertEquals("https://example.com/", UrlInput.normalize("https://example.com/"));
        assertEquals("http://example.com", UrlInput.normalize("http://example.com"));
        assertEquals("HTTPS://Example.com/Ação", UrlInput.normalize("HTTPS://Example.com/Ação"));
    }

    @Test
    void refusesEverythingElse() {
        assertNull(UrlInput.normalize("javascript:alert(1)"));
        assertNull(UrlInput.normalize("file:///C:/Windows"));
        assertNull(UrlInput.normalize("data:text/html,<b>x</b>"));
        assertNull(UrlInput.normalize("about:blank"));
        assertNull(UrlInput.normalize("chrome://settings"));
        assertNull(UrlInput.normalize("localhost:8080"));
        assertNull(UrlInput.normalize("hello world"), "no search engine");
        assertNull(UrlInput.normalize("palavra"), "no dotted host");
        assertNull(UrlInput.normalize("https://"));
        assertNull(UrlInput.normalize("https://.com"));
        assertNull(UrlInput.normalize("example.com."));
        assertNull(UrlInput.normalize(""));
        assertNull(UrlInput.normalize(null));
        assertNull(UrlInput.normalize("example.com/a\u0000b"), "control character inside");
        assertEquals("https://example.com/", UrlInput.normalize("example.com/\u0000"), "trailing controls are trimmed");
        StringBuilder huge = new StringBuilder("example.com/");
        while (huge.length() <= UrlInput.MAX_LENGTH) huge.append('a');
        assertNull(UrlInput.normalize(huge.toString()));
    }
}
