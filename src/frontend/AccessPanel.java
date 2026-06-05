package frontend;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

public class AccessPanel extends JPanel {
    private final AppFrame appFrame;
    private final TerminalService terminalService;

    private final JTextField memberIdField;
    private final JTextArea outputArea;

    public AccessPanel(AppFrame appFrame, TerminalService terminalService) {
        this.appFrame = appFrame;
        this.terminalService = terminalService;

        setLayout(new BorderLayout(10, 10));

        JLabel title = new JLabel("ACCESS Terminal", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        add(title, BorderLayout.NORTH);

        JPanel formPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        memberIdField = new JTextField();
        formPanel.add(new JLabel("Member ID:"));
        formPanel.add(memberIdField);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        JButton tier1Button = new JButton("Tier 1 Check-In");
        JButton tier2Button = new JButton("Tier 2 Check-In");
        JButton statusButton = new JButton("Read Card Status");
        JButton logoutButton = new JButton("Logout");

        buttonPanel.add(tier1Button);
        buttonPanel.add(tier2Button);
        buttonPanel.add(statusButton);
        buttonPanel.add(logoutButton);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        centerPanel.add(formPanel, BorderLayout.NORTH);
        centerPanel.add(buttonPanel, BorderLayout.CENTER);

        outputArea = new JTextArea(8, 60);
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(0, 140));

        add(centerPanel, BorderLayout.CENTER);
        add(scrollPane, BorderLayout.SOUTH);

        tier1Button.addActionListener(e -> log(terminalService.checkInTier1(getMemberId())));
        tier2Button.addActionListener(e -> log(terminalService.checkInTier2(getMemberId())));
        statusButton.addActionListener(e -> log(terminalService.readCardStatus(getMemberId())));
        logoutButton.addActionListener(e -> appFrame.showLogin());
    }

    private String getMemberId() {
        return memberIdField.getText().trim();
    }

    private void log(String text) {
        outputArea.append(text + "\n");
    }
}
