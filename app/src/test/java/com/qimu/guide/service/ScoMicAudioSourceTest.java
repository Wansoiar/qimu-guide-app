package com.qimu.guide.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;

public class ScoMicAudioSourceTest {

    @Test
    public void transientAndDuckLossRemainRecoverable() {
        assertTrue(ScoMicAudioSource.isTransientFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));
        assertTrue(ScoMicAudioSource.isTransientFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));
    }

    @Test
    public void permanentLossIsNotRecoverable() {
        assertFalse(ScoMicAudioSource.isTransientFocusLoss(AudioManager.AUDIOFOCUS_LOSS));
        assertFalse(ScoMicAudioSource.isTransientFocusLoss(AudioManager.AUDIOFOCUS_GAIN));
    }
}
