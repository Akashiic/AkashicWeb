package com.akashiic.web.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void sameMajorIsCompatible() {
        assertTrue(Protocol.compatible(Protocol.MAJOR));
        assertFalse(Protocol.compatible(Protocol.MAJOR + 1));
    }
}
