package com.qimu.guide.ui.dialogue;

import android.os.Bundle;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.qimu.guide.R;

/** Three offline tutorials shown at the start of a visitor's tour. */
public class TourTutorialDialogFragment extends AppCompatDialogFragment {
    public static final String TAG = "tour_tutorial";
    private static final String STATE_STEP = "tutorial_step";
    private static final Step[] STEPS = {
            new Step(R.raw.tutorial_ai_dialogue, R.drawable.tutorial_ai_dialogue_poster, R.string.tutorial_ai_dialogue_title,
                    R.string.tutorial_ai_dialogue_description),
            new Step(R.raw.tutorial_photo_question, R.drawable.tutorial_photo_question_poster, R.string.tutorial_photo_question_title,
                    R.string.tutorial_photo_question_description),
            new Step(R.raw.tutorial_photo_record, R.drawable.tutorial_photo_record_poster, R.string.tutorial_photo_record_title,
                    R.string.tutorial_photo_record_description)
    };

    private int currentStep;
    private TutorialAnimationView animation;
    private TextView stepTitle;
    private LinearProgressIndicator progress;
    private MaterialButton previousButton;
    private MaterialButton nextButton;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.Theme_Qimu_Tutorial);
        setCancelable(false);
        if (savedInstanceState != null) {
            currentStep = Math.max(0, Math.min(STEPS.length - 1,
                    savedInstanceState.getInt(STATE_STEP, 0)));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_tour_tutorial, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        animation = view.findViewById(R.id.tutorial_animation);
        stepTitle = view.findViewById(R.id.tutorial_step_title);
        progress = view.findViewById(R.id.tutorial_progress);
        previousButton = view.findViewById(R.id.tutorial_previous);
        nextButton = view.findViewById(R.id.tutorial_next);
        previousButton.setOnClickListener(clicked -> {
            if (currentStep > 0) {
                currentStep--;
                renderStep();
            }
        });
        nextButton.setOnClickListener(clicked -> {
            if (currentStep < STEPS.length - 1) {
                currentStep++;
                renderStep();
            } else {
                // Match the original "开始参观": close the tutorial without starting the mic.
                dismiss();
            }
        });
        renderStep();
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null) {
            Rect bounds = new Rect();
            requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
            float density = getResources().getDisplayMetrics().density;
            int width = Math.min(bounds.width() - Math.round(48 * density),
                    Math.round(520 * density));
            int contentHeight = Math.round((width - 32 * density) * 4f / 3f + 128 * density);
            int height = Math.min(contentHeight, Math.round(bounds.height() * .84f));
            window.setLayout(Math.max(1, width), Math.max(1, height));
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(.48f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (animation != null) animation.resumePlayback();
    }

    @Override
    public void onPause() {
        if (animation != null) animation.pausePlayback();
        super.onPause();
    }

    private void renderStep() {
        Step step = STEPS[currentStep];
        stepTitle.setText(getString(R.string.tutorial_step_title,
                currentStep + 1, STEPS.length, getString(step.title)));
        progress.setMax(STEPS.length);
        progress.setProgressCompat(currentStep + 1, false);
        animation.setContentDescription(getString(step.description));
        previousButton.setVisibility(currentStep == 0 ? View.GONE : View.VISIBLE);
        nextButton.setText(currentStep == STEPS.length - 1
                ? R.string.dialogue_tutorial_action : R.string.tutorial_next);

        animation.setVideo(step.animation, step.poster);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_STEP, currentStep);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (animation != null) animation.releasePlayback();
        animation = null;
        stepTitle = null;
        progress = null;
        previousButton = null;
        nextButton = null;
        super.onDestroyView();
    }

    private static final class Step {
        final int animation;
        final int poster;
        final int title;
        final int description;

        Step(int animation, int poster, int title, int description) {
            this.animation = animation;
            this.poster = poster;
            this.title = title;
            this.description = description;
        }
    }
}
