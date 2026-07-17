package com.fastasyncworldedit.core.util.task;

import java.util.List;

/** Exact applied subset — the undo/report source of truth. */
public record AppliedReceipt(
        long appliedSectionBitmap,
        List<String> appliedTileIds,
        List<EntityAction> appliedEntities,      // UUID + action actually performed
        List<String> appliedPoiNeighborIds,
        long appliedLightSections,
        PacketPhaseResult packetResult
) {
}
