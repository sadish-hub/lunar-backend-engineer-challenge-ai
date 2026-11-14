package com.lunar.rockets.read.service;

import com.lunar.rockets.apply.persistence.RocketEntity;
import com.lunar.rockets.apply.persistence.RocketRepository;
import com.lunar.rockets.read.api.RocketResponse;
import com.lunar.rockets.read.api.RocketSortField;
import com.lunar.rockets.read.api.SortOrder;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class RocketReadService {

    private final RocketRepository rockets;

    public RocketReadService(RocketRepository rockets) {
        this.rockets = rockets;
    }

    public List<RocketResponse> list(RocketSortField sortField, SortOrder order) {
        Sort.Order primary = new Sort.Order(order.toDirection(), sortField.property());
        Sort.Order secondary = Sort.Order.asc("id");
        Sort sort = sortField == RocketSortField.ID
                ? Sort.by(primary)
                : Sort.by(primary, secondary);
        return rockets.findAll(sort).stream().map(this::toResponse).toList();
    }

    public RocketResponse get(UUID id) {
        return rockets.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rocket not found"));
    }

    private RocketResponse toResponse(RocketEntity entity) {
        return new RocketResponse(
                entity.getId(),
                entity.getType(),
                entity.getSpeed(),
                entity.getMission(),
                entity.isExploded(),
                entity.getExplosionReason());
    }
}
