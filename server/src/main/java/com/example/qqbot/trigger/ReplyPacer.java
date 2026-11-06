package com.example.qqbot.trigger;

import com.example.qqbot.time.AppTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 主动回复的节奏闸 —— **多快**，与概率的**愿不愿意**分开。
 *
 * <p>三项都是 0 = 不限：同群最小间隔、同一人冷却、每群每日上限。
 * 默认只开每日上限 40 条，间隔与冷却保留为可调项。
 *
 * <p>判定与占位分两步：{@link #allows} 只看不写，{@link #tryReserve} 在锁内
 * 再检查一次并记录。并发下最多多回一条，不会超发。
 */
@Component
public class ReplyPacer {

    private final TriggerPolicy config;
    private final LongSupplier clock;
    private final Map<Long, GroupState> groups = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ReplyPacer(TriggerPolicy config) {
        this(config, System::currentTimeMillis);
    }

    ReplyPacer(TriggerPolicy config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    /** 现在这个群、这个人还能不能发一条主动回复。只读 */
    public boolean allows(long groupId, long userId) {
        GroupState state = groups.computeIfAbsent(groupId, k -> new GroupState());
        synchronized (state) {
            rollover(state);
            return allowsLocked(state, userId, clock.getAsLong());
        }
    }

    /**
     * 原子地再检查一次并占位。
     *
     * @return false 表示并发或节奏变化导致这一次不能发
     */
    public boolean tryReserve(long groupId, long userId) {
        GroupState state = groups.computeIfAbsent(groupId, k -> new GroupState());
        synchronized (state) {
            rollover(state);
            long now = clock.getAsLong();
            if (!allowsLocked(state, userId, now)) {
                return false;
            }
            state.lastAutoAt = now;
            state.dailyCount++;
            state.userLastAt.put(userId, now);
            return true;
        }
    }

    private boolean allowsLocked(GroupState state, long userId, long now) {
        int gap = config.minGapSeconds();
        if (gap > 0 && state.lastAutoAt > 0 && now - state.lastAutoAt < gap * 1000L) {
            return false;
        }
        int cooldown = config.userCooldownSeconds();
        if (cooldown > 0) {
            Long last = state.userLastAt.get(userId);
            if (last != null && now - last < cooldown * 1000L) {
                return false;
            }
        }
        int dailyMax = config.dailyMaxPerGroup();
        return dailyMax <= 0 || state.dailyCount < dailyMax;
    }

    private void rollover(GroupState state) {
        long today = LocalDate.now(AppTime.ZONE).toEpochDay();
        if (state.day == today) {
            return;
        }
        state.day = today;
        state.dailyCount = 0;
        state.userLastAt.clear();
    }

    private static final class GroupState {
        long day = Long.MIN_VALUE;
        int dailyCount;
        long lastAutoAt;
        final Map<Long, Long> userLastAt = new HashMap<>();
    }
}
