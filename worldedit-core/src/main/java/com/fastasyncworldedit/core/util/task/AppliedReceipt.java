package com.fastasyncworldedit.core.util.task;

import java.util.List;
import java.util.Objects;

/** Exact applied subset — the undo/report source of truth. */
public record AppliedReceipt(
        long appliedSectionBitmap,
        List<String> appliedTileIds,
        List<EntityAction> appliedEntities,      // UUID + action actually performed
        List<String> appliedPoiNeighborIds,
        long appliedLightSections,
        PacketPhaseResult packetResult,
        HistorySettlement historySettlement      // finalized before result publication (r4 am. 2)
) {

    public AppliedReceipt {
        appliedTileIds = List.copyOf(appliedTileIds);
        appliedEntities = List.copyOf(appliedEntities);
        appliedPoiNeighborIds = List.copyOf(appliedPoiNeighborIds);
        Objects.requireNonNull(packetResult, "packetResult");
        Objects.requireNonNull(historySettlement, "historySettlement");
    }

}
