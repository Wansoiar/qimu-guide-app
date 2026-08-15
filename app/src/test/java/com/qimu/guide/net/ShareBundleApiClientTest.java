package com.qimu.guide.net;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MultipartBody;
import okio.Buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ShareBundleApiClientTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    @Test
    public void sharePhotoIncludesTrimmedTourSessionId() throws Exception {
        MultipartBody body = ShareBundleApiClient.buildPhotoUploadBody(
                "photo.jpg", "image/jpeg", new byte[]{1}, 0, "sha", " session-123 ");

        assertEquals("session-123", formValue(body, "session_id"));
    }

    @Test
    public void sharePhotoOmitsTourSessionIdWhenMissing() throws Exception {
        MultipartBody body = ShareBundleApiClient.buildPhotoUploadBody(
                "photo.jpg", "image/jpeg", new byte[]{1}, 0, "sha", null);

        assertNull(formValue(body, "session_id"));
    }

    @Test
    public void aiPhotoIncludesTrimmedTourSessionId() throws Exception {
        File photo = temporaryFolder.newFile("ai-photo.jpg");
        MultipartBody body = GuideApiClient.buildImageUploadBody(photo, " session-456 ");

        assertEquals("session-456", formValue(body, "session_id"));
    }

    @Test
    public void aiPhotoOmitsTourSessionIdWhenMissing() throws Exception {
        File photo = temporaryFolder.newFile("ai-photo.jpg");
        MultipartBody body = GuideApiClient.buildImageUploadBody(photo, "  ");

        assertNull(formValue(body, "session_id"));
    }

    private static String formValue(MultipartBody body, String name) throws IOException {
        String marker = "name=\"" + name + "\"";
        for (MultipartBody.Part part : body.parts()) {
            if (part.headers() == null) continue;
            String disposition = part.headers().get("Content-Disposition");
            if (disposition == null || !disposition.contains(marker)) continue;
            Buffer buffer = new Buffer();
            part.body().writeTo(buffer);
            return buffer.readUtf8();
        }
        return null;
    }
}
