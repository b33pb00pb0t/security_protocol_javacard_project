package com.sports.recreation.frontend;

import javax.swing.*;
import java.awt.*;

public class AdminPanel extends JPanel {
    private final AppFrame appFrame;
    private final TerminalService terminalService;

    private final JTextField memberIdField;
    private final JTextField expiryDateField;
    private final JTextArea outputArea;

    public AdminPanel(AppFrame appFrame, TerminalService terminalService) {
        this.appFrame = appFrame;
        this.terminalService = terminalService;

        setLayout(new BorderLayout(10, 10));

        JLabel title = new JLabel("ADMIN Terminal", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        add(title, BorderLayout.NORTH);

        JPanel formPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        memberIdField = new JTextField();
        expiryDateField = new JTextField("20261231");

        formPanel.add(new JLabel("Member ID:"));
        formPanel.add(memberIdField);
        formPanel.add(new JLabel("Expiry Date (YYYYMMDD):"));
        formPanel.add(expiryDateField);

        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 8, 8));

        JButton activateButton = new JButton("Activate Card");
        JButton deactivateButton = new JButton("Deactivate Card");
        JButton renewButton = new JButton("Renew Membership");
        JButton lostButton = new JButton("Report Lost/Stolen");
        JButton statusButton = new JButton("Read Card Status");
        JButton viewBlockedButton = new JButton("View Blocked Cards");
        JButton logoutButton = new JButton("Logout");

        buttonPanel.add(activateButton);
        buttonPanel.add(deactivateButton);
        buttonPanel.add(renewButton);
        buttonPanel.add(lostButton);
        buttonPanel.add(statusButton);
        buttonPanel.add(viewBlockedButton);
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

        activateButton.addActionListener(e ->
                log(terminalService.activateCard(getMemberId(), expiryDateField.getText())));

        deactivateButton.addActionListener(e ->
                log(terminalService.deactivateCard(getMemberId())));

        renewButton.addActionListener(e ->
                log(terminalService.renewMembership(getMemberId(), expiryDateField.getText())));

        lostButton.addActionListener(e ->
                log(terminalService.reportLostOrStolen(getMemberId())));

        statusButton.addActionListener(e ->
                log(terminalService.readCardStatus(getMemberId())));

        viewBlockedButton.addActionListener(e ->
                log(terminalService.viewBlockedCards()));

        logoutButton.addActionListener(e -> appFrame.showLogin());
    }

    private String getMemberId() {
        return memberIdField.getText().trim();
    }

    private void log(String text) {
        outputArea.append(text + "\n");
    }
}