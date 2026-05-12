package com.sports.recreation.backend;

public class MembershipAdminService {
    private final BlockListRepository blockListRepository;

    public MembershipAdminService(BlockListRepository blockListRepository) {
        this.blockListRepository = blockListRepository;
    }

    public String reportLostOrStolenCard(String idc) {
        blockListRepository.blockCard(idc);
        return "Card " + idc + " has been added to the Block List.";
    }

    public boolean isCardBlocked(String idc) {
        return blockListRepository.isBlocked(idc);
    }
}