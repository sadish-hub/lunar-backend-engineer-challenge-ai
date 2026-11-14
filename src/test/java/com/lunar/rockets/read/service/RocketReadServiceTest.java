package com.lunar.rockets.read.service;

import com.lunar.rockets.apply.persistence.RocketEntity;
import com.lunar.rockets.apply.persistence.RocketRepository;
import com.lunar.rockets.read.api.RocketResponse;
import com.lunar.rockets.read.api.RocketSortField;
import com.lunar.rockets.read.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class RocketReadServiceTest {

    @Mock
    RocketRepository rockets;

    @InjectMocks
    RocketReadService service;

    @Test
    void listUsesPrimarySortThenIdAsc() {
        when(rockets.findAll(any(Sort.class))).thenReturn(List.of());

        service.list(RocketSortField.SPEED, SortOrder.DESC);

        ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
        verify(rockets).findAll(sortCaptor.capture());
        Sort sort = sortCaptor.getValue();
        assertThat(sort.getOrderFor("speed").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listByLastAppliedNumberUsesIdTieBreak() {
        when(rockets.findAll(any(Sort.class))).thenReturn(List.of());

        service.list(RocketSortField.LAST_APPLIED_NUMBER, SortOrder.ASC);

        ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
        verify(rockets).findAll(sortCaptor.capture());
        Sort sort = sortCaptor.getValue();
        assertThat(sort.getOrderFor("lastAppliedNumber").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void getMapsEntityToResponse() {
        UUID id = UUID.randomUUID();
        when(rockets.findById(id)).thenReturn(Optional.of(
                new RocketEntity(id, "Falcon-9", 10L, "ARTEMIS", false, null, 1L, 1L)));

        RocketResponse response = service.get(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.type()).isEqualTo("Falcon-9");
        assertThat(response.speed()).isEqualTo(10L);
        assertThat(response.mission()).isEqualTo("ARTEMIS");
        assertThat(response.exploded()).isFalse();
        assertThat(response.explosionReason()).isNull();
    }

    @Test
    void getMissingThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(rockets.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(NOT_FOUND);
    }
}
