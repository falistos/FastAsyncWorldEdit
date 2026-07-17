package com.fastasyncworldedit.core.util.task;

/** Six-state terminal classification (Proposal B §6.1). */
public enum TerminalStatus {
    NOT_ACCEPTED,
    NO_CHANGE,
    FAILED_BEFORE_MUTATION,
    COMMITTED,
    PARTIALLY_COMMITTED,
    CANCELLED_BEFORE_MUTATION
}
