package frontend;

import backend.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
<<<<<<< HEAD
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;

public class ConnectedTerminalService implements TerminalService {

    // APDU instruction bytes aligned with MembershipApplet specifications
    private static final byte CLA = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY  = (byte) 0x10; // Initialize RSA key pair on the card
    private static final byte INS_LOAD_CERT       = (byte) 0x11; // Load member certificate (135 bytes)
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12; // Load Master Public Key (67 bytes)
    private static final byte INS_ACTIVATE        = (byte) 0x13;
    private static final byte INS_BLOCK           = (byte) 0x14;
    private static final byte INS_CHECKIN_T1      = (byte) 0x20;
    private static final byte INS_T2_STEP1        = (byte) 0x21;
    private static final byte INS_T2_STEP2        = (byte) 0x22;
    private static final byte INS_GET_CERT        = (byte) 0x60;
=======
import java.time.format.DateTimeParseException;
import java.util.Map;

public class ConnectedTerminalService implements TerminalService {
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
>>>>>>> f05a0aba748e68fe0099f1e0c470465d6cefb0c8

    private final CsvMemberRepository memberRepository;
    private final BlockListRepository blockListRepository;
    private final TerminalSyncService syncService;
    private final PhysicalCardGateway cardGateway;
    private final AuditLogger auditLogger;
    private final TerminalOfflineCache terminalCache;

    // Temporary storage for public key during initialization process
    private RSAPublicKey lastGeneratedCardPublicKey;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepo,
                                    TerminalSyncService syncService,
                                    PhysicalCardGateway cardGateway,
                                    AuditLogger auditLogger,
                                    TerminalOfflineCache terminalCache) {
        this.memberRepository = memberRepository;
        this.blockListRepository = blockListRepo;
        this.syncService = syncService;
        this.cardGateway = cardGateway;
        this.auditLogger = auditLogger;
        this.terminalCache = terminalCache;
    }

    private boolean isHardwareReady() {
        return cardGateway != null;
    }





    //left this file incompleted





    @Override
    public String initializeCard(String memberId) {
        if (!isHardwareReady()) return "Error: Card reader not connected.";
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512);
            KeyPair pair = keyGen.generateKeyPair();
            
            // Save public key locally to use later for certificate signing
            this.lastGeneratedCardPublicKey = (RSAPublicKey) pair.getPublic();
            
            RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();
            byte[] data = new byte[128];
            byte[] modBytes = priv.getModulus().toByteArray();
            byte[] expBytes = priv.getPrivateExponent().toByteArray();

            System.arraycopy(modBytes, modBytes.length > 64 ? modBytes.length - 64 : 0, data, 64 - Math.min(modBytes.length, 64), Math.min(modBytes.length, 64));
            System.arraycopy(expBytes, expBytes.length > 64 ? expBytes.length - 64 : 0, data, 128 - Math.min(expBytes.length, 64), Math.min(expBytes.length, 64));
            
            cardGateway.transmit(CLA, INS_INITIALIZE_KEY, (byte) 0x00, (byte) 0x00, data);
            return finish("MASTER", memberId, "INITIALIZE", true, "Card initialized successfully.");
        } catch (Exception e) {
            return finish("MASTER", memberId, "INITIALIZE", false, "Error: " + e.getMessage());
        }
    }

    @Override
    public String installCertificate(String memberId) {
        if (!isHardwareReady()) return "Error: Card reader not connected.";
        try {
            String normalized = CardId.normalize(memberId);
<<<<<<< HEAD
            
            if (this.lastGeneratedCardPublicKey == null) {
                return "ERROR: Card not initialized. Initialize before installing certificate.";
=======
            if (blockListRepository.isBlocked(normalized)) {
                return finish("MASTER", normalized, "INSTALL_CERTIFICATE", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot install certificate.");
            }
            cardGateway.provision(normalized);
            if (memberRepository.find(normalized) == null) {
                memberRepository.ensureInitialized(normalized, "STANDARD");
            }
            return finish("MASTER", normalized, "INSTALL_CERTIFICATE", true,
                    "Certificate installed for simulator card " + normalized + ".");
        } catch (Exception e) {
            return finish("MASTER", memberId, "INSTALL_CERTIFICATE", false, "ERROR: " + e.getMessage());
        }
    }

    ........................
}
