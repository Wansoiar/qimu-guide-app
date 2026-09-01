package com.qimu.guide.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import com.qimu.guide.R;

/**
 * 宋体标题 TextView。
 *
 * 部分 ROM（如 vivo）会忽略布局 XML 中的 android:fontFamily 属性，
 * 导致 @font/noto_serif_sc_bold 不生效、中文回退到黑体。
 * 这里统一在代码里通过 Resources.getFont() 加载并 setTypeface，保证标题真正渲染宋体。
 */
public class SerifTextView extends AppCompatTextView {

    private static Typeface sSerifBold;

    private static Typeface serifBold(Context context) {
        if (sSerifBold == null) {
            try {
                sSerifBold = context.getResources().getFont(R.font.noto_serif_sc_bold);
            } catch (Throwable ignored) {
                sSerifBold = null;
            }
        }
        return sSerifBold;
    }

    public SerifTextView(Context context) {
        this(context, null);
    }

    public SerifTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SerifTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        Typeface serif = serifBold(context);
        if (serif != null) {
            // 注意：不要带 BOLD 样式参数。部分 ROM 的 Typeface.create(font, BOLD)
            // 会返回系统默认黑体；字体文件本身已是 Bold 字重，直接设置即可。
            setTypeface(serif);
        }
        // CJK 字体自带较大的行内留白，标题会显得上下很空，这里收紧。
        setIncludeFontPadding(false);
    }
}
