package com.akashiic.web.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.net.C2SControl;
import com.akashiic.web.common.net.C2SSetUrl;
import com.akashiic.web.server.ServerRuntime;

import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import sun.misc.Unsafe;

/**
 * Review finding: 1.7.10 processes up to 1001 packets per tick per connection, and every packet over the rate limit
 * (or malformed) still queued a "rejected" task, so three flooding clients filled the global action queue in about
 * nine ticks. Driven through the real handlers with a player object made without its constructor (as the review's
 * probe did); nothing here needs a world.
 */
class ServerPacketsFloodTest {

    private static final ScreenKey KEY = new ScreenKey(0, 0, 64, 0, 3);

    private EntityPlayerMP player;
    private MessageContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ServerRuntime.stop();
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Unsafe unsafe = (Unsafe) uf.get(null);
        player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        Field id = Entity.class.getDeclaredField("entityUniqueID");
        id.setAccessible(true);
        id.set(player, UUID.randomUUID());
        NetHandlerPlayServer handler = (NetHandlerPlayServer) unsafe.allocateInstance(NetHandlerPlayServer.class);
        handler.playerEntity = player;
        Constructor<MessageContext> c = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
        c.setAccessible(true);
        ctx = c.newInstance(handler, Side.SERVER);
    }

    @AfterEach
    void tearDown() {
        ServerRuntime.stop();
    }

    @Test
    void aFloodOfOneTickQueuesTheBurstAndOneFlush() {
        ServerPackets.Control control = new ServerPackets.Control();
        for (int i = 0; i < 1001; i++) control.onMessage(new C2SControl(KEY, true), ctx);
        // C2SControl is limited to 1/s with a burst of 2 (D-016); everything else is only counted.
        assertEquals(2 + 1, ServerRuntime.QUEUE.pending(), "two accepted takes and one flush of the refusals");
        assertEquals(1001 - 2, ServerPackets.REJECTIONS.pending(player.getUniqueID()));
    }

    @Test
    void malformedPacketsAreCountedNotQueued() {
        ServerPackets.SetUrl setUrl = new ServerPackets.SetUrl();
        for (int i = 0; i < 500; i++) {
            C2SSetUrl bad = new C2SSetUrl();
            bad.malformed = "truncated";
            setUrl.onMessage(bad, ctx);
        }
        assertEquals(1, ServerRuntime.QUEUE.pending(), "one flush, however many bad packets");
        assertEquals(500, ServerPackets.REJECTIONS.pending(player.getUniqueID()));
    }

    @Test
    void aFlushForAPlayerWhoLoggedOutRecordsNothing() {
        ServerPackets.SetUrl setUrl = new ServerPackets.SetUrl();
        C2SSetUrl bad = new C2SSetUrl();
        bad.malformed = "truncated";
        setUrl.onMessage(bad, ctx);
        ServerPackets.forget(player.getUniqueID());
        ServerRuntime.QUEUE.drain(); // no server: the player counts as offline
        assertNull(
            ServerRuntime.DIAGNOSTICS.peek(player.getUniqueID()),
            "nothing re-created after logout (review finding: diagnostics kept for players who left)");
    }
}
