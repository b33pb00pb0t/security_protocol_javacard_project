package frontend;

import javax.swing.*;
import java.awt.*;

public class MasterPanel extends JPanel {
    private final AppFrame appFrame;
    private final TerminalService terminalService;

    private final JTextField packageTypeField;
    private final JTextArea outputArea;

    public MasterPanel(AppFrame appFrame, TerminalService terminalService) {
        this.appFrame = appFrame;
        this.terminalService = terminalService;

        setLayout(new BorderLayout(10, 10));

        JLabel title = new JLabel("MASTER Terminal", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        add(title, BorderLayout.NORTH);

        JPanel formPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        packageTypeField = new JTextField("STANDARD");

        formPanel.add(new JLabel("Package Type:"));
        formPanel.add(packageTypeField);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 8, 8));

        JButton initializeButton = new JButton("Initialize Card");
        JButton personalizeButton = new JButton("Personalize Card");
        JButton statusButton = new JButton("Read Card Status");
        JButton logoutButton = new JButton("Logout");

        buttonPanel.add(initializeButton);
        buttonPanel.add(personalizeButton);
        buttonPanel.add(statusButton);
        buttonPanel.add(logoutButton);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        centerPanel.add(formPanel, BorderLayout.NORTH);
        centerPanel.add(buttonPanel, BorderLayout.CENTER);

        outputArea = new JTextArea(6, 50);
        outputArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(0, 100));

        add(centerPanel, BorderLayout.CENTER);
        add(scrollPane, BorderLayout.SOUTH);

        initializeButton.addActionListener(e ->
                log(terminalService.initializeCard()));

        personalizeButton.addActionListener(e ->
                log(terminalService.personalizeCard(packageTypeField.getText())));

        statusButton.addActionListener(e ->
                log(terminalService.readInitializedCardStatus()));

        logoutButton.addActionListener(e -> appFrame.showLogin());
    }

    private void log(String text) {
        outputArea.append(text + "\n");
    }
}
