package com.lunar.rockets.apply.service;

import com.lunar.rockets.apply.config.ApplyProperties;
import com.lunar.rockets.apply.domain.ApplyCommand;
import com.lunar.rockets.apply.domain.ApplyCommandParser;
import com.lunar.rockets.apply.domain.RocketState;
import com.lunar.rockets.apply.domain.RocketStateReducer;
import com.lunar.rockets.apply.persistence.ApplyMessageRepository;
import com.lunar.rockets.apply.persistence.RocketEntity;
import com.lunar.rockets.apply.persistence.RocketRepository;
import com.lunar.rockets.ingest.persistence.MessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ChannelApplyService {

    private static final Logger log = LoggerFactory.getLogger(ChannelApplyService.class);

    private final ApplyMessageRepository messages;
    private final RocketRepository rockets;
    private final RocketStateReducer reducer;
    private final ApplyCommandParser parser;
    private final ApplyProperties properties;

    public ChannelApplyService(
            ApplyMessageRepository messages,
            RocketRepository rockets,
            RocketStateReducer reducer,
            ApplyCommandParser parser,
            ApplyProperties properties) {
        this.messages = messages;
        this.rockets = rockets;
        this.reducer = reducer;
        this.parser = parser;
        this.properties = properties;
    }

    /**
     * Applies contiguous unapplied messages for a claimed channel until a gap or batch limit.
     *
     * @return true if unapplied messages remain (gap or batch cap); false if caught up
     */
    @Transactional
    public boolean applyContiguous(UUID channel) {
        int applied = 0;
        int max = properties.getMaxMessagesPerClaim();

        while (applied < max) {
            RocketState current = loadState(channel);
            long nextNumber = current.lastAppliedNumber() + 1L;
            Optional<MessageEntity> next = messages.findUnapplied(channel, nextNumber);
            if (next.isEmpty()) {
                break;
            }

            MessageEntity message = next.get();
            ApplyCommand command = parser.parse(message);
            RocketState reduced = reducer.reduce(current, command);
            writeState(reduced, current.version());
            int marked = messages.markApplied(channel, nextNumber);
            if (marked != 1) {
                throw new IllegalStateException(
                        "failed to mark message applied: channel=" + channel + " mn=" + nextNumber);
            }
            applied++;
        }

        boolean remaining = messages.hasUnapplied(channel);
        if (remaining) {
            log.debug("channel {} paused with unapplied remaining after applying {}", channel, applied);
        }
        return remaining;
    }

    private RocketState loadState(UUID channel) {
        return rockets.findById(channel)
                .map(entity -> new RocketState(
                        entity.getId(),
                        entity.getType(),
                        entity.getSpeed(),
                        entity.getMission(),
                        entity.isExploded(),
                        entity.getExplosionReason(),
                        entity.getLastAppliedNumber(),
                        entity.getVersion()))
                .orElseGet(() -> RocketState.empty(channel));
    }

    private void writeState(RocketState state, long expectedVersion) {
        if (expectedVersion == 0L && !rockets.existsById(state.id())) {
            int inserted = rockets.insertNew(
                    state.id(),
                    state.type(),
                    state.speed(),
                    state.mission(),
                    state.exploded(),
                    state.explosionReason(),
                    state.lastAppliedNumber());
            if (inserted == 1) {
                return;
            }
            // Concurrent insert raced — fall through to CAS against freshly loaded version.
            RocketEntity existing = rockets.findById(state.id())
                    .orElseThrow(() -> new IllegalStateException("rocket missing after insert race"));
            expectedVersion = existing.getVersion();
        }

        int updated = rockets.updateCas(
                state.id(),
                state.type(),
                state.speed(),
                state.mission(),
                state.exploded(),
                state.explosionReason(),
                state.lastAppliedNumber(),
                expectedVersion);
        if (updated != 1) {
            throw new VersionConflictException(
                    "rocket version conflict for channel " + state.id() + " expectedVersion=" + expectedVersion);
        }
    }
}
