package com.akashiic.web.client.install;

import static com.akashiic.web.client.install.TestSupport.env;
import static com.akashiic.web.client.install.TestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Install-location rules (ADR-011, ADR-014 P1 "C:\Users\João Conceição"), exercised for Windows on any OS. */
class InstallPathsTest {

    private static final String JOAO = "C:\\Users\\Jo\u00e3o Concei\u00e7\u00e3o\\AppData\\Local";
    private static final File GAME = new File("C:\\Users\\x\\AppData\\Roaming\\.technic\\modpacks\\forbidden");

    private static InstallPaths.Inputs windows(Map<String, String> env) {
        return new InstallPaths.Inputs(true, facts("Windows 10", "amd64", "64", env), "C:\\Users\\x", GAME)
            .usable(f -> true);
    }

    @Test
    void asciiLocalAppDataIsUsedDirectly() throws Exception {
        InstallPaths p = InstallPaths.resolve(windows(env("LOCALAPPDATA", "C:\\Users\\gabriel\\AppData\\Local")));
        assertEquals("C:\\Users\\gabriel\\AppData\\Local\\AkashicWeb", p.base.getPath());
        assertEquals("LOCALAPPDATA", p.how);
        assertEquals("%LOCALAPPDATA%\\AkashicWeb", p.display);
        assertEquals(new File(p.base, "import"), p.importDir());
        assertEquals(new File(p.base, "profiles"), p.profilesDir());
    }

    @Test
    void nonAsciiProfileUsesTheShortPathWhenWindowsHasOne() throws Exception {
        List<File> asked = new ArrayList<>();
        InstallPaths p = InstallPaths
            .resolve(windows(env("LOCALAPPDATA", JOAO, "PROGRAMDATA", "C:\\ProgramData")).shortPath(f -> {
                asked.add(f);
                return "C:\\Users\\JOOCON~1\\AppData\\Local";
            }));
        assertEquals("C:\\Users\\JOOCON~1\\AppData\\Local\\AkashicWeb", p.base.getPath());
        assertEquals("%LOCALAPPDATA%\\AkashicWeb", p.display, "the same folder, as the player can type it");
        assertEquals(
            JOAO,
            asked.get(0)
                .getPath());
        assertTrue(InstallPaths.isAscii(p.base.getPath()));
    }

    @Test
    void nonAsciiWithoutShortNameUsesTheNonAsciiFolderNeverProgramData() throws Exception {
        // Review finding: ProgramData is writable by every local user, so it is never a fallback, even when set.
        InstallPaths p = InstallPaths.resolve(windows(env("LOCALAPPDATA", JOAO, "PROGRAMDATA", "C:\\ProgramData")));
        assertEquals(JOAO + "\\AkashicWeb", p.base.getPath());
        assertEquals("LOCALAPPDATA (non-ASCII)", p.how);
        assertEquals(new File(p.base, "profiles"), p.profilesDir(), "cookies stay in the player's own folder");
        assertEquals("%LOCALAPPDATA%\\AkashicWeb", p.display);
    }

    @Test
    void shortPathThatIsStillNonAsciiIsIgnored() throws Exception {
        InstallPaths p = InstallPaths
            .resolve(windows(env("LOCALAPPDATA", JOAO, "PROGRAMDATA", "C:\\ProgramData")).shortPath(f -> JOAO));
        assertEquals(JOAO + "\\AkashicWeb", p.base.getPath());
    }

    @Test
    void anUnwritableLocalAppDataNeverFallsBackToProgramData() {
        InstallException e = assertThrows(
            InstallException.class,
            () -> InstallPaths.resolve(
                windows(env("LOCALAPPDATA", "C:\\Users\\a\\AppData\\Local", "PROGRAMDATA", "C:\\ProgramData")).usable(
                    f -> f.getPath()
                        .startsWith("C:\\ProgramData"))));
        assertEquals(InstallFailure.INSTALL_DIR_NOT_WRITABLE, e.failure);
        assertTrue(e.detail.contains("%LOCALAPPDATA%\\AkashicWeb is not writable"), e.detail);
        assertFalse(e.detail.contains("C:\\Users"), "no expanded profile path in the detail: " + e.detail);
    }

    @Test
    void nothingWritableIsInstallDirNotWritable() {
        InstallException e = assertThrows(
            InstallException.class,
            () -> InstallPaths
                .resolve(windows(env("LOCALAPPDATA", "C:\\a", "PROGRAMDATA", "C:\\b")).usable(f -> false)));
        assertEquals(InstallFailure.INSTALL_DIR_NOT_WRITABLE, e.failure);
    }

    @Test
    void relativeOrMissingLocalAppDataUsesTheProfileFolder() throws Exception {
        InstallPaths p = InstallPaths.resolve(windows(env("LOCALAPPDATA", "AppData\\Local")));
        assertEquals(
            "C:\\Users\\x\\AppData\\Local\\AkashicWeb",
            p.base.getPath(),
            "a relative value would follow the CWD");
        assertEquals("C:\\Users\\x\\AppData\\Local\\AkashicWeb", InstallPaths.resolve(windows(env())).base.getPath());
    }

    @Test
    void oneDriveDocumentsAndTheGameFolderAreNeverUsed() {
        InstallPaths.Inputs in = windows(env());
        assertFalse(InstallPaths.allowed("C:\\Users\\x\\OneDrive\\AppData\\AkashicWeb", in));
        assertFalse(InstallPaths.allowed("C:\\Users\\x\\OneDrive - Rede Akashic\\AkashicWeb", in));
        assertFalse(InstallPaths.allowed("C:\\Users\\x\\Documents\\AkashicWeb", in));
        assertFalse(InstallPaths.allowed(GAME.getPath() + "\\mods\\AkashicWeb", in));
        assertFalse(InstallPaths.allowed(GAME.getPath(), in));
        assertTrue(InstallPaths.allowed("C:\\Users\\x\\AppData\\Local\\AkashicWeb", in));
        assertTrue(InstallPaths.allowed(GAME.getPath() + "-other\\AkashicWeb", in));
    }

    @Test
    void oneDriveRedirectedLocalAppDataUsesTheUserProfileFolder() throws Exception {
        InstallPaths p = InstallPaths
            .resolve(windows(env("LOCALAPPDATA", "C:\\Users\\x\\OneDrive\\Local", "PROGRAMDATA", "C:\\ProgramData")));
        assertEquals("C:\\Users\\x\\AppData\\Local\\AkashicWeb", p.base.getPath());
        assertEquals("user profile", p.how);
        assertEquals("%USERPROFILE%\\AppData\\Local\\AkashicWeb", p.display);
    }

    @Test
    void oneDriveEverywhereIsInstallDirNotWritable() {
        InstallPaths.Inputs in = new InstallPaths.Inputs(
            true,
            facts("Windows 11", "amd64", "64", env("LOCALAPPDATA", "C:\\Users\\x\\OneDrive\\Local")),
            "C:\\Users\\x\\OneDrive",
            GAME).usable(f -> true);
        InstallException e = assertThrows(InstallException.class, () -> InstallPaths.resolve(in));
        assertEquals(InstallFailure.INSTALL_DIR_NOT_WRITABLE, e.failure);
        assertTrue(e.detail.contains("OneDrive"), e.detail);
    }

    @Test
    void absoluteChecksFollowTheTargetOs() {
        assertEquals("C:\\x", InstallPaths.absolute("C:\\x", true));
        assertEquals("\\\\server\\share", InstallPaths.absolute("\\\\server\\share", true));
        assertNull(InstallPaths.absolute("x\\y", true));
        assertNull(InstallPaths.absolute("/home/x", true));
        assertEquals("/home/x", InstallPaths.absolute("/home/x", false));
        assertNull(InstallPaths.absolute("C:\\x", false));
    }

    @Test
    void linuxUsesXdgDataHomeThenLocalShare() throws Exception {
        InstallPaths.Inputs xdg = new InstallPaths.Inputs(
            false,
            facts("Linux", "amd64", "64", env("XDG_DATA_HOME", "/data/xdg")),
            "/home/p",
            new File("/home/p/.minecraft")).usable(f -> true);
        assertEquals(
            "/data/xdg/AkashicWeb",
            InstallPaths.resolve(xdg).base.getPath()
                .replace('\\', '/'));
        InstallPaths.Inputs relative = new InstallPaths.Inputs(
            false,
            facts("Linux", "amd64", "64", env("XDG_DATA_HOME", "rel/xdg")),
            "/home/p",
            new File("/home/p/.minecraft")).usable(f -> true);
        InstallPaths p = InstallPaths.resolve(relative);
        assertEquals(
            "/home/p/.local/share/AkashicWeb",
            p.base.getPath()
                .replace('\\', '/'));
        assertEquals("~/.local/share", p.how);
    }

    @Test
    void layoutUnderTheBase(@TempDir File tmp) {
        InstallPaths p = new InstallPaths(new File(tmp, "eng"), "test", "eng");
        assertEquals(new File(tmp, "eng/engine"), p.engineRoot());
        assertEquals(new File(tmp, "eng/download"), p.downloadDir());
        assertEquals(new File(tmp, "eng/install.lock"), p.lockFile());
        assertEquals(new File(tmp, "eng/profiles"), p.profilesDir());
        assertEquals(new File(tmp, "eng/import"), p.importDir());
    }

    @Test
    void redactionHidesTheExpandedProfilePath() throws Exception {
        InstallPaths p = InstallPaths.resolve(windows(env("LOCALAPPDATA", JOAO)));
        String raw = "java.nio.file.AccessDeniedException: " + JOAO
            + "\\AkashicWeb\\engine\\x -> "
            + JOAO
            + "\\AkashicWeb\\engine\\.trash-x";
        assertEquals(
            "java.nio.file.AccessDeniedException: %LOCALAPPDATA%\\AkashicWeb\\engine\\x -> "
                + "%LOCALAPPDATA%\\AkashicWeb\\engine\\.trash-x",
            p.redact(raw));
        assertEquals(
            "%USERPROFILE%\\AppData\\Roaming\\.technic",
            p.redact("C:\\Users\\x\\AppData\\Roaming\\.technic"),
            "the user's home folder is hidden too");
        // (On Windows the separator is a backslash too; on the test OS File appends its own.)
        assertEquals("%LOCALAPPDATA%\\AkashicWeb" + File.separator + "import", p.display(p.importDir()));
    }

    @Test
    void probeWritableCreatesTheFolderAndLeavesNothing(@TempDir File tmp) {
        File dir = new File(tmp, "a/b");
        assertTrue(InstallPaths.probeWritable(dir));
        assertTrue(dir.isDirectory());
        assertEquals(0, dir.list().length);
    }

    @Test
    void shortPathOutputParsing() {
        String shortPath = "C:\\Users\\JOOCON~1\\AppData\\Local";
        assertEquals(shortPath, WindowsShortPath.parse(shortPath + "\r\n"));
        assertNull(WindowsShortPath.parse("C:\\Users\\Jo\u00e3o\\AppData\\Local\r\n"), "8.3 names disabled: long path");
        assertNull(WindowsShortPath.parse("'for' is not recognized"));
        assertNull(WindowsShortPath.parse(""));
        assertNull(WindowsShortPath.parse(null));
    }

    @Test
    void shortPathRunsCmdInTheFolderWithoutPuttingThePathOnTheCommandLine() {
        List<List<String>> commands = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        String out = WindowsShortPath.of(new File(JOAO), (cmd, dir, timeout) -> {
            commands.add(cmd);
            dirs.add(dir);
            return new CommandRunner.Result(0, "C:\\Users\\JOOCON~1\\AppData\\Local\r\n");
        }, "C:\\Windows");
        assertEquals("C:\\Users\\JOOCON~1\\AppData\\Local", out);
        assertEquals(new File(JOAO), dirs.get(0));
        for (String token : commands.get(0)) assertFalse(token.contains("Jo\u00e3o"), token);
        assertTrue(
            commands.get(0)
                .get(0)
                .endsWith("cmd.exe"));
    }
}
