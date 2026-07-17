package com.fastasyncworldedit.bukkit.folia;

/**
 * Thrown when Folia is detected but the running server build falls outside FAWE's
 * certified matrix (architecture.md §2, spec.md §2b). Aborts plugin enable before any
 * listener, executor, or world access happens.
 */
public class UnsupportedFoliaVersionException extends Exception {

    public UnsupportedFoliaVersionException(String message) {
        super(message);
    }

}
