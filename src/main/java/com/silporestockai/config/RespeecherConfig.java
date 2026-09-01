package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link RespeecherProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(RespeecherProperties.class)
public class RespeecherConfig {}
