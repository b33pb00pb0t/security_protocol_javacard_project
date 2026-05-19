package com.sports.recreation.backend;

import java.util.Collection;

public interface MemberRepository {
    MemberRecord find(String memberId);

    void save(MemberRecord record);

    Collection<MemberRecord> findAll();
}
