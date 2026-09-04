package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link AdHocScheduleProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(AdHocScheduleProperties.class)
public class AdHocScheduleConfig {}
