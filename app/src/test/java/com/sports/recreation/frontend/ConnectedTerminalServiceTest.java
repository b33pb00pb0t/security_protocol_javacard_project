package com.sports.recreation.frontend;

import com.sports.recreation.backend.BlockListRepository;
import com.sports.recreation.backend.CsvBlockListRepository;
import com.sports.recreation.backend.CsvMemberRepository;
import com.sports.recreation.backend.JCardSimGateway;
import com.sports.recreation.backend.TerminalSyncService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class ConnectedTerminalServiceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void provisionActivateAndCheckInWithTier2Limit() throws Exception {
        ConnectedTerminalService service = newService();

        assertContains(service.initializeCard("1234"), "000004D2");
        assertContains(service.personalizeCard("1234", "VIP"), "VIP");
        assertContains(service.installCertificate("1234"), "Certificate installed");
        assertContains(service.loadIssuerData("1234"), "Issuer public data loaded");
        assertContains(service.activateCard("1234", "20991231", "+34123456789"), "activated");
        assertContains(service.readCardStatus("1234"), "AppletActive=true");
        assertContains(service.checkInTier1("1234"), "ACCESS GRANTED");
        assertContains(service.checkInTier2("1234"), "DailyCounter=1");
        assertContains(service.checkInTier2("1234"), "DailyCounter=2");
        assertContains(service.checkInTier2("1234"), "ACCESS DENIED");
    }

    @Test
    public void blockedAndInactiveMembersAreDeniedByBackendPolicy() throws Exception {
        ConnectedTerminalService service = newService();

        service.initializeCard("1234");
        service.activateCard("1234", "20991231", "555");
        assertContains(service.blockCard("1234"), "Block List");
        assertContains(service.checkInTier1("1234"), "ACCESS DENIED");

        service.initializeCard("2222");
        service.activateCard("2222", "20991231", "555");
        assertContains(service.deactivateCard("2222"), "deactivated");
        assertContains(service.checkInTier1("2222"), "INACTIVE");
    }

    @Test
    public void expiredMembersAreDeniedByBackendPolicy() throws Exception {
        ConnectedTerminalService service = newService();

        service.initializeCard("3333");
        service.activateCard("3333", "20000101", "555");
        assertContains(service.checkInTier1("3333"), "expired");
    }

    @Test
    public void deactivatedCardCanBeReactivatedWithoutSecondActivationApdu() throws Exception {
        ConnectedTerminalService service = newService();

        service.initializeCard("4444");
        assertContains(service.activateCard("4444", "20991231", "555"), "activated");
        assertContains(service.deactivateCard("4444"), "deactivated");
        assertContains(service.activateCard("4444", "20991231", "555"), "activated");
        assertContains(service.checkInTier1("4444"), "ACCESS GRANTED");
    }

    @Test
    public void persistedActiveMemberStillNeedsActivationForFreshSimulatorSession() throws Exception {
        File membersFile = temporaryFolder.newFile("members.csv");
        File blockedFile = temporaryFolder.newFile("blocked_cards.csv");
        CsvMemberRepository firstRepository = new CsvMemberRepository(membersFile.getAbsolutePath());
        BlockListRepository firstBlockList = new CsvBlockListRepository(blockedFile.getAbsolutePath());
        ConnectedTerminalService firstService = new ConnectedTerminalService(firstRepository, firstBlockList,
                new TerminalSyncService(firstBlockList), new JCardSimGateway());

        firstService.initializeCard("5555");
        assertContains(firstService.activateCard("5555", "20991231", "555"), "activated");

        CsvMemberRepository reloadedRepository = new CsvMemberRepository(membersFile.getAbsolutePath());
        BlockListRepository reloadedBlockList = new CsvBlockListRepository(blockedFile.getAbsolutePath());
        ConnectedTerminalService reloadedService = new ConnectedTerminalService(reloadedRepository, reloadedBlockList,
                new TerminalSyncService(reloadedBlockList), new JCardSimGateway());

        assertContains(reloadedService.initializeCard("5555"), "000015B3");
        assertContains(reloadedService.checkInTier1("5555"), "not active in this app run");
        assertContains(reloadedService.activateCard("5555", "20991231", "555"), "activated");
        assertContains(reloadedService.checkInTier1("5555"), "ACCESS GRANTED");
    }

    @Test
    public void renewalAndBlockListViewUseNormalizedIds() throws Exception {
        ConnectedTerminalService service = newService();

        service.initializeCard("0x00001E61");
        service.activateCard("7777", "20261231", "555");
        assertContains(service.renewMembership("7777", "20991231"), "20991231");
        assertContains(service.blockCard("7777"), "00001E61");
        assertContains(service.viewBlockedCards(), "00001E61");
        assertContains(service.checkInTier1("7777"), "ACCESS DENIED");
    }

    private ConnectedTerminalService newService() throws Exception {
        File membersFile = temporaryFolder.newFile("members.csv");
        File blockedFile = temporaryFolder.newFile("blocked_cards.csv");
        CsvMemberRepository memberRepository = new CsvMemberRepository(membersFile.getAbsolutePath());
        BlockListRepository blockListRepository = new CsvBlockListRepository(blockedFile.getAbsolutePath());
        return new ConnectedTerminalService(memberRepository, blockListRepository,
                new TerminalSyncService(blockListRepository), new JCardSimGateway());
    }

    private void assertContains(String actual, String expected) {
        assertTrue("Expected [" + actual + "] to contain [" + expected + "]", actual.contains(expected));
    }
}
