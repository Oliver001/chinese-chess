package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * 引擎选择对话框。
 * 支持：
 *   1. 内置AI（自研）
 *   2. 外部UCI引擎（皮卡鱼 Pikafish 等）
 *   3. 外部UCCI引擎（象棋巫师等）
 *
 * 外部引擎通过 UCI/UCCI 协议与引擎进程通信。
 * 选择确认后，由 ChessPanel 负责实际切换。
 */
public class EngineSelectDialog extends JDialog {

    public enum EngineType {
        BUILTIN("内置AI（自研）"),
        EXTERNAL_UCI("外部引擎 - UCI协议（皮卡鱼 Pikafish 等）"),
        EXTERNAL_UCCI("外部引擎 - UCCI协议（象棋巫师 ElephantEye 等）");

        final String label;
        EngineType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private EngineType selectedType;
    private String enginePath;
    private boolean confirmed = false;

    private JRadioButton builtinBtn, uciBtn, ucciBtn;
    private JTextField pathField;
    private JButton browseBtn, testBtn;
    private JLabel statusLabel;

    /** 构造，传入当前选择的引擎类型和路径（可为null） */
    public EngineSelectDialog(Component parent, EngineType currentType, String currentPath) {
        super(SwingUtilities.getWindowAncestor(parent), "选择AI引擎",
              ModalityType.APPLICATION_MODAL);
        this.selectedType = currentType != null ? currentType : EngineType.BUILTIN;
        this.enginePath   = currentPath != null ? currentPath : "";

        setLayout(new BorderLayout(8, 8));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        ((JPanel)getContentPane()).setBorder(new EmptyBorder(12, 16, 8, 16));

        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        pack();
        setResizable(false);
        setMinimumSize(new Dimension(520, 280));
        setLocationRelativeTo(parent);
        updateUI();
    }

    private JPanel buildMainPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;

        // 标题
        JLabel title = new JLabel("选择对战AI引擎");
        title.setFont(new Font("宋体", Font.BOLD, 14));
        p.add(title, gbc);

        // 引擎类型说明
        gbc.gridy++;
        JLabel desc = new JLabel("<html><font color='#666666' size='2'>" +
                "内置AI可直接使用；外部引擎需先安装引擎程序，再在此处选择可执行文件路径。<br>" +
                "推荐外部引擎：<b>皮卡鱼(Pikafish)</b> — 最强开源象棋引擎，支持UCI协议。" +
                "</font></html>");
        p.add(desc, gbc);

        // 分隔线
        gbc.gridy++;
        p.add(new JSeparator(), gbc);

        // 内置AI
        gbc.gridy++; gbc.gridwidth = 1;
        builtinBtn = new JRadioButton(EngineType.BUILTIN.label, selectedType == EngineType.BUILTIN);
        builtinBtn.setFont(new Font("宋体", Font.PLAIN, 12));
        gbc.gridwidth = 3;
        p.add(builtinBtn, gbc);

        // 描述
        gbc.gridy++;
        JLabel builtinDesc = new JLabel("  自研 Alpha-Beta 搜索引擎，无需安装，开箱即用");
        builtinDesc.setFont(new Font("宋体", Font.ITALIC, 11));
        builtinDesc.setForeground(new Color(0x666666));
        p.add(builtinDesc, gbc);

        // 外部UCI
        gbc.gridy++;
        uciBtn = new JRadioButton(EngineType.EXTERNAL_UCI.label, selectedType == EngineType.EXTERNAL_UCI);
        uciBtn.setFont(new Font("宋体", Font.PLAIN, 12));
        p.add(uciBtn, gbc);

        JLabel uciDesc = new JLabel("  推荐：皮卡鱼(Pikafish)，下载地址：https://www.pikafish.com");
        uciDesc.setFont(new Font("宋体", Font.ITALIC, 11));
        uciDesc.setForeground(new Color(0x0055AA));
        uciDesc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        uciDesc.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                try { Desktop.getDesktop().browse(new java.net.URI("https://www.pikafish.com")); }
                catch (Exception ignored) {}
            }
        });
        gbc.gridy++;
        p.add(uciDesc, gbc);

        // 外部UCCI
        gbc.gridy++;
        ucciBtn = new JRadioButton(EngineType.EXTERNAL_UCCI.label, selectedType == EngineType.EXTERNAL_UCCI);
        ucciBtn.setFont(new Font("宋体", Font.PLAIN, 12));
        p.add(ucciBtn, gbc);

        gbc.gridy++;
        JLabel ucciDesc = new JLabel("  推荐：象棋巫师(ElephantEye)、星际象棋等UCCI协议引擎");
        ucciDesc.setFont(new Font("宋体", Font.ITALIC, 11));
        ucciDesc.setForeground(new Color(0x666666));
        p.add(ucciDesc, gbc);

        ButtonGroup bg = new ButtonGroup();
        bg.add(builtinBtn); bg.add(uciBtn); bg.add(ucciBtn);
        builtinBtn.addActionListener(e -> updateUI());
        uciBtn.addActionListener(e -> updateUI());
        ucciBtn.addActionListener(e -> updateUI());

        // 路径选择
        gbc.gridy++;
        JLabel pathLabel = new JLabel("引擎路径：");
        pathLabel.setFont(new Font("宋体", Font.PLAIN, 12));
        gbc.gridwidth = 1;
        p.add(pathLabel, gbc);

        pathField = new JTextField(enginePath, 28);
        pathField.setFont(new Font("宋体", Font.PLAIN, 11));
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(pathField, gbc);

        browseBtn = new JButton("浏览...");
        browseBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        browseBtn.addActionListener(e -> browseEngine());
        gbc.gridx = 2; gbc.weightx = 0;
        p.add(browseBtn, gbc);

        // 测试按钮和状态
        gbc.gridy++; gbc.gridx = 0;
        testBtn = new JButton("测试连接");
        testBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        testBtn.addActionListener(e -> testEngine());
        gbc.gridwidth = 1;
        p.add(testBtn, gbc);

        statusLabel = new JLabel("");
        statusLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        gbc.gridx = 1; gbc.gridwidth = 2;
        p.add(statusLabel, gbc);

        return p;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton ok = new JButton("确认");
        ok.setFont(new Font("宋体", Font.BOLD, 12));
        ok.setForeground(new Color(0x005500));
        ok.addActionListener(e -> {
            selectedType = builtinBtn.isSelected() ? EngineType.BUILTIN
                         : uciBtn.isSelected()     ? EngineType.EXTERNAL_UCI
                                                   : EngineType.EXTERNAL_UCCI;
            enginePath = pathField.getText().trim();
            if (selectedType != EngineType.BUILTIN && enginePath.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请先选择引擎文件路径！", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });
        JButton cancel = new JButton("取消");
        cancel.setFont(new Font("宋体", Font.PLAIN, 12));
        cancel.addActionListener(e -> dispose());
        p.add(cancel); p.add(ok);
        return p;
    }

    private void updateUI() {
        boolean isExternal = uciBtn.isSelected() || ucciBtn.isSelected();
        pathField.setEnabled(isExternal);
        browseBtn.setEnabled(isExternal);
        testBtn.setEnabled(isExternal);
        if (!isExternal) statusLabel.setText("");
    }

    private void browseEngine() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择引擎可执行文件");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (!enginePath.isEmpty()) {
            File cur = new File(enginePath);
            if (cur.getParentFile() != null) fc.setCurrentDirectory(cur.getParentFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(fc.getSelectedFile().getAbsolutePath());
            statusLabel.setText("");
        }
    }

    private void testEngine() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            statusLabel.setText("请先选择引擎路径");
            statusLabel.setForeground(Color.RED);
            return;
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            statusLabel.setText("❌ 文件不存在：" + path);
            statusLabel.setForeground(Color.RED);
            return;
        }
        statusLabel.setText("⏳ 正在测试连接...");
        statusLabel.setForeground(new Color(0x886600));
        testBtn.setEnabled(false);

        ExternalEngine.Protocol proto = ucciBtn.isSelected()
                ? ExternalEngine.Protocol.UCCI : ExternalEngine.Protocol.UCI;

        new Thread(() -> {
            ExternalEngine eng = new ExternalEngine(path, proto);
            boolean ok = false;
            try { ok = eng.start(); }
            catch (Exception e) { /* 忽略 */ }
            finally { eng.stop(); }
            final boolean success = ok;
            final String name = success ? eng.getName() : "";
            SwingUtilities.invokeLater(() -> {
                testBtn.setEnabled(true);
                if (success) {
                    statusLabel.setText("✅ 连接成功：" + name + "（" + eng.getProtocol() + "）");
                    statusLabel.setForeground(new Color(0x005500));
                } else {
                    statusLabel.setText("❌ 连接失败，请检查路径和引擎类型");
                    statusLabel.setForeground(Color.RED);
                }
            });
        }, "engine-test").start();
    }

    public boolean isConfirmed()    { return confirmed; }
    public EngineType getSelectedType() { return selectedType; }
    public String getEnginePath()   { return enginePath; }
}
