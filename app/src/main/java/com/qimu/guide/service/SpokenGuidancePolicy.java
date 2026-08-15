package com.qimu.guide.service;

/**
 * 端侧过程引导固定文案。
 *
 * <p>拍照意图由 RTC Function Calling 判断；端侧只在真正预留拍照任务后播放，
 * 不再根据用户字幕提前播报，避免引导音回灌触发第二次拍照。</p>
 */
public final class SpokenGuidancePolicy {

    public static final String VISION = "让我来看一看～";

    private SpokenGuidancePolicy() {
    }
}
