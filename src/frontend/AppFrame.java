package frontend;

import backend.BlockListRepository;
import backend.CardGateway;
import backend.CsvAuditLogger;
import backend.CsvBlockListRepository;
import backend.CsvMemberRepository;
import backend.HardwareCardGateway;
import backend.JCardSimGateway;
import backend.TerminalSyncService;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.CardLayout;

public class AppFrame extends JFrame {
    private final CardLayout cardLayout;
    private final JPanel rootPanel;

    private final AuthService authService;
    private final TerminalService terminalService;

    public AppFrame() {
        this(false);
    }

    public AppFrame(boolean useHardware) {
        BlockListRepository blockListRepo = new CsvBlockListRepository("blocked_cards.csv");
        CsvMemberRepository memberRepo = new CsvMemberRepository("members.csv");
        TerminalSyncService syncService = new TerminalSyncService(blockListRepo);
        CardGateway cardGateway = useHardware ? new HardwareCardGateway() : new JCardSimGateway();
        CsvAuditLogger auditLogger = new CsvAuditLogger("audit_log.csv");

        this.authService = new AuthService(auditLogger);
        this.terminalService = new ConnectedTerminalService(memberRepo, blockListRepo, syncService, cardGateway, auditLogger);

        setTitle("Sports Recreation Center - " + cardGateway.getGatewayName() + " Terminal Frontend");
        setSize(800, 560);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        cardLayout = new CardLayout();
        rootPanel = new JPanel(cardLayout);

        rootPanel.add(new LoginPanel(this, authService), "LOGIN");
        rootPanel.add(new AdminPanel(this, terminalService), "ADMIN");
        rootPanel.add(new MasterPanel(this, terminalService), "MASTER");
        rootPanel.add(new AccessPanel(this, terminalService), "ACCESS");

        add(rootPanel);
        showLogin();
    }

    public void showLogin() {
        cardLayout.show(rootPanel, "LOGIN");
    }

    public void showAdmin() {
        cardLayout.show(rootPanel, "ADMIN");
    }

    public void showMaster() {
        cardLayout.show(rootPanel, "MASTER");
    }

    public void showAccess() {
        cardLayout.show(rootPanel, "ACCESS");
    }
}
