package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link CheckinProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(CheckinProperties.class)
public class CheckinConfig {}
