package com.fastasyncworldedit.core.util.task;

@FunctionalInterface
public interface EntityTask {

    void run(EntityTicket ticket);

}
