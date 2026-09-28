package com.qimu.guide.ui.dialogue;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.fragment.app.testing.FragmentScenario;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.qimu.guide.R;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.RealtimeGuideManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class TourTutorialTest {
    private static final String SESSION_ID = "tutorial-instrumentation";
    private FragmentScenario<DialogueFragment> scenario;

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
        TourSessionManager.get().completeSession(SESSION_ID, true);
    }

    @Test
    public void modalPreviewDoesNotCreateTour() throws Exception {
        assertFalse(TourSessionManager.get().isActive());
        scenario = FragmentScenario.launchInContainer(DialogueFragment.class, null,
                R.style.AppTheme);
        scenario.onFragment(fragment -> new TourTutorialDialogFragment().showNow(
                fragment.getChildFragmentManager(), TourTutorialDialogFragment.TAG));
        assertDialogBounds();
        assertStep("1 / 3 · AI 对话", "下一步");
        assertAnimationPlays();
        SystemClock.sleep(2100);
        capture("01-ai-dialogue");
        onView(withId(R.id.tutorial_next)).perform(click());
        assertStep("2 / 3 · 拍照提问", "下一步");
        assertAnimationPlays();
        SystemClock.sleep(2100);
        capture("02-photo-question");
        onView(withId(R.id.tutorial_next)).perform(click());
        assertStep("3 / 3 · 拍照记录", "开始参观");
        assertAnimationPlays();
        SystemClock.sleep(4400);
        capture("03-photo-record");
        onView(withId(R.id.tutorial_next)).perform(click());
        assertFalse(TourSessionManager.get().isActive());
        assertFalse(RealtimeGuideManager.get().isListeningDesired());
    }

    @Test
    public void tourEntryPlaysThreeStepsRestoresProgressAndShowsOncePerTour() throws Exception {
        TourSessionManager sessions = TourSessionManager.get();
        assertFalse(sessions.isActive());
        sessions.clearCleanupWarning();
        beginTour();
        scenario = FragmentScenario.launchInContainer(DialogueFragment.class, null,
                R.style.AppTheme);

        assertStep("1 / 3 · AI 对话", "下一步");
        onView(withId(R.id.tutorial_previous))
                .check(matches(withEffectiveVisibility(Visibility.GONE)));
        assertDialogBounds();
        assertAnimationPlays();
        capture("01-ai-dialogue");

        onView(withId(R.id.tutorial_next)).perform(click());
        assertStep("2 / 3 · 拍照提问", "下一步");
        assertAnimationPlays();
        capture("02-photo-question");

        scenario.recreate();
        assertStep("2 / 3 · 拍照提问", "下一步");
        assertAnimationPlays();
        onView(withId(R.id.tutorial_previous)).perform(click());
        assertStep("1 / 3 · AI 对话", "下一步");
        onView(withId(R.id.tutorial_next)).perform(click());
        onView(withId(R.id.tutorial_next)).perform(click());

        assertStep("3 / 3 · 拍照记录", "开始参观");
        assertAnimationPlays();
        capture("03-photo-record");
        onView(withId(R.id.tutorial_previous)).perform(click());
        assertStep("2 / 3 · 拍照提问", "下一步");
        onView(withId(R.id.tutorial_next)).perform(click());
        onView(withId(R.id.tutorial_next)).perform(click());
        onView(withId(R.id.tutorial_animation)).check(doesNotExist());
        onView(withId(R.id.btn_push_text)).check(matches(isDisplayed()));
        assertFalse(RealtimeGuideManager.get().isListeningDesired());

        scenario.recreate();
        onView(withId(R.id.tutorial_animation)).check(doesNotExist());
        scenario.close();
        scenario = null;
        assertTrue(sessions.completeSession(SESSION_ID, true));

        beginTour();
        scenario = FragmentScenario.launchInContainer(DialogueFragment.class, null,
                R.style.AppTheme);
        assertStep("1 / 3 · AI 对话", "下一步");
    }

    private void beginTour() {
        TourSessionManager sessions = TourSessionManager.get();
        assertTrue(sessions.beginSession(sessions.beginSessionRequest(), SESSION_ID,
                "", "tutorial-venue", "教程测试"));
    }

    private void assertStep(String title, String action) {
        onView(withId(R.id.tutorial_step_title)).check(matches(withText(title)));
        onView(withId(R.id.tutorial_next)).check(matches(withText(action)));
    }

    private void assertDialogBounds() {
        scenario.onFragment(fragment -> {
            TourTutorialDialogFragment tutorial = (TourTutorialDialogFragment)
                    fragment.getChildFragmentManager().findFragmentByTag(TourTutorialDialogFragment.TAG);
            assertNotNull(tutorial);
            android.view.View dialog = tutorial.requireDialog().getWindow().getDecorView();
            android.view.View activity = fragment.requireActivity().getWindow().getDecorView();
            assertTrue("Tutorial must leave the guide page visible around the modal",
                    dialog.getWidth() < activity.getWidth() && dialog.getHeight() < activity.getHeight());
            assertTrue(tutorial.requireDialog().getWindow().getAttributes().dimAmount > 0);
        });
    }

    private void assertAnimationPlays() {
        AtomicReference<TutorialAnimationView> animation = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 8_000;
        while (animation.get() == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onFragment(fragment -> {
                TourTutorialDialogFragment tutorial = (TourTutorialDialogFragment)
                        fragment.getChildFragmentManager()
                                .findFragmentByTag(TourTutorialDialogFragment.TAG);
                if (tutorial == null || tutorial.getView() == null) return;
                TutorialAnimationView view = tutorial.requireView().findViewById(R.id.tutorial_animation);
                if (view.isPlaying()) animation.set(view);
            });
            if (animation.get() == null) SystemClock.sleep(100);
        }
        assertNotNull("Bundled animation should play", animation.get());
        assertTrue("Decode at native HD resolution", animation.get().getVideoWidth() >= 1080);
        int position = animation.get().getCurrentPosition();
        SystemClock.sleep(350);
        assertTrue("Animation must advance instead of displaying a static poster",
                animation.get().getCurrentPosition() > position);
    }

    private void capture(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File folder = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "tutorial-verification");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
    }
}
