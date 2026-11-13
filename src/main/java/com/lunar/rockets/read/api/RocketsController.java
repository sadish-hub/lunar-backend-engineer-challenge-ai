package com.lunar.rockets.read.api;

import com.lunar.rockets.read.service.RocketReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rockets")
public class RocketsController {

    private final RocketReadService readService;

    public RocketsController(RocketReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    public List<RocketResponse> list(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        RocketSortField sortField = RocketSortField.parseQuery(sort).orElse(RocketSortField.ID);
        SortOrder sortOrder = SortOrder.parseQuery(order);
        return readService.list(sortField, sortOrder);
    }

    @GetMapping("/{id}")
    public RocketResponse get(@PathVariable UUID id) {
        return readService.get(id);
    }
}
