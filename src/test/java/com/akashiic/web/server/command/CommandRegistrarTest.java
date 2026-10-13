package com.akashiic.web.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.command.ICommand;

import org.junit.jupiter.api.Test;

import com.akashiic.web.server.Admins;

/**
 * Review finding: the full node was passed as Crucible's group, and Crucible appends ".<command name>", so the real
 * nodes were akashicweb.web.web and akashicweb.webadmin.webadmin (LIVE-OBSERVED in the lead's Crucible boot log).
 */
class CommandRegistrarTest {

    /** Crucible's CommandHandler overload as observed: node = group + "." + command name. */
    public static final class CrucibleLikeManager {

        final List<String> nodes = new ArrayList<>();

        public ICommand registerCommand(String group, ICommand command) {
            nodes.add(group + "." + command.getCommandName());
            return command;
        }
    }

    /** Plain Forge: no grouped overload. */
    public static final class ForgeManager {
    }

    @Test
    void onCrucibleTheNodesAreTheDocumentedOnes() {
        CrucibleLikeManager crucible = new CrucibleLikeManager();
        List<ICommand> fallback = new ArrayList<>();
        assertEquals("group", CommandRegistrar.register(crucible, new WebCommand(), fallback::add));
        assertEquals("group", CommandRegistrar.register(crucible, new WebAdminCommand(), fallback::add));
        assertEquals(Arrays.asList("akashicweb.web", "akashicweb.webadmin"), crucible.nodes);
        assertEquals(Arrays.asList(Admins.NODE_WEB, Admins.NODE_WEBADMIN), crucible.nodes);
        assertTrue(fallback.isEmpty());
        assertEquals("akashicweb.web", CommandRegistrar.node(new WebCommand()), "what the log line says");
    }

    @Test
    void onPlainForgeTheStandardRegistrationIsUsed() {
        List<ICommand> fallback = new ArrayList<>();
        assertEquals("forge", CommandRegistrar.register(new ForgeManager(), new WebCommand(), fallback::add));
        assertEquals(1, fallback.size());
    }
}
