package com.qimu.guide.ui.dialogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import androidx.fragment.app.testing.FragmentScenario;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.net.TourSessionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/** Exercises the real Fragment and adapter; no BLE, RTC or backend calls. */
@RunWith(AndroidJUnit4.class)
public class DialogueHistoryLifecycleTest {
    private final String sessionId = "dialogue-history-test-" + UUID.randomUUID();
    private FragmentScenario<DialogueFragment> scenario;
    private File photo;

    @Before public void setUp() throws Exception {
        TourSessionManager sessions = TourSessionManager.get();
        assertFalse(sessions.isActive());
        // A previous instrumentation process may have been killed before its async prefs flush.
        sessions.clearCleanupWarning();
        assertTrue(sessions.beginSession(sessions.beginSessionRequest(), sessionId,
                "", "history-test-venue", "History test"));
        sessions.consumeFirstTutorial();
        photo = File.createTempFile("dialogue-history-", ".jpg",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(photo)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        } finally {
            bitmap.recycle();
        }
        scenario = launch();
        appendPhoto();
        appendPhoto();
        assertPhotoCount(2);
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        TourSessionManager.get().completeSession(sessionId, true);
        if (photo != null) photo.delete();
    }

    @Test public void backgroundAndResumeKeepPhotos() {
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        assertPhotoCount(2);
    }

    @Test public void activityRecreationKeepsPhotos() {
        scenario.recreate();
        assertPhotoCount(2);
    }

    @Test public void reopeningPageInSameTourKeepsPhotos() {
        scenario.close();
        scenario = launch();
        assertPhotoCount(2);
    }

    private FragmentScenario<DialogueFragment> launch() {
        return FragmentScenario.launchInContainer(DialogueFragment.class, null, R.style.AppTheme);
    }

    private void appendPhoto() throws Exception {
        // Inject the already-received photo at the same UI event boundary used by onVisionImage.
        Method append = DialogueFragment.class.getDeclaredMethod(
                "appendMessageDirect", DialogueMessage.class);
        append.setAccessible(true);
        scenario.onFragment(fragment -> {
            try {
                append.invoke(fragment, new DialogueMessage(
                        DialogueMessage.Type.PHOTO, photo, System.currentTimeMillis()));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private void assertPhotoCount(int expected) {
        scenario.onFragment(fragment -> {
            RecyclerView list = fragment.requireView().findViewById(R.id.recycler_messages);
            RecyclerView.Adapter<?> adapter = list.getAdapter();
            int count = 0;
            for (int i = 0; i < adapter.getItemCount(); i++) {
                if (adapter.getItemViewType(i) == 0) count++;
            }
            assertEquals("Photo rows must survive within the current tour", expected, count);
            assertTrue("History restoration must not delete the underlying image", photo.isFile());
        });
    }
}
