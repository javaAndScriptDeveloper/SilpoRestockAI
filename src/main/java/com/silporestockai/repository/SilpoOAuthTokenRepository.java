package com.silporestockai.repository;

import com.silporestockai.entity.SilpoOAuthToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to the encrypted Silpo MCP OAuth tokens. */
public interface SilpoOAuthTokenRepository extends JpaRepository<SilpoOAuthToken, UUID> {

    Optional<SilpoOAuthToken> findByUserId(UUID userId);
}
