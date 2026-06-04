package tools;

import applet.MembershipApplet;
import backend.ApduDateCodec;
import backend.BlockListRepository;
import backend.CardGateway;
import backend.CardId;
import backend.CsvBlockListRepository;
import backend.CsvMemberRepository;
import backend.JCardSimGateway;
import backend.TerminalSyncService;
import com.licel.jcardsim.base.Simulator;
import frontend.ConnectedTerminalService;
import javacard.framework.AID;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;

public final class SimulatorRegressionTest {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte[] APPLET_AID = new byte[] {
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x01
    };

    private SimulatorRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        runConnectedServiceFlow();
        runRawApduNegativeChecks();
        System.out.println("SIMULATOR REGRESSION: PASS");
    }

    private static void runConnectedServiceFlow() throws Exception {
        Path directory = Files.createTempDirectory("membership-simulator-regression-");
        CsvMemberRepository members = new CsvMemberRepository(directory.resolve("members.csv").toString());
        BlockListRepository blocks = new CsvBlockListRepository(directory.resolve("blocked.csv").toString());
        JCardSimGateway gateway = new JCardSimGateway();
        ConnectedTerminalService service = new ConnectedTerminalService(
                members, blocks, new TerminalSyncService(blocks), gateway);
        String memberId = "1234";
        LocalDate today = LocalDate.now();

        requireContains(service.initializeCard(memberId), "initialized", "provision");
        requireContains(service.activateCard(memberId, today.plusYears(2).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
                "activated", "activate");
        requireContains(service.syncTerminals(), "synced", "terminal sync");
        requireContains(service.checkInTier1(memberId), "ACCESS GRANTED", "Tier 1");
        requireContains(service.checkInTier2(memberId), "DailyCounter=1", "Tier 2 first access");
        requireContains(service.checkInTier2(memberId), "DailyCounter=2", "Tier 2 second access");
        requireContains(service.checkInTier2(memberId), "ACCESS DENIED", "Tier 2 third access");

        CardGateway.CardAccessResult nextDay = gateway.checkInTier2(memberId, today.plusDays(1));
        require(nextDay.isSuccess() && nextDay.getMessage().contains("DailyCounter=1"),
                "Tier 2 next-day counter reset failed: " + nextDay.getMessage());

        requireContains(service.blockCard(memberId), "Block List", "block");
        requireContains(service.syncTerminals(), "synced", "post-block terminal sync");
        requireContains(service.checkInTier1(memberId), "ACCESS DENIED", "post-block access policy");
        requireContains(service.activateCard(memberId, today.plusYears(2)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)), "BLOCKED", "blocked activation policy");
        System.out.println("Connected service flow: PASS");
    }

    private static void runRawApduNegativeChecks() {
        Simulator wrongLengthSimulator = newSimulator();
        requireSw(transmit(wrongLengthSimulator, (byte) 0x13, CardId.toBytes("1234")),
                0x6700, "ACTIVATE with four bytes");

        Simulator wrongInstructionSimulator = newSimulator();
        byte[] validActivation = activationPayload(LocalDate.now(), LocalDate.now().plusYears(1));
        requireSw(transmit(wrongInstructionSimulator, (byte) 0x13, validActivation), 0x9000,
                "valid ACTIVATE before wrong Tier 2 INS check");
        requireSw(transmit(wrongInstructionSimulator, (byte) 0x21, new byte[203]), 0x6700,
                "Tier 2 step 2 payload sent with step 1 INS");

        requireSw(transmit(wrongInstructionSimulator, (byte) 0x14, null), 0x9000, "BLOCK");
        requireSw(transmit(wrongInstructionSimulator, (byte) 0x13, validActivation), 0x6985,
                "blocked card reactivation");
        requireSw(transmit(wrongInstructionSimulator, (byte) 0x20, new byte[16]), 0x6985,
                "blocked card Tier 1");

        Simulator malformedDateSimulator = newSimulator();
        byte[] malformedDateActivation = new byte[12];
        System.arraycopy(CardId.toBytes("1234"), 0, malformedDateActivation, 0, 4);
        Arrays.fill(malformedDateActivation, 4, 12, (byte) 0xFA);
        requireSw(transmit(malformedDateSimulator, (byte) 0x13, malformedDateActivation), 0x9000,
                "malformed BCD activation date");
        System.out.println("Known limitation: applet accepts malformed BCD dates.");
        System.out.println("Raw APDU negative checks: PASS");
    }

    private static Simulator newSimulator() {
        Simulator simulator = new Simulator();
        AID aid = new AID(APPLET_AID, (short) 0, (byte) APPLET_AID.length);
        simulator.installApplet(aid, MembershipApplet.class);
        require(simulator.selectApplet(aid), "Could not select simulator applet");
        return simulator;
    }

    private static byte[] activationPayload(LocalDate currentDate, LocalDate expiryDate) {
        byte[] payload = new byte[12];
        System.arraycopy(CardId.toBytes("1234"), 0, payload, 0, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, payload, 4, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, payload, 8, 4);
        return payload;
    }

    private static int transmit(Simulator simulator, byte ins, byte[] data) {
        int length = data == null ? 0 : data.length;
        byte[] command = new byte[5 + length];
        command[0] = CLA_PROPRIETARY;
        command[1] = ins;
        command[4] = (byte) length;
        if (data != null) {
            System.arraycopy(data, 0, command, 5, length);
        }
        byte[] response = simulator.transmitCommand(command);
        return ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
    }

    private static void requireSw(int actual, int expected, String operation) {
        require(actual == expected, operation + " returned SW=" + String.format("%04X", actual)
                + "; expected " + String.format("%04X", expected));
    }

    private static void requireContains(String actual, String expected, String operation) {
        require(actual != null && actual.contains(expected), operation + " returned: " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
