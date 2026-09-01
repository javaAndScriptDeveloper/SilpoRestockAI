package com.silporestockai.repository;

import com.silporestockai.entity.GoogleOAuthToken;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Google OAuth tokens, one row per user. Absence of a row means "this user never connected a calendar". */
public interface GoogleOAuthTokenRepository extends JpaRepository<GoogleOAuthToken, UUID> {}
