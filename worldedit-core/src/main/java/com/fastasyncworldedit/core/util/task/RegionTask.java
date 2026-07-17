package com.fastasyncworldedit.core.util.task;

@FunctionalInterface
public interface RegionTask {

    void run(RegionTicket ticket);

}
