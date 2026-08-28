package com.silporestockai.support;

import com.silporestockai.client.mcp.SilpoAccessTokenProvider;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for {@code SilpoAuthService} so MCP client tests never touch the OAuth flow. The token changes after a
 * refresh, which lets a test prove the transport picked up the new value rather than replaying the stale header.
 */
public class RecordingTokenProvider implements SilpoAccessTokenProvider {

    public static final String INITIAL_TOKEN = "stub-access-token-0000";
    public static final String REFRESHED_TOKEN = "stub-access-token-1111";

    private final AtomicInteger refreshCount = new AtomicInteger();
    private volatile boolean refreshSucceeds = true;
    private volatile String token = INITIAL_TOKEN;

    @Override
    public String accessToken(UUID userId) {
        return token;
    }

    @Override
    public boolean refresh(UUID userId) {
        refreshCount.incrementAndGet();
        if (!refreshSucceeds) {
            return false;
        }
        token = REFRESHED_TOKEN;
        return true;
    }

    public int refreshCount() {
        return refreshCount.get();
    }

    public String currentToken() {
        return token;
    }

    public void refreshSucceeds(boolean succeeds) {
        this.refreshSucceeds = succeeds;
    }

    public void reset() {
        refreshCount.set(0);
        refreshSucceeds = true;
        token = INITIAL_TOKEN;
    }
}
