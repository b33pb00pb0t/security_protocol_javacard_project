package com.sports.recreation.frontend;

import javax.swing.*;
import java.awt.*;

public class LoginPanel extends JPanel {
    private final AppFrame appFrame;
    private final AuthService authService;
    private final JPasswordField passwordField;
    private final JLabel messageLabel;

    public LoginPanel(AppFrame appFrame, AuthService authService) {
        this.appFrame = appFrame;
        this.authService = authService;

        setLayout(new GridBagLayout());

        JPanel box = new JPanel(new GridLayout(5, 1, 8, 8));

        JLabel title = new JLabel("Terminal Login", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));

        passwordField = new JPasswordField();
        JButton loginButton = new JButton("Login");
        messageLabel = new JLabel(" ", SwingConstants.CENTER);

        box.add(title);
        box.add(new JLabel("Password:"));
        box.add(passwordField);
        box.add(loginButton);
        box.add(messageLabel);

        add(box);

        loginButton.addActionListener(e -> login());
        passwordField.addActionListener(e -> login());
    }

    private void login() {
        String password = new String(passwordField.getPassword());
        AuthService.Role role = authService.login(password);

        if (role == AuthService.Role.ADMIN) {
            passwordField.setText("");
            appFrame.showAdmin();
        } else if (role == AuthService.Role.MASTER) {
            passwordField.setText("");
            appFrame.showMaster();
        } else {
            messageLabel.setText("Invalid password.");
        }
    }
}