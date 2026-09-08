package com.silporestockai.repository;

import com.silporestockai.entity.GroupEventItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupEventItemRepository extends JpaRepository<GroupEventItem, UUID> {

    List<GroupEventItem> findByGroupEventId(UUID groupEventId);

    List<GroupEventItem> findByGroupEventIdIn(Collection<UUID> groupEventIds);
}
