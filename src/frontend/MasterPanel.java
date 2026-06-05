package frontend;

import javax.swing.*;
import java.awt.*;

public class MasterPanel extends JPanel {
    private final AppFrame appFrame;
    private final TerminalService terminalService;

    private final JTextArea outputArea;

    public MasterPanel(AppFrame appFrame, TerminalService terminalService) {
        this.appFrame = appFrame;
        this.terminalService = terminalService;

        setLayout(new BorderLayout(10, 10));

        JLabel title = new JLabel("MASTER Terminal", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        add(title, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 8, 8));

        JButton initializeButton = new JButton("Initialize Card");
        JButton resetButton = new JButton("Reset Card");
        JButton statusButton = new JButton("Read Card Status");
        JButton logoutButton = new JButton("Logout");

        buttonPanel.add(initializeButton);
        buttonPanel.add(resetButton);
        buttonPanel.add(statusButton);
        buttonPanel.add(logoutButton);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        centerPanel.add(buttonPanel, BorderLayout.CENTER);

        outputArea = new JTextArea(6, 50);
        outputArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(0, 100));

        add(centerPanel, BorderLayout.CENTER);
        add(scrollPane, BorderLayout.SOUTH);

        initializeButton.addActionListener(e ->
                log(terminalService.initializeCard()));

        resetButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(this,
                    "This deletes this project's applet state from the selected card and reinstalls a fresh applet. Continue?",
                    "Reset Card",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                log(terminalService.resetCard());
            }
        });

        statusButton.addActionListener(e ->
                log(terminalService.readInitializedCardStatus()));

        logoutButton.addActionListener(e -> appFrame.showLogin());
    }

    private void log(String text) {
        outputArea.append(text + "\n");
    }
}
