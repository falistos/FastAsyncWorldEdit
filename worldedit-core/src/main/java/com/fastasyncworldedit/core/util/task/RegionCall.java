package com.fastasyncworldedit.core.util.task;

@FunctionalInterface
public interface RegionCall<T> {

    T call(RegionTicket ticket);

}
