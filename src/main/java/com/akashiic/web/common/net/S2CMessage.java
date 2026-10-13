package com.akashiic.web.common.net;

/**
 * Base of the server-to-client packets. Forward-compatibility rule within one protocol major (docs/PROTOCOL.md): a
 * server may only append fields at the end of an S2C packet, so this client skips bytes it does not know instead of
 * dropping the whole message, and a field added in a later minor is read only when it is present (see each reader).
 * A different major is handled by S2CHello alone, which reads nothing beyond the version numbers.
 */
public abstract class S2CMessage extends AkwMessage {

    @Override
    protected final boolean toleratesTrailingBytes() {
        return true;
    }
}
