package frontend;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        final boolean useHardware = hasArgument(args, "--hardware")
                || "true".equalsIgnoreCase(System.getenv("USE_HARDWARE"));
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                AppFrame frame = new AppFrame(useHardware);
                frame.setVisible(true);
            }
        });
    }

    private static boolean hasArgument(String[] args, String expected) {
        for (String argument : args) {
            if (expected.equalsIgnoreCase(argument)) {
                return true;
            }
        }
        return false;
    }
}
