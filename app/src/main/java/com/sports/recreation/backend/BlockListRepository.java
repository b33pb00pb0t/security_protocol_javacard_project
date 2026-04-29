package com.sports.recreation.backend;

import java.util.Set;

public interface BlockListRepository {
    void blockCard(String idc);

    boolean isBlocked(String idc);

    Set<String> getBlockedCards();

    long getVersion();
}