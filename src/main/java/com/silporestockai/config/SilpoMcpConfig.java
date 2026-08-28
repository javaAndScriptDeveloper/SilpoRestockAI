package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link SilpoMcpProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(SilpoMcpProperties.class)
public class SilpoMcpConfig {}
