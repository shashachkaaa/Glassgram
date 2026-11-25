package com.exteragram.messenger.ai.network;

/**
 * Compatibility contract for plugins written against the exteraGram AI callback API.
 * Providing this interface allows optional AI integrations to be imported even when
 * Glassgram does not provide the AI service itself.
 *
 * API reference: exteraSquad/exteraGram, GenerationCallback.
 */
public interface GenerationCallback {
    default void onThinking() {
        // Optional notification.
    }

    void onChunk(String chunk);

    void onResponse(String response);

    void onError(int code, String message);
}
