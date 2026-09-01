package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link SttProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(SttProperties.class)
public class SttConfig {}
