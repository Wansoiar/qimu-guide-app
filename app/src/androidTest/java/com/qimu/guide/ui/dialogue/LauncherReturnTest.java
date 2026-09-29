package com.qimu.guide.ui.dialogue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.qimu.guide.MainActivity;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.provisioning.LoginActivity;
import com.qimu.guide.provisioning.ProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** Verifies the real initialized launcher route without starting a tour or contacting the API. */
@RunWith(AndroidJUnit4.class)
public class LauncherReturnTest {
    @Test public void initializedLauncherReusesExistingMainActivity() {
        assertFalse(TourSessionManager.get().isActive());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProvisioningStore store = ProvisioningStore.get(context);
        ProvisioningApi.ProvisioningSnapshot previous = store.snapshot();
        assertTrue(store.save(new ProvisioningApi.ProvisioningSnapshot(
                "launcher-test-device", "launcher-test-phone", "", "",
                new ProvisioningApi.Venue("launcher-test-venue", "test", "Test venue", ""), 0)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original = new AtomicReference<>();
            scenario.onActivity(activity -> {
                original.set(activity);
                activity.startActivity(new Intent(activity, LoginActivity.class));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse("Launcher must not clear the live MainActivity", original.get().isDestroyed());
            scenario.onActivity(activity -> assertSame(original.get(), activity));
        } finally {
            if (previous != null) store.save(previous);
            else store.clearProvisioning();
        }
    }
}
