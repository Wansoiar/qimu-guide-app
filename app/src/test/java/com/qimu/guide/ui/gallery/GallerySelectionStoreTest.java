package com.qimu.guide.ui.gallery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GallerySelectionStoreTest {

    @Test
    public void thirtiethPhotoCanBeSelected() {
        assertTrue(GallerySelectionStore.canAddSelection(29));
    }

    @Test
    public void thirtyFirstPhotoIsRejected() {
        assertFalse(GallerySelectionStore.canAddSelection(30));
    }
}
