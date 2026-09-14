package com.example.cleanrecovery.download;

import org.junit.Test;
import static org.junit.Assert.*;

public class YtDlpTransferProgressTest {
    @Test public void parsesActualAndEstimatedBytes() {
        YtDlpTransferProgress exact = YtDlpTransferProgress.parse("__SHU_PROGRESS__{\"downloaded\":512,\"total\":1024,\"estimate\":2048}");
        assertEquals(50, exact.percent()); assertFalse(exact.estimated); assertEquals(1024, exact.total);
        YtDlpTransferProgress estimate = YtDlpTransferProgress.parse("__SHU_PROGRESS__{\"downloaded\":512,\"estimate\":2048,\"vcodec\":\"none\"}");
        assertEquals(25, estimate.percent()); assertTrue(estimate.estimated); assertTrue(estimate.audio);
    }
    @Test public void handlesUnknownTotalAndUnrelatedOutput() {
        assertEquals(-1, YtDlpTransferProgress.parse("__SHU_PROGRESS__{\"downloaded\":2048}").percent());
        assertNull(YtDlpTransferProgress.parse("[Merger] Merging formats"));
        assertNull(YtDlpTransferProgress.parse("__SHU_PROGRESS__bad"));
    }
}
