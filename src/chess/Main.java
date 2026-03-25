package chess;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class Main {
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            // 先显示开局设置对话框
            StartupDialog dialog = new StartupDialog();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);

            if (!dialog.confirmed) return; // 关闭则退出

            JFrame frame = new JFrame("中国象棋 — 人机对弈  v4.0");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            ChessPanel panel = new ChessPanel(
                dialog.humanIsRed,
                dialog.difficulty,
                dialog.timeControl,
                dialog.tcBaseMinutes,
                dialog.tcIncSeconds
            );
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    // =========================================================================
    // 开局设置对话框
    // =========================================================================
    static class StartupDialog extends JDialog {
        boolean confirmed = false;
        boolean humanIsRed = true;
        GameState.Difficulty difficulty = GameState.Difficulty.MEDIUM;
        GameState.TimeControl timeControl = GameState.TimeControl.INCREMENTAL;
        int tcBaseMinutes = 20;
        int tcIncSeconds  = 5;

        StartupDialog() {
            super((Frame)null, "中国象棋 — 开局设置", true);
            setResizable(false);
            buildUI();
            pack();
        }

        private void buildUI() {
            JPanel main = new JPanel(new BorderLayout(8, 8));
            main.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
            main.setBackground(new Color(0xF5E6C8));

            // ---- 标题 ----
            JLabel title = new JLabel("中国象棋", SwingConstants.CENTER);
            title.setFont(new Font("宋体", Font.BOLD, 28));
            title.setForeground(new Color(0x8B0000));
            JLabel sub = new JLabel("人机对弈", SwingConstants.CENTER);
            sub.setFont(new Font("宋体", Font.PLAIN, 14));
            sub.setForeground(new Color(0x555555));
            JPanel titlePanel = new JPanel(new GridLayout(2,1,0,2));
            titlePanel.setOpaque(false);
            titlePanel.add(title); titlePanel.add(sub);
            main.add(titlePanel, BorderLayout.NORTH);

            // ---- 设置区 ----
            JPanel settings = new JPanel(new GridBagLayout());
            settings.setOpaque(false);
            settings.setBorder(BorderFactory.createEmptyBorder(10,0,10,0));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 4, 6, 8);
            gbc.anchor = GridBagConstraints.WEST;

            int row = 0;

            // 执棋方
            gbc.gridx=0; gbc.gridy=row; gbc.fill=GridBagConstraints.NONE;
            settings.add(boldLabel("执棋方："), gbc);
            gbc.gridx=1; gbc.fill=GridBagConstraints.HORIZONTAL;
            ButtonGroup sideGroup = new ButtonGroup();
            JPanel sidePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            sidePanel.setOpaque(false);
            JRadioButton redBtn   = new JRadioButton("红方（先手）", true);
            JRadioButton blackBtn = new JRadioButton("黑方（后手）");
            redBtn.setOpaque(false); blackBtn.setOpaque(false);
            redBtn.setForeground(new Color(0xB22222));
            redBtn.setFont(new Font("宋体",Font.BOLD,13));
            blackBtn.setFont(new Font("宋体",Font.PLAIN,13));
            sideGroup.add(redBtn); sideGroup.add(blackBtn);
            sidePanel.add(redBtn); sidePanel.add(blackBtn);
            settings.add(sidePanel, gbc);
            row++;

            // 难度
            gbc.gridx=0; gbc.gridy=row; gbc.fill=GridBagConstraints.NONE;
            settings.add(boldLabel("AI难度："), gbc);
            gbc.gridx=1; gbc.fill=GridBagConstraints.HORIZONTAL;
            String[] diffLabels = {"简单（10秒/步）", "中等（30秒/步）", "困难（2分钟/步）"};
            JComboBox<String> diffBox = new JComboBox<>(diffLabels);
            diffBox.setSelectedIndex(1);
            diffBox.setFont(new Font("宋体", Font.PLAIN, 13));
            settings.add(diffBox, gbc);
            row++;

            // 时制方案
            gbc.gridx=0; gbc.gridy=row; gbc.fill=GridBagConstraints.NONE;
            settings.add(boldLabel("时制方案："), gbc);
            gbc.gridx=1; gbc.fill=GridBagConstraints.HORIZONTAL;
            String[] presetLabels = new String[GameState.TIME_PRESETS.length];
            for (int i=0; i<presetLabels.length; i++)
                presetLabels[i] = (String)GameState.TIME_PRESETS[i][0];
            JComboBox<String> timeBox = new JComboBox<>(presetLabels);
            timeBox.setSelectedIndex(0); // 默认全国联赛标准
            timeBox.setFont(new Font("宋体", Font.PLAIN, 13));
            settings.add(timeBox, gbc);
            row++;

            // 时制说明（动态更新）
            gbc.gridx=0; gbc.gridy=row; gbc.gridwidth=2; gbc.fill=GridBagConstraints.HORIZONTAL;
            JLabel timeDescLabel = new JLabel(makeTimeDesc(0));
            timeDescLabel.setFont(new Font("宋体", Font.PLAIN, 11));
            timeDescLabel.setForeground(new Color(0x555555));
            timeDescLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8A060)),
                BorderFactory.createEmptyBorder(3,6,3,6)));
            timeDescLabel.setBackground(new Color(0xFFFAF0));
            timeDescLabel.setOpaque(true);
            settings.add(timeDescLabel, gbc);
            timeBox.addActionListener(e ->
                timeDescLabel.setText(makeTimeDesc(timeBox.getSelectedIndex())));
            row++;
            gbc.gridwidth=1;

            main.add(settings, BorderLayout.CENTER);

            // ---- 按钮 ----
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
            btns.setOpaque(false);
            JButton startBtn = new JButton("开始对局");
            startBtn.setFont(new Font("宋体", Font.BOLD, 14));
            startBtn.setBackground(new Color(0xB22222));
            startBtn.setForeground(Color.WHITE);
            startBtn.setPreferredSize(new Dimension(110, 34));
            startBtn.addActionListener(e -> {
                humanIsRed = redBtn.isSelected();
                int di = diffBox.getSelectedIndex();
                difficulty = new GameState.Difficulty[]{
                    GameState.Difficulty.EASY,
                    GameState.Difficulty.MEDIUM,
                    GameState.Difficulty.HARD}[di];
                int ti = timeBox.getSelectedIndex();
                Object[] preset = GameState.TIME_PRESETS[ti];
                timeControl = (GameState.TimeControl) preset[1];
                tcBaseMinutes = (int) preset[2];
                tcIncSeconds  = (int) preset[3];
                confirmed = true;
                dispose();
            });
            JButton quitBtn = new JButton("退出");
            quitBtn.setFont(new Font("宋体", Font.PLAIN, 13));
            quitBtn.setPreferredSize(new Dimension(80, 34));
            quitBtn.addActionListener(e -> dispose());
            btns.add(startBtn); btns.add(quitBtn);
            main.add(btns, BorderLayout.SOUTH);

            setContentPane(main);
        }

        private JLabel boldLabel(String text) {
            JLabel l = new JLabel(text);
            l.setFont(new Font("宋体", Font.BOLD, 13));
            return l;
        }

        private String makeTimeDesc(int idx) {
            Object[] p = GameState.TIME_PRESETS[idx];
            GameState.TimeControl tc = (GameState.TimeControl)p[1];
            int base = (int)p[2], inc = (int)p[3];
            if (tc == GameState.TimeControl.INCREMENTAL)
                return "  ⏱ 加秒制：每方基础 " + base + " 分钟，每走一步加 " + inc + " 秒";
            else if (tc == GameState.TimeControl.FIXED)
                return "  ⏱ 包干制：每方共 " + base + " 分钟，用完即负，不加秒";
            else
                return "  ⏱ 无限制：休闲模式，不计时";
        }
    }
}
