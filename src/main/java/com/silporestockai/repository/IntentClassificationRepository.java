package com.silporestockai.repository;

import com.silporestockai.entity.IntentClassification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentClassificationRepository extends JpaRepository<IntentClassification, UUID> {}
