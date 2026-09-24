package com.qimu.guide.ui.dialogue;

import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.service.SubtitleTranscript;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.qimu.guide.service.SubtitleTranscript.Source.BINARY;
import static com.qimu.guide.service.SubtitleTranscript.Source.SDK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Replays real callback shapes through the production deduplication and bubble projection. */
public final class SubtitleTimelineTest {
    private final SubtitleTranscript transcript = new SubtitleTranscript();
    private final SubtitleTimeline live = new SubtitleTimeline();

    @Test
    public void combinesSentencesInOneRoundButSeparatesConsecutiveAgentRounds() {
        receive(1, false, "第一句。", 1, 7, BINARY, 0);
        receive(1, false, "第二句。", 2, 7, BINARY, 5_000);
        receive(1, false, "新一轮。", 3, 8, BINARY, 5_100);
        assertBubbles("第一句。第二句。", "新一轮。");
    }

    @Test
    public void knownRoundDoesNotSplitAtFallbackTimeout() {
        receive(1, false, "开头。", 1, 7, BINARY, 0);
        receive(1, false, "长停顿后的同轮句子。", 2, 7, BINARY, 60_000);
        assertBubbles("开头。长停顿后的同轮句子。");
    }

    @Test
    public void reconnectGenerationKeepsRepeatedWelcomeAndSequenceIndependent() {
        receive(1, false, "欢迎。", 1, 1, BINARY, 0);
        receive(2, false, "欢迎。", 1, 1, SDK, 100);
        receive(2, false, "欢迎。", 1, 1, BINARY, 150);
        assertBubbles("欢迎。", "欢迎。");
    }

    @Test
    public void inPlaceRtcReconnectAlsoBreaksGroupsAndDeduplication() {
        receive(1, false, "欢迎。", 1, 1, BINARY, 0);
        transcript.breakGroup();
        receive(1, false, "欢迎。", 1, 1, BINARY, 100);
        assertBubbles("欢迎。", "欢迎。");
    }

    @Test
    public void lateBinaryRoundMetadataCorrectsSdkFirstProvisionalBubble() {
        receive(1, false, "旧轮。", 10, 0, SDK, 0);
        receive(1, false, "新轮。", 11, 0, SDK, 100);
        assertBubbles("旧轮。新轮。");
        receive(1, false, "旧轮。", 20, 4, BINARY, 200);
        receive(1, false, "新轮。", 21, 5, BINARY, 300);
        assertBubbles("旧轮。", "新轮。");
        assertEquals(2, transcript.snapshot().size());
        assertReplayMatchesLive();
    }

    @Test
    public void binaryFirstDuplicateDoesNotRenderTwice() {
        receive(1, false, "说明。", 10, 4, BINARY, 0);
        receive(1, false, "说明。", 30, 0, SDK, 100);
        receive(1, false, "说明。", 30, 0, SDK, 200);
        assertBubbles("说明。");
    }

    @Test
    public void sameSourceNewSequenceCanRepeatTextWithinSameRound() {
        receive(1, false, "对。", 1, 4, BINARY, 0);
        receive(1, false, "对。", 2, 4, BINARY, 100);
        receive(1, false, "对。", 10, 0, SDK, 150);
        receive(1, false, "对。", 11, 0, SDK, 200);
        assertBubbles("对。对。");
        assertEquals(2, transcript.snapshot().size());
    }

    @Test
    public void knownNewRoundCanRepeatTextEvenWithSameSequenceAndOppositeChannel() {
        receive(1, false, "好。", 1, 4, BINARY, 0);
        receive(1, false, "好。", 1, 5, SDK, 100);
        assertBubbles("好。", "好。");
    }

    @Test
    public void sdkOnlyRepeatedSentenceOutsideCrossChannelWindowSurvives() {
        receive(1, true, "你好。", 1, 0, SDK, 0);
        receive(1, true, "你好。", 1, 6, BINARY, 2_000);
        assertEquals(2, transcript.snapshot().size());
        assertBubbles("你好。你好。");
    }

    @Test
    public void lateOldRoundAndOutOfOrderSentenceUpdateOriginalBubble() {
        receive(1, false, "后句。", 2, 7, BINARY, 0);
        receive(1, false, "新轮。", 3, 8, BINARY, 100);
        receive(1, false, "前句。", 1, 7, BINARY, 200);
        receive(1, false, "新轮接续。", 4, 0, SDK, 300);
        assertBubbles("前句。后句。", "新轮。新轮接续。");
        assertReplayMatchesLive();
    }

    @Test
    public void unknownRoundCombinesFragmentsAndBreaksAfterSilence() {
        receive(1, false, "第一句。", 1, 0, SDK, 0);
        receive(1, false, "第二句。", 2, 0, SDK, 9_000);
        receive(1, false, "停顿后。", 3, 0, SDK, 19_001);
        assertBubbles("第一句。第二句。", "停顿后。");
    }

    @Test
    public void unknownRoundHasBoundedSpanEvenWithoutSilence() {
        for (int i = 0; i <= 5; i++) {
            receive(1, false, "句。", i, 0, SDK, i * 9_000L);
        }
        receive(1, false, "新段。", 6, 0, SDK, 45_001);
        assertBubbles("句。句。句。句。句。句。", "新段。");
    }

    @Test
    public void unknownRoundBreaksWhenSpeakerChanges() {
        receive(1, false, "回答。", 1, 0, SDK, 0);
        receive(1, true, "提问。", 2, 0, SDK, 100);
        receive(1, false, "再回答。", 3, 0, SDK, 200);
        assertBubbles("回答。", "提问。", "再回答。");
        assertEquals(DialogueMessage.Type.VOICE, live.snapshot().get(1).getType());
    }

    @Test
    public void laterKnownRoundAdoptsInitialUnknownSentence() {
        receive(1, false, "首句。", 1, 0, SDK, 0);
        receive(1, false, "同轮后句。", 2, 7, BINARY, 100);
        assertBubbles("首句。同轮后句。");
    }

    @Test
    public void photosAndStatusStayAheadOfFollowingExplanationEvenInSameRound() {
        receive(1, false, "拍照前。", 1, 7, BINARY, 0);
        DialogueMessage photo = new DialogueMessage(DialogueMessage.Type.PHOTO, "照片", 100);
        live.append(photo);
        live.append(new DialogueMessage(DialogueMessage.Type.STATUS_HINT, "已收到照片", 101));
        receive(1, false, "照片讲解。", 2, 7, BINARY, 200);
        // SDK duplicate and metadata refresh cannot move the explanation above the photo.
        receive(1, false, "照片讲解。", 10, 0, SDK, 300);
        assertBubbles("拍照前。", "照片", "已收到照片", "照片讲解。");
        assertEquals(photo, live.snapshot().get(1));
    }

    @Test
    public void snapshotReplayAndDuplicateReplayAreIdenticalToLive() {
        receive(1, true, "它是什么？", 1, 1, SDK, 0);
        receive(1, true, "它是什么？", 10, 1, BINARY, 20);
        receive(1, false, "这是铜器。", 11, 1, BINARY, 500);
        receive(1, false, "来自商代。", 12, 1, BINARY, 1_000);
        receive(1, false, "另一轮。", 13, 2, BINARY, 1_200);
        assertReplayMatchesLive();
    }

    @Test
    public void interimOrLateDuplicateCannotReplaceFinalText() {
        assertNull(transcript.record(1, false, "未完成", false, 1, 1, BINARY, 0, 0));
        receive(1, false, "完整回答。", 1, 1, BINARY, 100);
        receive(1, false, "被改写的重复 final", 1, 1, BINARY, 200);
        assertBubbles("完整回答。");
    }

    private void receive(int generation, boolean fromSelf, String text, int sequence, int round,
                         SubtitleTranscript.Source source, long time) {
        SubtitleTranscript.Entry entry = transcript.record(
                generation, fromSelf, text, true, sequence, round, source, time, time + 1_000_000);
        if (entry != null) live.upsert(entry);
    }

    private void assertBubbles(String... expected) {
        assertEquals(Arrays.asList(expected), texts(live));
    }

    private void assertReplayMatchesLive() {
        SubtitleTimeline replay = new SubtitleTimeline();
        for (SubtitleTranscript.Entry entry : transcript.snapshot()) replay.upsert(entry);
        for (SubtitleTranscript.Entry entry : transcript.snapshot()) replay.upsert(entry);
        assertEquals(texts(live), texts(replay));
    }

    private static List<String> texts(SubtitleTimeline timeline) {
        List<String> texts = new ArrayList<>();
        for (DialogueMessage message : timeline.snapshot()) texts.add(message.getText());
        return texts;
    }
}
