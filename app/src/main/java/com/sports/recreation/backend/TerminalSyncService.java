package com.sports.recreation.backend;

public class TerminalSyncService {
    private final BlockListRepository blockListRepository;

    public TerminalSyncService(BlockListRepository blockListRepository) {
        this.blockListRepository = blockListRepository;
    }

    public BlockListSnapshot syncBlockList() {
        if (blockListRepository instanceof CsvBlockListRepository) {
            return new BlockListSnapshot(
                    blockListRepository.getVersion(),
                    ((CsvBlockListRepository) blockListRepository).getBlockListEntries()
            );
        }
        
        return new BlockListSnapshot(
                blockListRepository.getVersion(),
                blockListRepository.getBlockedCards()
        );
    }
}