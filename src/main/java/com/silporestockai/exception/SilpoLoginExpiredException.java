package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * The OAuth callback arrived for a login state older than {@code silpo.mcp.login-state-ttl}.
 *
 * <p>Its own type because the chat needs to say something different: the button that produced this callback is
 * the one still sitting in the greeting, and it will fail the same way every time it is tapped — so the person
 * needs a fresh one, not «try again».
 */
public class SilpoLoginExpiredException extends ApplicationException {

    public SilpoLoginExpiredException() {
        super(HttpStatus.BAD_REQUEST, "this login link has expired, please start again");
    }
}
