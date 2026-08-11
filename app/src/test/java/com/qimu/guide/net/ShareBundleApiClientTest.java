package com.qimu.guide.net;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public final class ShareBundleApiClientTest {

    @Test
    public void sha256MatchesKnownVector() throws Exception {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ShareBundleApiClient.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha256KeepsLeadingZeroes() throws Exception {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ShareBundleApiClient.sha256(new byte[0]));
    }
}
