package com.example.company.config;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class JobLoggingAspect {

    @SneakyThrows
    @Around("@annotation(scheduled)")
    public Object logAround(ProceedingJoinPoint joinPoint, Scheduled scheduled) {

        var className = joinPoint.getTarget().getClass().getSimpleName();
        var methodName = joinPoint.getSignature().getName();

        log.debug("Started {}.{}()", className, methodName);
        try {
            var result = joinPoint.proceed();
            log.debug("Finished {}.{}()", className, methodName);
            return result;
        } catch (Throwable throwable) {
            log.error("Error occurred in {}: {}", methodName, throwable.getMessage());
            throw throwable;
        }
    }
}
