package com.example.company.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

public class SpringContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void setup() {
        Assertions.assertNotNull(applicationContext.getApplicationName());
    }
}
