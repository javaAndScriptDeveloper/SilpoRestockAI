package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TelegramProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {}
