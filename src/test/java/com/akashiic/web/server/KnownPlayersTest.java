package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.jupiter.api.Test;

/** Friend names resolve from logins, never from a network lookup (review finding, high). */
class KnownPlayersTest {

    private static final UUID ALICE = new UUID(1, 1);
    private static final UUID BOB = new UUID(2, 2);

    @Test
    void namesResolveInAnyCaseToTheCanonicalSpelling() {
        KnownPlayers k = new KnownPlayers();
        assertNull(k.lookup("Alice"), "unknown until they log in");
        assertTrue(k.seen(ALICE, "Alice", 1000));
        assertEquals(ALICE, k.lookup("aLiCe").id);
        assertEquals("Alice", k.lookup("alice").name);
        assertFalse(k.seen(ALICE, "Alice", 2000), "a quick relog does not dirty the save file");
    }

    @Test
    void renamesAndReusedNamesFollowTheLatestLogin() {
        KnownPlayers k = new KnownPlayers();
        k.seen(ALICE, "Alice", 1000);
        k.seen(ALICE, "Alicia", 5_000_000);
        assertNull(k.lookup("Alice"), "the old name no longer points at her");
        assertEquals(ALICE, k.lookup("Alicia").id);
        k.seen(BOB, "Alicia", 9_000_000); // the name changed hands
        assertEquals(BOB, k.lookup("alicia").id);
        assertEquals(1, k.size(), "Alice is no longer reachable by any name, so she is dropped");
    }

    @Test
    void playersLongGoneAreForgottenAndTheMapIsBounded() {
        KnownPlayers k = new KnownPlayers();
        k.seen(ALICE, "Alice", 0);
        k.seen(BOB, "Bob", KnownPlayers.FORGET_AFTER_MS);
        assertTrue(k.expire(KnownPlayers.FORGET_AFTER_MS + 1));
        assertNull(k.lookup("Alice"));
        assertEquals(BOB, k.lookup("Bob").id);
        KnownPlayers many = new KnownPlayers();
        for (int i = 0; i <= KnownPlayers.MAX_ENTRIES; i++) many.seen(new UUID(9, i), "p" + i, 1000 + i);
        assertEquals(KnownPlayers.MAX_ENTRIES, many.size());
        assertNull(many.lookup("p0"), "the least recently seen goes first");
        assertEquals(new UUID(9, KnownPlayers.MAX_ENTRIES), many.lookup("p" + KnownPlayers.MAX_ENTRIES).id);
    }

    @Test
    void savedWithTheIndexAndValidatedOnLoad() {
        KnownPlayers k = new KnownPlayers();
        k.seen(ALICE, "Alice", 1234);
        NBTTagCompound tag = new NBTTagCompound();
        ScreenIndexData.writePlayers(tag, k);
        NBTTagList list = tag.getTagList("players", 10);
        NBTTagCompound bad = new NBTTagCompound();
        bad.setString("id", "not-a-uuid");
        bad.setString("name", "Mallory");
        list.appendTag(bad);
        NBTTagCompound evil = new NBTTagCompound();
        evil.setString("id", BOB.toString());
        evil.setString("name", "§kBob\n");
        list.appendTag(evil);
        KnownPlayers back = new KnownPlayers();
        ScreenIndexData.readPlayers(tag, back);
        assertEquals(1, back.size());
        assertEquals(1234, back.lookup("alice").seenMs);
    }
}
