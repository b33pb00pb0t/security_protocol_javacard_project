package tools;

import backend.HardwareCardGateway;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class HardwareSmokeTest {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String ALLOW_MODIFICATION_FLAG = "--allow-card-modification";

    private HardwareSmokeTest() {
    }

    public static void main(String[] args) {
        List<String> commandArguments = new ArrayList<>(Arrays.asList(args));
        boolean allowCardModification = removeFlag(commandArguments, ALLOW_MODIFICATION_FLAG);
        // Prevent accidental provisioning, state transitions, or counter updates during
        // the default SELECT-only hardware check.
        if (requiresCardModification(commandArguments) && !allowCardModification) {
            throw new IllegalArgumentException("State-changing hardware commands require "
                    + ALLOW_MODIFICATION_FLAG);
        }

        HardwareCardGateway gateway = new HardwareCardGateway();
        gateway.printAidDebug();

        List<String> readers = HardwareCardGateway.listAvailableReaders();
        System.out.println("Available readers: " + readers);
        if (readers.isEmpty()) {
            throw new IllegalStateException("No PC/SC card reader found.");
        }

        try {
            gateway.connect();
            System.out.println("Connected reader: " + gateway.getReaderName());
            System.out.println("Card ATR: " + gateway.getAtrHex());
            System.out.println("Applet SELECT: SUCCESS");

            if (commandArguments.isEmpty()) {
                printUsage();
                return;
            }

            System.out.println("WARNING: This will modify persistent state on the physical JavaCard.");

            if ("--provision-and-activate".equalsIgnoreCase(commandArguments.get(0))
                    && commandArguments.size() == 3) {
                String memberId = commandArguments.get(1);
                LocalDate expiry = LocalDate.parse(commandArguments.get(2), DATE_FORMAT);
                System.out.println("Provisioning physical card for member " + memberId + "...");
                gateway.provision(memberId);
                System.out.println("Provisioning APDUs: SUCCESS");
                gateway.activate(memberId, LocalDate.now(), expiry);
                System.out.println("Authenticated activation APDU: SUCCESS");
                return;
            }

            if ("--activate".equalsIgnoreCase(commandArguments.get(0)) && commandArguments.size() == 3) {
                String memberId = commandArguments.get(1);
                LocalDate expiry = LocalDate.parse(commandArguments.get(2), DATE_FORMAT);
                gateway.activate(memberId, LocalDate.now(), expiry);
                System.out.println("Authenticated activation APDU: SUCCESS");
                return;
            }

            if ("--tier1".equalsIgnoreCase(commandArguments.get(0)) && commandArguments.size() == 2) {
                System.out.println(gateway.checkInTier1(commandArguments.get(1)).getMessage());
                return;
            }

            if (isTier2Command(commandArguments, "--tier2")) {
                LocalDate terminalDate = getTier2Date(commandArguments);
                System.out.println(gateway.checkInTier2(commandArguments.get(1), terminalDate).getMessage());
                return;
            }

            if (isTier2Command(commandArguments, "--debug-tier2")) {
                String memberId = commandArguments.get(1);
                LocalDate terminalDate = getTier2Date(commandArguments);
                gateway.setTier2Debug(true);
                System.out.println("Running Tier 2 debug for member " + memberId
                        + " with terminal date " + terminalDate.format(DATE_FORMAT) + ".");
                HardwareCardGateway.CardAccessResult result = gateway.checkInTier2(memberId, terminalDate);
                System.out.println("Tier 2 debug result: " + (result.isSuccess() ? "SUCCESS" : "DENIED")
                        + " - " + result.getMessage());
                return;
            }

            printUsage();
        } catch (Exception e) {
            System.err.println("Hardware smoke test failed: " + e.getMessage());
            throw new IllegalStateException("Hardware smoke test failed", e);
        } finally {
            gateway.disconnect(false);
        }
    }

    private static boolean removeFlag(List<String> arguments, String flag) {
        for (int i = 0; i < arguments.size(); i++) {
            if (flag.equalsIgnoreCase(arguments.get(i))) {
                arguments.remove(i);
                return true;
            }
        }
        return false;
    }

    private static boolean requiresCardModification(List<String> arguments) {
        if (arguments.isEmpty()) {
            return false;
        }
        String command = arguments.get(0);
        return "--provision-and-activate".equalsIgnoreCase(command)
                || "--activate".equalsIgnoreCase(command)
                || "--tier1".equalsIgnoreCase(command)
                || "--tier2".equalsIgnoreCase(command)
                || "--debug-tier2".equalsIgnoreCase(command);
    }

    private static boolean isTier2Command(List<String> arguments, String command) {
        return command.equalsIgnoreCase(arguments.get(0))
                && (arguments.size() == 2
                || (arguments.size() == 4 && "--date".equalsIgnoreCase(arguments.get(2))));
    }

    private static LocalDate getTier2Date(List<String> arguments) {
        return arguments.size() == 4
                ? LocalDate.parse(arguments.get(3), DATE_FORMAT)
                : LocalDate.now();
    }

    private static void printUsage() {
        System.out.println("SELECT-only smoke test completed.");
        System.out.println("State-changing commands require " + ALLOW_MODIFICATION_FLAG + ":");
        System.out.println("  " + ALLOW_MODIFICATION_FLAG + " --provision-and-activate <memberId> <YYYYMMDD>");
        System.out.println("  " + ALLOW_MODIFICATION_FLAG + " --activate <memberId> <YYYYMMDD>");
        System.out.println("  " + ALLOW_MODIFICATION_FLAG + " --tier1 <memberId>");
        System.out.println("  " + ALLOW_MODIFICATION_FLAG + " --tier2 <memberId> [--date <YYYYMMDD>]");
        System.out.println("  " + ALLOW_MODIFICATION_FLAG + " --debug-tier2 <memberId> [--date <YYYYMMDD>]");
    }
}
