package com.example.qqbot.trigger;

import com.example.qqbot.time.AppTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动回复的**每日上限** —— 只防"一天插话太多"。
 *
 * <p>频率不在这里管：每群每分钟、每人窗口统一走
 * {@code app.guard.rate-limit.*}，同一套限制对提问和主动回复一起生效。
 *
 * <p>判定与占位分两步：{@link #allows} 只看不写，{@link #tryReserve} 在锁内
 * 再检查一次并记录。并发下最多多回一条，不会超发。
 */
@Component
public class ReplyPacer {

    private final TriggerPolicy config;
    private final Map<Long, GroupState> groups = new ConcurrentHashMap<>();

    public ReplyPacer(TriggerPolicy config) {
        this.config = config;
    }

    /** 这个群今天还能不能发一条主动回复。只读 */
    public boolean allows(long groupId) {
        int max = config.dailyMaxPerGroup();
        if (max <= 0) {
            return true;
        }
        GroupState state = groups.computeIfAbsent(groupId, k -> new GroupState());
        synchronized (state) {
            rollover(state);
            return state.dailyCount < max;
        }
    }

    /**
     * 原子地再检查一次并占位。
     *
     * @return false 表示并发或配置变化导致这一次不能发
     */
    public boolean tryReserve(long groupId) {
        int max = config.dailyMaxPerGroup();
        if (max <= 0) {
            return true;
        }
        GroupState state = groups.computeIfAbsent(groupId, k -> new GroupState());
        synchronized (state) {
            rollover(state);
            if (state.dailyCount >= max) {
                return false;
            }
            state.dailyCount++;
            return true;
        }
    }

    private void rollover(GroupState state) {
        long today = LocalDate.now(AppTime.ZONE).toEpochDay();
        if (state.day == today) {
            return;
        }
        state.day = today;
        state.dailyCount = 0;
    }

    private static final class GroupState {
        long day = Long.MIN_VALUE;
        int dailyCount;
    }
}
