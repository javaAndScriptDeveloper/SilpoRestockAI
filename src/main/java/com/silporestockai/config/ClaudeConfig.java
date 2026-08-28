package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link ClaudeProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeConfig {}
