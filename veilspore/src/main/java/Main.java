import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class Main {
    private static volatile boolean coverMode = true;
    static JFrame wallpaperFrame;
    static JPanel wallpaperPanel;
    static JLabel imageLabel;
    static JFXPanel videoPanel;

    static final File CONFIG_FILE = new File("config.properties");
    static Properties config = new Properties();

    public interface User32Ext extends User32 {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        int GWL_EXSTYLE = -20;
        int WS_EX_TOOLWINDOW = 0x00000080;
        int WS_EX_APPWINDOW = 0x00040000;

        HWND SetParent(HWND hWndChild, HWND hWndNewParent);

        boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);

        int GetClassName(HWND hWnd, byte[] lpClassName, int nMaxCount);

        HWND FindWindowEx(HWND hwndParent, HWND hwndChildAfter, String lpszClass, String lpszWindow);
    }

    public static void main(String[] args) {
        Platform.startup(() -> {});

        loadConfig();

        wallpaperFrame = new JFrame();
        wallpaperFrame.setUndecorated(true);
        wallpaperFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        wallpaperFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        wallpaperFrame.setType(Window.Type.UTILITY);
        wallpaperFrame.setAlwaysOnTop(false);

        wallpaperPanel = new JPanel(new BorderLayout());
        imageLabel = new JLabel("", JLabel.CENTER);
        videoPanel = new JFXPanel();

        wallpaperPanel.add(imageLabel, BorderLayout.CENTER);
        wallpaperFrame.setContentPane(wallpaperPanel);

        wallpaperFrame.setVisible(true);

        HWND wallpaperHwnd = getHWnd(wallpaperFrame);

        int exStyle = User32Ext.INSTANCE.GetWindowLong(wallpaperHwnd, User32Ext.GWL_EXSTYLE);
        exStyle = (exStyle | User32Ext.WS_EX_TOOLWINDOW) & ~User32Ext.WS_EX_APPWINDOW;
        User32Ext.INSTANCE.SetWindowLong(wallpaperHwnd, User32Ext.GWL_EXSTYLE, exStyle);
        User32Ext.INSTANCE.SetWindowPos(wallpaperHwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER | WinUser.SWP_FRAMECHANGED);

        HWND progman = User32Ext.INSTANCE.FindWindow("Progman", null);
        User32Ext.INSTANCE.SendMessageTimeout(progman, 0x052C, new WinDef.WPARAM(0), new WinDef.LPARAM(0),
                WinUser.SMTO_NORMAL, 1000, new WinDef.DWORDByReference());

        final HWND[] workerw = {null};

        User32Ext.INSTANCE.EnumWindows((hwnd, data) -> {
            byte[] className = new byte[256];
            User32Ext.INSTANCE.GetClassName(hwnd, className, 256);
            String windowClass = Native.toString(className);

            if ("WorkerW".equals(windowClass)) {
                HWND child = User32Ext.INSTANCE.FindWindowEx(hwnd, null, "SHELLDLL_DefView", null);
                if (child == null) {
                    workerw[0] = hwnd;
                    return false;
                }
            }
            return true;
        }, null);

        if (workerw[0] != null) {
            User32Ext.INSTANCE.SetParent(wallpaperHwnd, workerw[0]);
        }

        String lastMediaPath = config.getProperty("lastMediaPath");
        if (lastMediaPath != null) {
            File lastMedia = new File(lastMediaPath);
            if (lastMedia.exists()) {
                if (lastMediaPath.endsWith(".mp4") || lastMediaPath.endsWith(".webm")) {
                    setVideoWallpaper(lastMedia);
                } else if (lastMediaPath.endsWith(".gif")) {
                    setGifWallpaper(lastMedia);
                } else if (lastMediaPath.endsWith(".png") || lastMediaPath.endsWith(".jpg") || lastMediaPath.endsWith(".jpeg")) {
                    setImageWallpaper(lastMedia);
                }
            } else {
                setPurpleBackground();
            }
        } else {
            setPurpleBackground();
        }

        if (SystemTray.isSupported()) {
            PopupMenu popup = new PopupMenu();

            CheckboxMenuItem coverModeItem = new CheckboxMenuItem("Cover Mode");
            coverModeItem.setState(true);
            coverModeItem.addItemListener(e -> coverMode = coverModeItem.getState());

            MenuItem changeWallpaperItem = new MenuItem("Change Wallpaper");
            changeWallpaperItem.addActionListener((ActionEvent e) -> selectAndSetWallpaper());

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener((ActionEvent e) -> {
                wallpaperFrame.dispose();
                System.exit(0);
            });

            popup.add(coverModeItem);
            popup.add(changeWallpaperItem);
            popup.add(exitItem);

            TrayIcon trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage("icon.png"), "WallpaperApp", popup);
            trayIcon.setImageAutoSize(true);

            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (AWTException e) {
                e.printStackTrace();
            }
        }

    }

    private static void selectAndSetWallpaper() {
        String lastDir = config.getProperty("lastDirectory", System.getProperty("user.home"));
        JFileChooser chooser = new JFileChooser(lastDir);
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            long maxFileSize = 300L * 1024 * 1024; // 300MB byte max
            if (file.length() > maxFileSize) {
                JOptionPane.showMessageDialog(null, "File size exceeds 300MB limit.", "File Too Large", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String path = file.getAbsolutePath().toLowerCase();

            saveConfig(file.getAbsolutePath(), file.getParent());

            if (path.endsWith(".mp4") || path.endsWith(".webm")) {
                setVideoWallpaper(file);
            } else if (path.endsWith(".gif")) {
                setGifWallpaper(file);
            } else if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                setImageWallpaper(file);
            } else {
                JOptionPane.showMessageDialog(null, "Unsupported file type.");
            }
        }
    }


    private static void setImageWallpaper(File file) {
        removeVideoPanel();
        if (coverMode) {
            scaleAndSetImage(file);
        } else {
            ImageIcon icon = new ImageIcon(file.getAbsolutePath());
            imageLabel.setIcon(icon);
            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        }
    }


    private static void setGifWallpaper(File file) {
        removeVideoPanel();
        ImageIcon icon = new ImageIcon(file.getAbsolutePath());
        imageLabel.setIcon(icon);
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
    }


    private static void setVideoWallpaper(File file) {
        removeVideoPanel();
        wallpaperPanel.remove(imageLabel);
        wallpaperPanel.add(videoPanel, BorderLayout.CENTER);
        wallpaperPanel.revalidate();
        wallpaperPanel.repaint();

        new Thread(() -> Platform.runLater(() -> {
            try {
                Media media = new Media(file.toURI().toString());
                MediaPlayer mediaPlayer = new MediaPlayer(media);
                mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                mediaPlayer.setMute(true);
                MediaView mediaView = new MediaView(mediaPlayer);
                mediaView.setPreserveRatio(true);

                Group root = new Group(mediaView);
                Scene scene = new Scene(root);

                videoPanel.setScene(scene);

                videoPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        Platform.runLater(() -> {
                            int width = videoPanel.getWidth();
                            int height = videoPanel.getHeight();
                            if (coverMode) {
                                mediaView.setFitWidth(width);
                                mediaView.setFitHeight(height);
                                mediaView.setPreserveRatio(true);
                            } else {
                                mediaView.setFitWidth(media.getWidth());
                                mediaView.setFitHeight(media.getHeight());
                                mediaView.setPreserveRatio(false);
                            }
                        });
                    }
                });


                mediaPlayer.play();
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, "Failed to load video: " + ex.getMessage());
            }
        })).start();
    }


    private static void removeVideoPanel() {
        Platform.runLater(() -> videoPanel.setScene(null));
        wallpaperPanel.remove(videoPanel);
        wallpaperPanel.add(imageLabel, BorderLayout.CENTER);
        wallpaperPanel.revalidate();
    }

    private static HWND getHWnd(JFrame frame) {
        return new HWND(Native.getComponentPointer(frame));
    }
    private static void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
                config.load(in);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void saveConfig(String mediaPath, String directoryPath) throws RuntimeException {
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            config.setProperty("lastMediaPath", mediaPath);
            config.setProperty("lastDirectory", directoryPath);
            config.store(out, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setPurpleBackground() {
        removeVideoPanel();
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setPaint(new Color(128, 0, 128));
        g2d.fillRect(0, 0, image.getWidth(), image.getHeight());
        g2d.dispose();
        imageLabel.setIcon(new ImageIcon(image));
    }

    private static void scaleAndSetImage(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            int screenWidth = wallpaperFrame.getWidth();
            int screenHeight = wallpaperFrame.getHeight();

            double imgAspect = (double) img.getWidth() / img.getHeight();
            double screenAspect = (double) screenWidth / screenHeight;

            int newWidth, newHeight;

            if (imgAspect > screenAspect) {
                newHeight = screenHeight;
                newWidth = (int) (screenHeight * imgAspect);
            } else {
                newWidth = screenWidth;
                newHeight = (int) (screenWidth / imgAspect);
            }

            Image scaledImage = img.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            ImageIcon icon = new ImageIcon(scaledImage);
            imageLabel.setIcon(icon);
            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
