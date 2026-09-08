package com.example.cleanrecovery.proxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** SS 地址帧编解码单测。 */
public class AddressFrameTest {

    @Test
    public void domainFrameRoundTrip() {
        byte[] frame = SsAddress.buildFrame("www.example.com", 443);
        assertEquals(1 + 1 + 15 + 2, frame.length); // ATYP + len + domain + port
        assertEquals(0x03, frame[0] & 0xFF);
        SsAddress.Parsed p = SsAddress.parse(frame, 0);
        assertEquals("www.example.com", p.host);
        assertEquals(443, p.port);
        assertEquals(frame.length, p.consumed);
    }

    @Test
    public void ipv4FrameRoundTrip() {
        byte[] frame = SsAddress.buildFrame("127.0.0.1", 8080);
        assertEquals(0x01, frame[0] & 0xFF);
        SsAddress.Parsed p = SsAddress.parse(frame, 0);
        assertEquals("127.0.0.1", p.host);
        assertEquals(8080, p.port);
    }

    @Test
    public void portBigEndian() {
        byte[] frame = SsAddress.buildFrame("a.b", 443);
        // 443 = 0x01BB
        int len = frame.length;
        assertEquals(0x01, frame[len - 2] & 0xFF);
        assertEquals(0xBB, frame[len - 1] & 0xFF);
    }
}
