package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File helpers whose mistakes would delete or expose the player's own files. */
class FileOpsTest {

    @TempDir
    File tmp;

    @Test
    void deleteTreeRemovesALinkedFolderButNeverWhatItPointsTo() throws Exception {
        File victim = new File(tmp, "player-files");
        assertTrue(victim.mkdirs());
        File precious = new File(victim, "world.dat");
        Files.write(precious.toPath(), new byte[] { 1, 2, 3 });
        File trash = new File(tmp, "engine/.trash-x");
        assertTrue(trash.mkdirs());
        Files.write(new File(trash, "ours.bin").toPath(), new byte[] { 4 });
        Files.createSymbolicLink(new File(trash, "planted").toPath(), victim.toPath());

        assertTrue(FileOps.deleteTree(trash));
        assertFalse(trash.exists());
        assertTrue(precious.isFile(), "the link was removed, the folder it pointed to was not touched");
    }

    /**
     * Review finding (medium, 1d): on Windows an NTFS junction is a directory for File.isDirectory() and not a symbolic
     * link for Files.isSymbolicLink(); NIO reports it, read without following links, as "other" (a reparse point). It
     * must be removed as a link, never descended into.
     */
    @Test
    void aReparsePointThatLooksLikeAFolderIsNeverDescendedInto() {
        assertFalse(FileOps.descend(attrs(true, false, true)), "junction / mount point");
        assertFalse(FileOps.descend(attrs(false, true, false)), "symbolic link");
        assertFalse(FileOps.descend(attrs(false, false, false)), "regular file");
        assertTrue(FileOps.descend(attrs(true, false, false)), "plain folder");
    }

    private static BasicFileAttributes attrs(boolean directory, boolean symlink, boolean other) {
        return new BasicFileAttributes() {

            @Override
            public FileTime lastModifiedTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public FileTime lastAccessTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public FileTime creationTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public boolean isRegularFile() {
                return !directory && !symlink && !other;
            }

            @Override
            public boolean isDirectory() {
                return directory;
            }

            @Override
            public boolean isSymbolicLink() {
                return symlink;
            }

            @Override
            public boolean isOther() {
                return other;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public Object fileKey() {
                return null;
            }
        };
    }
}
