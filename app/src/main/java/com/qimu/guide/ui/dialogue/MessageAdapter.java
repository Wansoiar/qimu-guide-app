package com.qimu.guide.ui.dialogue;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;

import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_PHOTO = 0;
    private static final int TYPE_VOICE = 1;
    private static final int TYPE_AI_REPLY = 2;

    private final List<DialogueMessage> messages;

    public MessageAdapter(List<DialogueMessage> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        switch (messages.get(position).getType()) {
            case PHOTO: return TYPE_PHOTO;
            case VOICE: return TYPE_VOICE;
            case AI_REPLY: return TYPE_AI_REPLY;
            default: return TYPE_AI_REPLY;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_PHOTO) {
            return new PhotoViewHolder(inflater.inflate(R.layout.item_message_photo, parent, false));
        } else if (viewType == TYPE_VOICE) {
            return new VoiceViewHolder(inflater.inflate(R.layout.item_message_voice, parent, false));
        } else {
            return new AiViewHolder(inflater.inflate(R.layout.item_message_ai, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DialogueMessage msg = messages.get(position);
        String time = DateFormat.format("HH:mm:ss", msg.getTimestamp()).toString();

        if (holder instanceof PhotoViewHolder) {
            PhotoViewHolder h = (PhotoViewHolder) holder;
            h.tvTime.setText(time);
            if (msg.getImageFile() != null && msg.getImageFile().exists()) {
                h.ivPhoto.setImageURI(android.net.Uri.fromFile(msg.getImageFile()));
            }
        } else if (holder instanceof VoiceViewHolder) {
            VoiceViewHolder h = (VoiceViewHolder) holder;
            h.tvText.setText(msg.getText());
            h.tvTime.setText(time);
        } else if (holder instanceof AiViewHolder) {
            AiViewHolder h = (AiViewHolder) holder;
            h.tvText.setText(msg.getText());
            h.tvTime.setText(time);
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPhoto;
        TextView tvTime;
        PhotoViewHolder(View v) {
            super(v);
            ivPhoto = v.findViewById(R.id.iv_photo);
            tvTime = v.findViewById(R.id.tv_time);
        }
    }

    static class VoiceViewHolder extends RecyclerView.ViewHolder {
        TextView tvText, tvTime;
        VoiceViewHolder(View v) {
            super(v);
            tvText = v.findViewById(R.id.tv_text);
            tvTime = v.findViewById(R.id.tv_time);
        }
    }

    static class AiViewHolder extends RecyclerView.ViewHolder {
        TextView tvText, tvTime;
        AiViewHolder(View v) {
            super(v);
            tvText = v.findViewById(R.id.tv_text);
            tvTime = v.findViewById(R.id.tv_time);
        }
    }
}
