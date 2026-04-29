package com.sports.recreation.frontend;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.CardLayout;

public class AppFrame extends JFrame {
    private final CardLayout cardLayout;
    private final JPanel rootPanel;

    private final AuthService authService;
    private final TerminalService terminalService;

    public AppFrame() {
        this.authService = new AuthService();
        this.terminalService = new MockTerminalService();

        setTitle("Sports Recreation Center - Terminal Frontend");
        setSize(700, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        cardLayout = new CardLayout();
        rootPanel = new JPanel(cardLayout);

        rootPanel.add(new LoginPanel(this, authService), "LOGIN");
        rootPanel.add(new AdminPanel(this, terminalService), "ADMIN");
        rootPanel.add(new MasterPanel(this, terminalService), "MASTER");

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
}