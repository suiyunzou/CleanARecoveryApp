package com.example.cleanrecovery.ui.browser;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BrowserJsDialogPolicyTest {
    @Test public void rapidConfirmOffersSuppressionWithoutBlockingAnotherTypeOrHost() {
        BrowserJsDialogPolicy policy = new BrowserJsDialogPolicy();
        assertEquals(0, policy.request("a.test", true, 100000));
        assertEquals(0, policy.request("a.test", true, 101000));
        assertEquals(1, policy.request("a.test", true, 102000));
        policy.answer("a.test", true, true, 103000);
        assertEquals(2, policy.request("a.test", true, 104000));
        assertEquals(0, policy.request("a.test", false, 104000));
        assertEquals(0, policy.request("b.test", true, 104000));
        assertEquals(2, policy.request("a.test", true, 162999));
        assertEquals(0, policy.request("a.test", true, 163000));
    }

    @Test public void decliningSuppressionKeepsDialogsAvailableAndAnswerRestartsQuietInterval() {
        BrowserJsDialogPolicy policy = new BrowserJsDialogPolicy();
        policy.request("a.test", false, 100000);
        policy.request("a.test", false, 101000);
        assertEquals(1, policy.request("a.test", false, 102000));
        policy.answer("a.test", false, false, 130000);
        assertEquals(1, policy.request("a.test", false, 189999));
        assertEquals(0, policy.request("a.test", false, 249999));
    }
}
