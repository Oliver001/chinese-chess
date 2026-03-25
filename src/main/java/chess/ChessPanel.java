package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.prefs.Preferences;

public class ChessPanel extends JPanel {

    // ---- 尺寸（默认/初始值，用于 preferredSize 和首次布局）----
    private static final int CELL    = 66;
    private static final int MARGIN  = 46;
    private static final int PIECE_R = 26;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;
    private static final int SIDE_W  = 370;

    // ---- 动态尺寸（随面板大小实时计算，供绘制和点击使用）----
    private volatile int dynCell   = CELL;
    private volatile int dynMargin = MARGIN;
    private volatile int dynPieceR = PIECE_R;
    private volatile int dynOffX   = 0;
    private volatile int dynOffY   = 0;

    // ---- 核心 ----
    private final GameState gs = new GameState();
    private final AIEngine  ai = new AIEngine();
    private final SoundManager sound = new SoundManager();

    // ---- 外部引擎（UCI/UCCI），null=使用内置AI ----
    private ExternalEngine externalEngine = null;
    private EngineSelectDialog.EngineType currentEngineType = EngineSelectDialog.EngineType.BUILTIN;
    private String currentEnginePath = "";
    /** 引擎名称（菜单显示用） */
    private JMenuItem engineMenuItem; // 用于动态更新显示

    // ---- 交互 ----
    private int selRow = -1, selCol = -1;
    private List<int[]> legalMoves = null;
    private volatile boolean aiThinking = false;

    /**
     * 渲染快照：AI思考期间，绘制的是这个快照，而不是真实棋盘。
     * 这样AI在后台调用board.move/undoMove时，界面完全不受影响。
     */
    private volatile Piece[][] renderSnapshot = null;

    // ---- 最后一步高亮 ----
    private int lastFR=-1, lastFC=-1, lastTR=-1, lastTC=-1;

    // ---- 实时搜索统计（用于遮罩显示）----
    private volatile AIEngine.SearchStats latestStats = null;

    // ---- 棋盘翻转（人执黑时翻转，使人方在下方）----
    private boolean boardFlipped = false;

    // ---- 右侧组件 ----
    private JLabel redTimeLabel, blackTimeLabel;
    private JLabel redSideLabel, blackSideLabel;
    private javax.swing.JTable notationTable;
    private javax.swing.table.DefaultTableModel notationModel;
    private JLabel statusLabel;
    private JLabel sourceLabel;  // 着法来源（云库/开局库/AI搜索+统计）
    private JLabel mateLabel;    // 绝杀提示（"X步绝杀" 或 空）
    private JTextArea bestMoveArea; // 最优着法PV主线（纯走法）
    private JLabel advantageLabel;  // 优势评分标签（"红优200分"/"均势"/"黑优150分"）
    private JProgressBar advantageBar; // 视觉优势条
    private JPanel boardPanel;
    private JPanel sidePanel;          // 右侧信息面板（动态宽度）
    private JPanel timePanelContainer; // 时间面板容器（翻转时重排）
    private Timer clockTimer;
    private Timer animTimer;   // 思考动画定时器（成员变量，避免泄漏）

    // 记录上一次展示的局面分（用于人走棋后实时刷新绝杀提示）
    private volatile int lastBoardScore = 0;

    // ---- 暂停 ----
    private boolean paused = false;
    private JMenuItem pauseMenuItem;  // 暂停/继续菜单项（成员变量方便更新文字）

    // ---- 空棋盘模式（未开始对局）----
    private boolean idleMode = false;  // true=空棋盘等待状态，不允许走棋

    // ---- 引擎配置持久化 ----
    private static final Preferences PREFS = Preferences.userNodeForPackage(ChessPanel.class);
    private static final String PREF_ENGINE_TYPE = "engineType";
    private static final String PREF_ENGINE_PATH = "enginePath";

    /** 保存当前引擎配置到系统Preferences（跨启动持久化） */
    private void saveEngineConfig() {
        PREFS.put(PREF_ENGINE_TYPE, currentEngineType.name());
        PREFS.put(PREF_ENGINE_PATH, currentEnginePath);
    }

    /**
     * 从系统Preferences加载引擎配置，并在后台线程自动连接外部引擎。
     * 需在UI初始化完成后调用（保证 engineMenuItem 等控件已创建）。
     */
    private void loadEngineConfig() {
        String typeName = PREFS.get(PREF_ENGINE_TYPE, EngineSelectDialog.EngineType.BUILTIN.name());
        String path     = PREFS.get(PREF_ENGINE_PATH, "");
        EngineSelectDialog.EngineType savedType;
        try {
            savedType = EngineSelectDialog.EngineType.valueOf(typeName);
        } catch (Exception e) {
            savedType = EngineSelectDialog.EngineType.BUILTIN;
        }
        if (savedType == EngineSelectDialog.EngineType.BUILTIN || path.isEmpty()) return;

        // 异步连接上次选择的外部引擎
        final EngineSelectDialog.EngineType finalType = savedType;
        final String finalPath = path;
        ExternalEngine.Protocol proto = finalType == EngineSelectDialog.EngineType.EXTERNAL_UCCI
                ? ExternalEngine.Protocol.UCCI : ExternalEngine.Protocol.UCI;
        ExternalEngine eng = new ExternalEngine(finalPath, proto);
        // 预载上次保存的UCI参数（在start()握手后、isready前自动发送）
        applyStoredEngineOptions(eng);
        if (engineMenuItem != null)
            engineMenuItem.setText("AI引擎: 连接中...(E)");
        new Thread(() -> {
            boolean ok = false;
            try { ok = eng.start(); } catch (Exception ignored) {}
            final boolean success = ok;
            SwingUtilities.invokeLater(() -> {
                if (success) {
                    externalEngine = eng;
                    currentEngineType = finalType;
                    currentEnginePath = finalPath;
                    if (engineMenuItem != null)
                        engineMenuItem.setText("AI引擎: " + eng.getName() + "(E)");
                } else {
                    eng.stop();
                    currentEngineType = EngineSelectDialog.EngineType.BUILTIN;
                    currentEnginePath = "";
                    if (engineMenuItem != null)
                        engineMenuItem.setText("AI引擎: 内置(E)");
                }
            });
        }, "engine-autoconnect").start();
    }

    /** 将Preferences中保存的UCI参数预载到引擎对象（start()握手时自动发送） */
    private void applyStoredEngineOptions(ExternalEngine eng) {
        String[] keys = {"Threads", "Hash", "MultiPV", "Move Overhead", "UCI_ShowWDL"};
        String[] prefKeys = {"opt.Threads", "opt.Hash", "opt.MultiPV", "opt.Move Overhead", "opt.UCI_ShowWDL"};
        String[] defaults = {"1", "16", "1", "10", "false"};
        for (int i = 0; i < keys.length; i++) {
            String val = PREFS.get(prefKeys[i], defaults[i]);
            // 只有与默认值不同时才设置，避免不必要的setoption
            if (!val.equals(defaults[i])) {
                eng.setOption(keys[i], val);
            }
        }
    }

    // ===================== 构造 =====================
    /** 空棋盘构造（启动时用）：显示空棋盘，等待用户从菜单选择操作 */
    public ChessPanel() {
        idleMode = true;
        animTimer = new Timer(350, e -> { if (aiThinking) boardPanel.repaint(); });
        animTimer.setRepeats(true);

        setLayout(new BorderLayout());
        boardPanel = createBoardPanel();
        add(boardPanel, BorderLayout.CENTER);
        sidePanel = createSidePanel();
        add(sidePanel, BorderLayout.EAST);

        // 清空棋盘，显示欢迎状态
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                gs.board.grid[r][c] = null;
        gs.gameOver = true; // 阻止走棋和AI
        redSideLabel.setText("红方");
        blackSideLabel.setText("黑方");
        statusLabel.setText("请从菜单选择操作");
        statusLabel.setForeground(new Color(0x555555));
        advantageLabel.setText("—");
        updateTimeLabels();
        refreshTimePanelOrder();

        ai.setStatsListener(s -> {
            latestStats = s;
            SwingUtilities.invokeLater(() -> {
                updateSourceLabel(s);
                updateBestMoveArea(s);
                updateMateLabel(s);
                updateAdvantage(s.boardScore);
            });
        });
        startClock();
        // 加载上次保存的引擎配置（UI初始化完毕后，在EDT中延迟执行）
        SwingUtilities.invokeLater(this::loadEngineConfig);
    }

    /** 带参构造：由开局对话框传入设置 */
    public ChessPanel(boolean humanIsRed,
                      GameState.Difficulty difficulty,
                      GameState.TimeControl timeControl,
                      int tcBaseMinutes, int tcIncSeconds) {
        // 应用时制设置
        gs.humanIsRed    = humanIsRed;
        gs.difficulty    = difficulty;
        gs.timeControl   = timeControl;
        gs.tcBaseMinutes = tcBaseMinutes;
        gs.tcIncSeconds  = tcIncSeconds;
        gs.reset(); // reset 会用 tcBaseMinutes 初始化时间

        // 构造时初始化动画定时器
        animTimer = new Timer(350, e -> { if (aiThinking) boardPanel.repaint(); });
        animTimer.setRepeats(true);

        setLayout(new BorderLayout());
        boardPanel = createBoardPanel();
        add(boardPanel, BorderLayout.CENTER);
        sidePanel = createSidePanel();
        add(sidePanel, BorderLayout.EAST);

        // 更新侧边栏标签
        if (gs.humanIsRed) {
            redSideLabel.setText("红方（你）");
            blackSideLabel.setText("黑方（AI）");
        } else {
            redSideLabel.setText("红方（AI）");
            blackSideLabel.setText("黑方（你）");
        }
        updateTimeLabels();

        // 棋盘翻转：人执黑时翻转，使人方在下方
        boardFlipped = !gs.humanIsRed;
        refreshTimePanelOrder(); // 同步时间面板顺序

        // 注册 AI 搜索统计监听
        ai.setStatsListener(s -> {
            latestStats = s;
            SwingUtilities.invokeLater(() -> {
                updateSourceLabel(s);
                updateBestMoveArea(s);
                updateMateLabel(s);
                updateAdvantage(s.boardScore);
            });
        });

        startClock();

        // 若AI先走（人执黑，红方先手），立即触发AI
        if (gs.redTurn != gs.humanIsRed) {
            SwingUtilities.invokeLater(this::triggerAI);
        }
    }

    private static String formatNodes(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ===================== 坐标转换（棋盘翻转支持）=====================
    /** 将逻辑行(row)映射为屏幕行（翻转时倒置） */
    private int toScreenRow(int row) { return boardFlipped ? 9 - row : row; }
    /** 将屏幕行映射为逻辑行 */
    private int toLogicRow(int screenRow) { return boardFlipped ? 9 - screenRow : screenRow; }
    /** 将屏幕像素y坐标转换为逻辑行（使用动态边距/格距） */
    private int pixelToLogicRow(int py) {
        int screenRow = Math.round((float)(py - dynOffY - dynMargin) / dynCell);
        return toLogicRow(screenRow);
    }
    /** 将逻辑列映射为屏幕列（翻转时左右互换） */
    private int toScreenCol(int col) { return boardFlipped ? 8 - col : col; }
    /** 将屏幕列映射为逻辑列 */
    private int toLogicCol(int screenCol) { return boardFlipped ? 8 - screenCol : screenCol; }
    /** 将屏幕像素x坐标转换为逻辑列（使用动态边距/格距） */
    private int pixelToLogicCol(int px) {
        int screenCol = Math.round((float)(px - dynOffX - dynMargin) / dynCell);
        return toLogicCol(screenCol);
    }

    // ===================== 棋盘面板 =====================
    private JPanel createBoardPanel() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // 动态计算当前面板尺寸对应的格距/边距/棋子半径
                int pw = getWidth(), ph = getHeight();
                // 棋盘宽方向：9列×cell + 2×margin；高方向：10行×cell + 2×margin
                // 让棋盘尽量充满面板，保持比例
                int cellW = (pw - MARGIN * 2) / 8;  // 按宽计算
                int cellH = (ph - MARGIN * 2) / 9;  // 按高计算
                int cell = Math.max(30, Math.min(cellW, cellH)); // 取较小者保持比例
                int margin = (int)(cell * MARGIN / (double)CELL);
                margin = Math.max(20, margin);
                // 重新居中：计算实际绘制区域的偏移
                int boardPixW = margin * 2 + cell * 8;
                int boardPixH = margin * 2 + cell * 9;
                int offX = Math.max(0, (pw - boardPixW) / 2);
                int offY = Math.max(0, (ph - boardPixH) / 2);
                int pieceR = Math.max(12, (int)(cell * PIECE_R / (double)CELL));
                dynCell   = cell;
                dynMargin = margin;
                dynPieceR = pieceR;
                dynOffX   = offX;
                dynOffY   = offY;

                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                drawBoard(g2);
                drawLastMove(g2);
                drawHighlight(g2);
                // 关键：AI思考时用快照，否则用真实棋盘
                Piece[][] snap = renderSnapshot;
                drawPieces(g2, snap != null ? snap : gs.board.grid);
                if (aiThinking) drawThinkingOverlay(g2);
            }
        };
        p.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        p.setBackground(new Color(0xDEB887));
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (gs.gameOver || aiThinking) return;
                if (gs.redTurn == gs.humanIsRed)
                    handleClick(e.getX(), e.getY());
            }
        });
        return p;
    }

    // ===================== 右侧面板 =====================
    private JPanel createSidePanel() {
        JPanel side = new JPanel(new BorderLayout(4, 4));
        side.setPreferredSize(new Dimension(SIDE_W, BOARD_H));
        side.setMinimumSize(new Dimension(370, 500));
        side.setBackground(new Color(0xF5E6C8));
        side.setBorder(new EmptyBorder(8, 6, 8, 8));

        // ── 顶部：计时区 + 优势条 ──
        JPanel topAll = new JPanel(new BorderLayout(0, 3));
        topAll.setOpaque(false);

        timePanelContainer = new JPanel(new GridLayout(4, 1, 2, 3));
        timePanelContainer.setOpaque(false);
        JLabel bLabel = new JLabel("黑方（AI）", SwingConstants.CENTER);
        blackSideLabel = bLabel;
        bLabel.setFont(new Font("宋体", Font.BOLD, 13));
        blackTimeLabel = new JLabel("10:00", SwingConstants.CENTER);
        blackTimeLabel.setFont(new Font("Monospaced", Font.BOLD, 26));
        blackTimeLabel.setForeground(new Color(0x333333));
        JLabel rLabel = new JLabel("红方（你）", SwingConstants.CENTER);
        redSideLabel = rLabel;
        rLabel.setFont(new Font("宋体", Font.BOLD, 13));
        redTimeLabel = new JLabel("10:00", SwingConstants.CENTER);
        redTimeLabel.setFont(new Font("Monospaced", Font.BOLD, 26));
        redTimeLabel.setForeground(new Color(0xB22222));
        // 默认顺序：黑方在上（人执红，红方棋盘在下）
        timePanelContainer.add(bLabel); timePanelContainer.add(blackTimeLabel);
        timePanelContainer.add(rLabel); timePanelContainer.add(redTimeLabel);
        topAll.add(timePanelContainer, BorderLayout.NORTH);

        // 优势评分条
        JPanel advPanel = new JPanel(new BorderLayout(0, 2));
        advPanel.setOpaque(false);
        advantageLabel = new JLabel("均势", SwingConstants.CENTER);
        advantageLabel.setFont(new Font("宋体", Font.BOLD, 12));
        advantageLabel.setForeground(new Color(0x555555));
        advPanel.add(advantageLabel, BorderLayout.NORTH);

        advantageBar = new JProgressBar(0, 200);
        advantageBar.setValue(100); // 居中=均势
        advantageBar.setStringPainted(false);
        advantageBar.setPreferredSize(new Dimension(SIDE_W - 20, 14));
        advantageBar.setBackground(new Color(0x222222)); // 黑方
        advantageBar.setForeground(new Color(0xCC2222)); // 红方
        advantageBar.setBorder(BorderFactory.createLineBorder(new Color(0xAA8844), 1));
        advPanel.add(advantageBar, BorderLayout.CENTER);
        topAll.add(advPanel, BorderLayout.SOUTH);

        side.add(topAll, BorderLayout.NORTH);

        // ── 中部：棋谱（上）+ AI预测（下），用 JSplitPane 可拖分 ──

        // 棋谱：JTable 多列表格，每行两手棋（序号+红方+黑方 × 2列）
        notationModel = new javax.swing.table.DefaultTableModel(
                new Object[]{"序", "红方", "黑方", "序", "红方", "黑方"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        notationTable = new javax.swing.JTable(notationModel);
        notationTable.setFont(new Font("宋体", Font.PLAIN, 12));
        notationTable.setRowHeight(20);
        notationTable.setBackground(new Color(0xFFFAF0));
        notationTable.setGridColor(new Color(0xDDC080));
        notationTable.setShowGrid(true);
        notationTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        notationTable.getTableHeader().setFont(new Font("宋体", Font.BOLD, 12));
        notationTable.getTableHeader().setBackground(new Color(0xF0D080));
        notationTable.getTableHeader().setReorderingAllowed(false);
        notationTable.getTableHeader().setResizingAllowed(true);
        // 列宽：序号窄，走法均分，两组各占一半
        int[] colWidths = {30, 72, 72, 30, 72, 72};
        for (int ci = 0; ci < colWidths.length; ci++) {
            notationTable.getColumnModel().getColumn(ci).setPreferredWidth(colWidths[ci]);
            if (ci == 0 || ci == 3)
                notationTable.getColumnModel().getColumn(ci).setMaxWidth(40);
        }
        // 居中对齐（所有列）
        javax.swing.table.DefaultTableCellRenderer center = new javax.swing.table.DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        for (int ci = 0; ci < 6; ci++)
            notationTable.getColumnModel().getColumn(ci).setCellRenderer(center);

        JScrollPane scroll = new JScrollPane(notationTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xC8A060)), "棋谱",
                TitledBorder.CENTER, TitledBorder.TOP,
                new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));

        // ── 着法来源行 + 绝杀提示（放在PV面板顶部）──
        sourceLabel = new JLabel("<html><center>等待AI走棋...</center></html>", SwingConstants.CENTER);
        sourceLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        sourceLabel.setForeground(new Color(0x555555));
        sourceLabel.setBackground(new Color(0xEEE0B0));
        sourceLabel.setOpaque(true);
        sourceLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));

        mateLabel = new JLabel(" ", SwingConstants.CENTER);
        mateLabel.setFont(new Font("宋体", Font.BOLD, 13));
        mateLabel.setForeground(new Color(0xCC0000));
        mateLabel.setOpaque(true);
        mateLabel.setBackground(new Color(0xF5E6C8));
        mateLabel.setBorder(BorderFactory.createLineBorder(new Color(0xC8A060)));

        // PV区：不折行，每行一条走法，支持水平+垂直滚动
        bestMoveArea = new JTextArea();
        bestMoveArea.setFont(new Font("宋体", Font.PLAIN, 12));
        bestMoveArea.setEditable(false);
        bestMoveArea.setBackground(new Color(0xFFF8E1));
        bestMoveArea.setForeground(new Color(0x5C3317));
        bestMoveArea.setLineWrap(false);  // 不折行，支持水平滚动
        bestMoveArea.setText("AI尚未走棋");
        JScrollPane pvScroll = new JScrollPane(bestMoveArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        pvScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xC8A060)), "AI预测走法",
                TitledBorder.CENTER, TitledBorder.TOP,
                new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));

        JPanel pvContainer = new JPanel(new BorderLayout(0, 2));
        pvContainer.setOpaque(false);
        JPanel pvTop = new JPanel(new BorderLayout(0, 2));
        pvTop.setOpaque(false);
        pvTop.add(sourceLabel, BorderLayout.NORTH);
        pvTop.add(mateLabel,   BorderLayout.SOUTH);
        pvContainer.add(pvTop,    BorderLayout.NORTH);
        pvContainer.add(pvScroll, BorderLayout.CENTER);

        // 用 JSplitPane 分割棋谱(上)与AI预测(下)，棋谱占 40%，PV占 60%
        JSplitPane splitCenter = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, pvContainer);
        splitCenter.setResizeWeight(0.35);   // 棋谱初始占 35%
        splitCenter.setDividerSize(5);
        splitCenter.setContinuousLayout(true);
        splitCenter.setOpaque(false);
        splitCenter.setBorder(null);

        side.add(splitCenter, BorderLayout.CENTER);

        // ── 底部：仅保留状态行（按钮已移至菜单栏）──
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setOpaque(false);

        statusLabel = new JLabel("红方先走", SwingConstants.CENTER);
        statusLabel.setFont(new Font("宋体", Font.BOLD, 13));
        statusLabel.setForeground(new Color(0xB22222));
        bottom.add(statusLabel, BorderLayout.NORTH);

        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    /**
     * 创建菜单栏，由 Main.java 调用后安装到 JFrame 上。
     * 菜单栏包含：对局、设置、视图 三个菜单。
     */
    public JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ── 对局 ──
        JMenu gameMenu = new JMenu("对局(G)");
        gameMenu.setMnemonic('G');

        // 开始对局（仅空棋盘/初始状态下需要，功能等同于新游戏）
        JMenuItem startItem = new JMenuItem("开始对局(B)");
        startItem.setMnemonic('B');
        startItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        startItem.addActionListener(e -> showNewGameDialog());

        JMenuItem undoItem = new JMenuItem("悔棋(U)");
        undoItem.setMnemonic('U');
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> doUndo());

        pauseMenuItem = new JMenuItem("暂停/继续(P)");
        pauseMenuItem.setMnemonic('P');
        pauseMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        pauseMenuItem.addActionListener(e -> togglePause());

        JMenuItem historyItem = new JMenuItem("历史棋谱(H)");
        historyItem.setMnemonic('H');
        historyItem.addActionListener(e -> ReviewPanel.showReviewDialog(this));

        JMenuItem editItem = new JMenuItem("摆谱/编辑棋局(E)");
        editItem.setMnemonic('E');
        editItem.addActionListener(e -> enterEditMode());

        JSeparator sep1 = new JSeparator();

        JMenuItem saveFenItem = new JMenuItem("保存局面FEN(S)");
        saveFenItem.setMnemonic('S');
        saveFenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveFenItem.addActionListener(e -> saveFEN());

        JMenuItem loadFenItem = new JMenuItem("读取局面FEN(O)");
        loadFenItem.setMnemonic('O');
        loadFenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        loadFenItem.addActionListener(e -> loadFEN());

        // 保存/加载对局（继续对局）
        JMenuItem saveGameItem = new JMenuItem("保存棋局(Ctrl+W)");
        saveGameItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        saveGameItem.addActionListener(e -> {
            if (idleMode) {
                setStatus("还没有开始对局", true);
                return;
            }
            String result = gs.gameOver ? "已结束" : "进行中";
            gs.saveGame(result);
            String saveDir = GameState.getSaveDir().toString();
            setStatus("棋局已保存至 " + saveDir, true);
            JOptionPane.showMessageDialog(this,
                    "棋局已保存！\n保存目录：" + saveDir + "\n\n" +
                    (gs.gameOver ? "下次可通过[加载棋局]进行复盘分析。" : "下次可通过[加载棋局]菜单继续对弈。"),
                    "保存成功", JOptionPane.INFORMATION_MESSAGE);
        });

        JMenuItem loadGameItem = new JMenuItem("加载棋局(Ctrl+L)");
        loadGameItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
        loadGameItem.addActionListener(e -> showLoadGameDialog());

        JMenuItem exitItem = new JMenuItem("退出(Q)");
        exitItem.setMnemonic('Q');
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK));
        exitItem.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "确认退出游戏？", "退出",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) System.exit(0);
        });

        gameMenu.add(startItem);
        gameMenu.add(undoItem);
        gameMenu.add(pauseMenuItem);
        gameMenu.add(sep1);
        gameMenu.add(historyItem);
        gameMenu.add(editItem);
        gameMenu.add(new JSeparator());
        gameMenu.add(saveGameItem);
        gameMenu.add(loadGameItem);
        gameMenu.add(new JSeparator());
        gameMenu.add(saveFenItem);
        gameMenu.add(loadFenItem);
        gameMenu.add(new JSeparator());
        gameMenu.add(exitItem);

        // ── 设置 ──
        JMenu settingsMenu = new JMenu("设置(S)");
        settingsMenu.setMnemonic('S');

        JMenuItem diffItem = new JMenuItem("AI难度(D)");
        diffItem.setMnemonic('D');
        diffItem.addActionListener(e -> showDifficultyDialog());

        // 引擎选择
        engineMenuItem = new JMenuItem("AI引擎: 内置(E)");
        engineMenuItem.setMnemonic('E');
        engineMenuItem.addActionListener(e -> showEngineSelectDialog());

        // 皮卡鱼/外部引擎参数设置
        JMenuItem engineOptionsItem = new JMenuItem("引擎参数设置(O)");
        engineOptionsItem.addActionListener(e -> showEngineOptionsDialog());

        // 音效开关（CheckBox菜单项）
        JCheckBoxMenuItem soundItem = new JCheckBoxMenuItem("音效(M)", sound.isEnabled());
        soundItem.setMnemonic('M');
        soundItem.addActionListener(e -> sound.setEnabled(soundItem.isSelected()));

        // 开局库设置
        JMenuItem bookItem = new JMenuItem("开局库设置(K)");
        bookItem.setMnemonic('K');
        bookItem.addActionListener(e -> showBookModeDialog());

        settingsMenu.add(engineMenuItem);
        settingsMenu.add(engineOptionsItem);
        settingsMenu.add(diffItem);
        settingsMenu.add(bookItem);
        settingsMenu.add(soundItem);

        // ── 视图 ──
        JMenu viewMenu = new JMenu("视图(V)");
        viewMenu.setMnemonic('V');

        JMenuItem flipItem = new JMenuItem("翻转棋盘(F)");
        flipItem.setMnemonic('F');
        flipItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flipItem.addActionListener(e -> {
            boardFlipped = !boardFlipped;
            refreshTimePanelOrder();
            boardPanel.repaint();
        });

        viewMenu.add(flipItem);

        menuBar.add(gameMenu);
        menuBar.add(settingsMenu);
        menuBar.add(viewMenu);
        return menuBar;
    }

    private void addBtn(JPanel p, String txt, ActionListener al) {
        JButton b = new JButton(txt);
        b.setFont(new Font("宋体", Font.PLAIN, 12));
        b.addActionListener(al);
        p.add(b);
    }

    // ===================== 时钟 =====================
    private void startClock() {
        clockTimer = new Timer(1000, e -> {
            if (!gs.gameOver && !paused) {
                // 修复：AI思考时仍对当前走方（AI方）计时，不跳过
                if (!gs.tick()) {
                    gs.gameOver = true;
                    // 如果AI还在思考中，中断它
                    if (aiThinking) {
                        animTimer.stop();
                        aiThinking = false;
                        renderSnapshot = null;
                    }
                    sound.playTimeout();
                    String who = gs.redTurn ? "红方" : "黑方";
                    setStatus(who + " 超时，游戏结束！", gs.redTurn);
                    boardPanel.repaint();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, who + " 超时！",
                                    "超时", JOptionPane.WARNING_MESSAGE));
                }
                updateTimeLabels();
                boardPanel.repaint();
            }
        });
        clockTimer.start();
    }

    private void updateTimeLabels() {
        redTimeLabel.setText(gs.formatTime(gs.redTimeLeft));
        blackTimeLabel.setText(gs.formatTime(gs.blackTimeLeft));
        redTimeLabel.setForeground(gs.redTimeLeft < 30 ? Color.RED : new Color(0xB22222));
        blackTimeLabel.setForeground(gs.blackTimeLeft < 30 ? Color.RED : new Color(0x333333));
    }

    /**
     * 根据 boardFlipped 重排时间面板的顺序：
     *   boardFlipped=false（红方在下）：黑方在上，红方在下
     *   boardFlipped=true （黑方在下）：红方在上，黑方在下
     */
    private void refreshTimePanelOrder() {
        timePanelContainer.removeAll();
        if (boardFlipped) {
            // 黑方在下（人执黑）→ 红方(AI)在上，黑方(人)在下
            timePanelContainer.add(redSideLabel);
            timePanelContainer.add(redTimeLabel);
            timePanelContainer.add(blackSideLabel);
            timePanelContainer.add(blackTimeLabel);
        } else {
            // 红方在下（人执红）→ 黑方(AI)在上，红方(人)在下
            timePanelContainer.add(blackSideLabel);
            timePanelContainer.add(blackTimeLabel);
            timePanelContainer.add(redSideLabel);
            timePanelContainer.add(redTimeLabel);
        }
        timePanelContainer.revalidate();
        timePanelContainer.repaint();
    }

    // ===================== 人类走棋 =====================
    private void handleClick(int px, int py) {
        if (idleMode) return; // 空棋盘模式，不允许走棋
        int col = pixelToLogicCol(px);
        int row = pixelToLogicRow(py);
        if (!gs.board.inBounds(row, col)) return;
        Piece piece = gs.board.getPiece(row, col);

        if (selRow == -1) {
            if (piece != null && piece.isRed == gs.humanIsRed) {
                selRow = row; selCol = col;
                legalMoves = gs.board.getLegalMoves(row, col);
            }
        } else {
            boolean legal = false;
            if (legalMoves != null)
                for (int[] m : legalMoves) if (m[0]==row && m[1]==col) { legal=true; break; }

            if (legal) {
                boolean wasRed = gs.redTurn;
                lastFR=selRow; lastFC=selCol; lastTR=row; lastTC=col;
                boolean isCapture = gs.board.getPiece(row, col) != null;
                Piece cap = gs.doMove(selRow, selCol, row, col);
                gs.applyIncrement(wasRed); // 加秒制：走完后加秒
                selRow=-1; selCol=-1; legalMoves=null;

                if (isCapture) sound.playCapture(); else sound.playMove();
                updateNotation();
                // ★ 人走棋后不立即更新优势条（避免"谁走棋谁领先"错觉）
                // 优势条由 AI 搜索结果统一更新（statsListener 中的 updateAdvantage）
                // 仅在游戏结束时才通过 checkGameOver 极值更新
                mateLabel.setText(" ");
                mateLabel.setForeground(new Color(0xCC0000));
                mateLabel.setBackground(new Color(0xF5E6C8));
                boardPanel.repaint();

                if (!checkGameOver(cap)) triggerAI();
            } else if (piece != null && piece.isRed == gs.humanIsRed) {
                selRow=row; selCol=col;
                legalMoves = gs.board.getLegalMoves(row, col);
            } else {
                selRow=-1; selCol=-1; legalMoves=null;
            }
        }
        boardPanel.repaint();
    }

    // ===================== AI走棋 =====================
    private void triggerAI() {
        if (gs.gameOver) return;

        // ★ 关键：AI开始思考前，先拍一张棋盘快照用于渲染
        renderSnapshot = gs.board.copyGrid();
        aiThinking = true;
        animTimer.start();  // 启动思考动画刷新

        String diffLabel = gs.difficulty.label;
        int timeMs = gs.difficulty.aiTimeMs;

        // ★ 判断使用外部引擎还是内置AI
        if (externalEngine != null && externalEngine.isReady()) {
            // ── 外部引擎路径：先在后台线程查开局库，命中则直接走棋，否则交给引擎 ──
            final Board boardForBook = new Board();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    boardForBook.grid[r][c] = gs.board.grid[r][c] != null
                            ? gs.board.grid[r][c].copy() : null;
            final boolean redTurnForBook = gs.redTurn;

            setStatus("查询开局库...", false);
            new Thread(() -> {
                // 后台查开局库（可能有网络请求，不阻塞EDT）
                OpeningBook.LookupResult bookResult = OpeningBook.lookupWithSource(boardForBook, redTurnForBook);
                if (bookResult != null) {
                    final int[] bmv = bookResult.move;
                    // 验证：起点有棋子且属于当前走棋方，终点在棋盘内
                    Piece movingPiece = boardForBook.getPiece(bmv[0], bmv[1]);
                    boolean valid = movingPiece != null && movingPiece.isRed == redTurnForBook
                            && bmv[2] >= 0 && bmv[2] <= 9 && bmv[3] >= 0 && bmv[3] <= 8;
                    if (!valid) bookResult = null;
                }

                final OpeningBook.LookupResult finalBook = bookResult;
                SwingUtilities.invokeLater(() -> {
                    if (!aiThinking) return; // 游戏状态已变（如用户新开游戏）
                    if (finalBook != null) {
                        // 开局库命中，直接走棋
                        final int[] bmv = finalBook.move;
                        final boolean fromCloud = finalBook.fromCloud;
                        animTimer.stop();
                        renderSnapshot = null;
                        boolean wasRed = gs.redTurn;
                        lastFR=bmv[0]; lastFC=bmv[1]; lastTR=bmv[2]; lastTC=bmv[3];
                        boolean isCapture = gs.board.getPiece(bmv[2], bmv[3]) != null;
                        Piece cap = gs.doMove(bmv[0], bmv[1], bmv[2], bmv[3]);
                        gs.applyIncrement(wasRed);
                        aiThinking = false;
                        if (isCapture) sound.playCapture(); else sound.playMove();
                        updateNotation();
                        updateAdvantage(Evaluator.evaluate(gs.board));
                        sourceLabel.setText("<html><center>" + (fromCloud ? "☁ 云库" : "📖 本地开局库") + "</center></html>");
                        mateLabel.setText(" ");
                        mateLabel.setBackground(new Color(0xF5E6C8));
                        bestMoveArea.setText("（开局库走法）");
                        checkGameOver(cap);
                        boardPanel.repaint();
                    } else {
                        // 开局库未命中，交给外部引擎
                        triggerExternalEngine();
                    }
                });
            }, "BookLookup-ExtEngine").start();
            return;
        }

        // 无外部引擎，走内置AI路径
        triggerBuiltinAI();
    }
    private void triggerExternalEngine() {
        if (!aiThinking || gs.gameOver) return;
        int timeMs = gs.difficulty.aiTimeMs;
        if (externalEngine == null || !externalEngine.isReady()) return;

        setStatus("外部引擎思考中... [" + externalEngine.getName() + "]", false);
        sourceLabel.setText("<html><center>🔌 " + externalEngine.getName() + "</center></html>");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));

            final Board boardSnapshot = new Board();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    boardSnapshot.grid[r][c] = gs.board.grid[r][c] != null
                            ? gs.board.grid[r][c].copy() : null;
            final boolean redTurnSnap = gs.redTurn;

            externalEngine.requestMove(boardSnapshot, redTurnSnap, timeMs,
                mv -> SwingUtilities.invokeLater(() -> {
                    animTimer.stop();
                    renderSnapshot = null;
                    if (mv != null && mv[0] != -1) {
                        boolean wasRed = gs.redTurn;
                        lastFR=mv[0]; lastFC=mv[1]; lastTR=mv[2]; lastTC=mv[3];
                        boolean isCapture = gs.board.getPiece(mv[2], mv[3]) != null;
                        Piece cap = gs.doMove(mv[0], mv[1], mv[2], mv[3]);
                        gs.applyIncrement(wasRed);
                        aiThinking = false;
                        if (isCapture) sound.playCapture(); else sound.playMove();
                        updateNotation();
                        updateAdvantage(Evaluator.evaluate(gs.board));
                        checkGameOver(cap);
                    } else {
                        aiThinking = false;
                        gs.gameOver = true;
                        gs.saveGame("AI认负");
                        setStatus("外部引擎无子可走，你赢了！", true);
                        sound.playWin();
                    }
                    boardPanel.repaint();
                }),
                info -> {
                    // 解析皮卡鱼 info 行：score cp / score mate / depth / pv
                    // 格式：info depth N seldepth M score cp X nodes N nps N pv a0b1 ...
                    //   或：info depth N ... score mate M pv ...
                    final String fi = info;
                    if (info.contains("score cp") || info.contains("score mate") || info.contains("depth")) {
                        try {
                            String[] tokens = info.split("\\s+");
                            int depth = 0;
                            int cp = Integer.MIN_VALUE;
                            int mateIn = 0;
                            int pvStart = -1;

                            for (int i = 0; i < tokens.length; i++) {
                                switch (tokens[i]) {
                                    case "depth": if (i+1<tokens.length) depth = Integer.parseInt(tokens[i+1]); break;
                                    case "cp":    if (i+1<tokens.length) cp    = Integer.parseInt(tokens[i+1]); break;
                                    case "mate":  if (i+1<tokens.length) mateIn= Integer.parseInt(tokens[i+1]); break;
                                    case "pv":    pvStart = i+1; break;
                                }
                            }

                            final int fDepth  = depth;
                            final int fCp     = cp;
                            final int fMateIn = mateIn;
                            final int fPvStart = pvStart;
                            final boolean aiIsRed = redTurnSnap;

                            SwingUtilities.invokeLater(() -> {
                                // 更新评分条
                                if (fCp != Integer.MIN_VALUE) {
                                    int boardScore = aiIsRed ? fCp : -fCp;
                                    updateAdvantage(boardScore);
                                } else if (fMateIn != 0) {
                                    // score mate M: 正值=当前方赢，负值=当前方输
                                    int abs = Math.abs(fMateIn);
                                    if (fMateIn > 0) updateAdvantage(aiIsRed ? 99999 : -99999);
                                    else             updateAdvantage(aiIsRed ? -99999 : 99999);
                                }

                                // 更新绝杀提示
                                if (fMateIn != 0) {
                                    String attacker, defender;
                                    if (fMateIn > 0) {
                                        // 当前走棋方能绝杀
                                        attacker = aiIsRed ? "红" : "黑";
                                        defender = aiIsRed ? "黑" : "红";
                                        mateLabel.setText("⚑ " + attacker + "方 " + Math.abs(fMateIn) + " 步绝杀" + defender + "方！");
                                        mateLabel.setForeground(Color.WHITE);
                                        mateLabel.setBackground(new Color(0xCC2222));
                                    } else {
                                        // 对方能绝杀当前走棋方
                                        attacker = aiIsRed ? "黑" : "红";
                                        defender = aiIsRed ? "红" : "黑";
                                        mateLabel.setText("⚠ " + attacker + "方 " + Math.abs(fMateIn) + " 步绝杀" + defender + "方！");
                                        mateLabel.setForeground(Color.WHITE);
                                        mateLabel.setBackground(new Color(0x226622));
                                    }
                                } else {
                                    mateLabel.setText(" ");
                                    mateLabel.setBackground(new Color(0xF5E6C8));
                                }

                                // 更新AI预测走法区：把皮卡鱼PV转成中文棋谱
                                if (fPvStart >= 0 && fPvStart < fi.split("\\s+").length) {
                                    String[] tk = fi.split("\\s+");
                                    StringBuilder pvSb = new StringBuilder();
                                    pvSb.append(String.format("▶ 深度%-2d  ", fDepth));
                                    // 在当前局面基础上逐步演示PV走法
                                    Board sim = new Board();
                                    for (int r2=0;r2<10;r2++)
                                        for (int c2=0;c2<9;c2++)
                                            sim.grid[r2][c2] = boardSnapshot.grid[r2][c2] != null
                                                ? boardSnapshot.grid[r2][c2].copy() : null;
                                    boolean turn = aiIsRed;
                                    for (int i = fPvStart; i < tk.length && i < fPvStart+10; i++) {
                                        int[] mv2 = ExternalEngine.iccsToInternal(tk[i]);
                                        if (mv2 == null) break;
                                        Piece[][] snap = sim.copyGrid();
                                        String nota = GameState.buildNotationStatic(mv2[0],mv2[1],mv2[2],mv2[3],snap,turn);
                                        if (i > fPvStart) pvSb.append(" → ");
                                        pvSb.append(nota);
                                        try { sim.move(mv2[0],mv2[1],mv2[2],mv2[3]); } catch(Exception ignored2){}
                                        turn = !turn;
                                    }
                                    if (fMateIn != 0) pvSb.append("  ✦绝杀");
                                    sourceLabel.setText("<html><center>🔌 " + externalEngine.getName()
                                        + "  depth=" + fDepth + "</center></html>");
                                    bestMoveArea.setText(pvSb.toString());
                                    bestMoveArea.setCaretPosition(0);
                                } else {
                                    sourceLabel.setText("<html><center>" + fi.replace("<","&lt;") + "</center></html>");
                                }
                            });
                        } catch (Exception ignored) {
                            String finalInfo = fi;
                            SwingUtilities.invokeLater(() -> sourceLabel.setText(
                                "<html><center>" + finalInfo.replace("<","&lt;") + "</center></html>"));
                        }
                    }
                });
    }

    // ===================== 内置AI =====================
    private void triggerBuiltinAI() {
        if (!aiThinking || gs.gameOver) return;
        String diffLabel = gs.difficulty.label;
        int timeMs = gs.difficulty.aiTimeMs;

        setStatus("查询开局库...", false);
        sourceLabel.setText("<html><center>🔍 查询云库/开局库...</center></html>");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));

        // ── 先在后台查开局库，命中则直接走棋，否则交给内置AI ──
        final Board boardForBook = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                boardForBook.grid[r][c] = gs.board.grid[r][c] != null
                        ? gs.board.grid[r][c].copy() : null;
        final boolean redTurnForBook = gs.redTurn;

        new Thread(() -> {
            OpeningBook.LookupResult bookResult = OpeningBook.lookupWithSource(boardForBook, redTurnForBook);
            if (bookResult != null) {
                final int[] bmv = bookResult.move;
                Piece movingPiece = boardForBook.getPiece(bmv[0], bmv[1]);
                boolean valid = movingPiece != null && movingPiece.isRed == redTurnForBook
                        && bmv[2] >= 0 && bmv[2] <= 9 && bmv[3] >= 0 && bmv[3] <= 8;
                if (!valid) bookResult = null;
            }
            final OpeningBook.LookupResult finalBook = bookResult;
            SwingUtilities.invokeLater(() -> {
                if (!aiThinking) return;
                if (finalBook != null) {
                    // 开局库命中，直接走棋
                    final int[] bmv = finalBook.move;
                    final boolean fromCloud = finalBook.fromCloud;
                    animTimer.stop();
                    renderSnapshot = null;
                    boolean wasRed = gs.redTurn;
                    lastFR=bmv[0]; lastFC=bmv[1]; lastTR=bmv[2]; lastTC=bmv[3];
                    boolean isCapture = gs.board.getPiece(bmv[2], bmv[3]) != null;
                    Piece cap = gs.doMove(bmv[0], bmv[1], bmv[2], bmv[3]);
                    gs.applyIncrement(wasRed);
                    aiThinking = false;
                    if (isCapture) sound.playCapture(); else sound.playMove();
                    updateNotation();
                    updateAdvantage(Evaluator.evaluate(gs.board));
                    sourceLabel.setText("<html><center>" + (fromCloud ? "☁ 云库" : "📖 本地开局库") + "</center></html>");
                    mateLabel.setText(" ");
                    mateLabel.setBackground(new Color(0xF5E6C8));
                    bestMoveArea.setText("（开局库走法）");
                    checkGameOver(cap);
                    boardPanel.repaint();
                } else {
                    // 开局库未命中，启动内置AI搜索
                    setStatus("AI 思考中... [" + diffLabel + "]", false);
                    triggerBuiltinAISearch(timeMs);
                }
            });
        }, "BookLookup-BuiltinAI").start();
    }

    /** 内置AI实际搜索（开局库未命中后调用） */
    private void triggerBuiltinAISearch(int timeMs) {
        if (!aiThinking || gs.gameOver) return;
        // 不在此处更新优势条——等AI搜索过程中通过 statsListener 逐步更新（真实评分）

        // AI在后台线程运行，不会影响EDT和渲染
        SwingWorker<int[], Void> worker = new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                ai.setTimeLimit(timeMs);
                return ai.getBestMove(gs.board, gs.redTurn);
            }

            @Override
                protected void done() {
                    // ★ AI完成后，先停动画，清除快照，再更新棋盘
                    animTimer.stop();
                    renderSnapshot = null;
                try {
                    int[] mv = get();
                    if (mv != null && mv[0] != -1) {
                        boolean wasRed = gs.redTurn;
                        lastFR=mv[0]; lastFC=mv[1]; lastTR=mv[2]; lastTC=mv[3];
                        boolean isCapture = gs.board.getPiece(mv[2], mv[3]) != null;
                        Piece cap = gs.doMove(mv[0], mv[1], mv[2], mv[3]);
                        gs.applyIncrement(wasRed); // 加秒制
                        aiThinking = false;
                        if (isCapture) sound.playCapture(); else sound.playMove();
                        updateNotation();
                        // 更新优势条为走棋后的实际局面评分
                        updateAdvantage(Evaluator.evaluate(gs.board));
                        checkGameOver(cap);
                        SwingUtilities.invokeLater(() -> updateBestMoveArea(latestStats));
                    } else {
                        aiThinking = false;
                        gs.gameOver = true;
                        gs.saveGame("AI认负"); // 自动保存
                        setStatus("AI 无子可走，你赢了！", true);
                        sound.playWin();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    animTimer.stop();
                    aiThinking = false;
                }
                boardPanel.repaint();
            }
        };
        worker.execute();
    }

    // ===================== 胜负 =====================
    private boolean checkGameOver(Piece cap) {
        if (cap != null && cap.type == Piece.Type.KING) {
            boolean humanWon = !cap.isRed == gs.humanIsRed;
            gs.gameOver = true;
            String result = humanWon ? "人胜" : "AI胜";
            String msg = humanWon ? "你赢了！恭喜！" : "AI 胜，再接再厉！";
            gs.saveGame(result); // 自动保存
            setStatus(msg, humanWon);
            // 终局：被吃将一方子力清零，评分推向极端
            updateAdvantage(humanWon ? 99999 : -99999);
            if (humanWon) sound.playWin(); else sound.playLose();
            boardPanel.repaint();
            showEndDialog(msg);
            return true;
        }
        if (!gs.board.hasLegalMoves(gs.redTurn)) {
            boolean inCheck = gs.board.isInCheck(gs.redTurn);
            boolean humanLost = gs.redTurn == gs.humanIsRed;
            gs.gameOver = true;
            // 象棋规则：困毙方（无子可走方）判负，与将死相同处理
            String result = humanLost ? "AI胜" : "人胜";
            String reason = inCheck ? "将死" : "困毙";
            result += "(" + reason + ")";
            String msg = inCheck
                    ? (humanLost ? "你被将死，AI 胜！" : "AI 被将死，你赢了！")
                    : (humanLost ? "你被困毙，AI 胜！" : "AI 被困毙，你赢了！");
            gs.saveGame(result); // 自动保存
            setStatus(msg, !humanLost);
            // 终局：将死或困毙（均为输棋）
            updateAdvantage(humanLost ? -99999 : 99999);
            if (humanLost) sound.playLose(); else sound.playWin();
            boardPanel.repaint();
            showEndDialog(msg);
            return true;
        }
        if (gs.board.isInCheck(gs.redTurn)) {
            setStatus((gs.redTurn ? "红方" : "黑方") + " 被将军！", gs.redTurn);
            sound.playCheck();
        } else {
            setStatus((gs.redTurn == gs.humanIsRed ? "红方（你）" : "AI") + " 走棋",
                    gs.redTurn == gs.humanIsRed);
        }
        return false;
    }

    private void showEndDialog(String msg) {
        SwingUtilities.invokeLater(() -> {
            int ok = JOptionPane.showConfirmDialog(this,
                    msg + "\n\n重新开始？", "游戏结束", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) showNewGameDialog();
        });
    }

    // ===================== 悔棋 =====================
    private void doUndo() {
        if (aiThinking) return;
        if (gs.gameOver) gs.gameOver = false;
        gs.undoTwoSteps();
        selRow=-1; selCol=-1; legalMoves=null;
        lastFR=-1; lastFC=-1; lastTR=-1; lastTC=-1;
        renderSnapshot = null;
        latestStats = null;
        updateNotation();
        bestMoveArea.setText("悔棋后重置");
        sourceLabel.setText("等待AI走棋...");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));
        updateAdvantage(Evaluator.evaluate(gs.board));
        setStatus((gs.redTurn == gs.humanIsRed ? "红方（你）" : "AI") + " 走棋",
                gs.redTurn == gs.humanIsRed);
        boardPanel.repaint();
    }

    // ===================== 新游戏 =====================
    private void showNewGameDialog() {
        if (aiThinking) return;
        JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
        p.add(new JLabel("执棋方："));
        JComboBox<String> sideBox = new JComboBox<>(new String[]{"执红（先手）", "执黑（后手）"});
        sideBox.setSelectedIndex(gs.humanIsRed ? 0 : 1);
        p.add(sideBox);
        p.add(new JLabel("AI难度："));
        String[] diffLabels = {"简单（10秒/步）", "中等（30秒/步）", "困难（2分钟/步）"};
        JComboBox<String> diffBox = new JComboBox<>(diffLabels);
        diffBox.setSelectedIndex(gs.difficulty == GameState.Difficulty.EASY ? 0
                : gs.difficulty == GameState.Difficulty.HARD ? 2 : 1);
        p.add(diffBox);
        p.add(new JLabel("时制方案："));
        String[] presetLabels = new String[GameState.TIME_PRESETS.length];
        for (int i=0; i<presetLabels.length; i++)
            presetLabels[i] = (String)GameState.TIME_PRESETS[i][0];
        JComboBox<String> timeBox = new JComboBox<>(presetLabels);
        timeBox.setSelectedIndex(0);
        p.add(timeBox);
        p.add(new JLabel("时制说明："));
        JLabel tcDesc = new JLabel(getTimePresetDesc(0));
        tcDesc.setFont(new Font("宋体", Font.PLAIN, 11));
        tcDesc.setForeground(new Color(0x555555));
        p.add(tcDesc);
        timeBox.addActionListener(e -> tcDesc.setText(getTimePresetDesc(timeBox.getSelectedIndex())));

        int ok = JOptionPane.showConfirmDialog(this, p, "新游戏设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        gs.humanIsRed = sideBox.getSelectedIndex() == 0;
        gs.difficulty = new GameState.Difficulty[]{
                GameState.Difficulty.EASY,
                GameState.Difficulty.MEDIUM,
                GameState.Difficulty.HARD}[diffBox.getSelectedIndex()];
        int ti = timeBox.getSelectedIndex();
        Object[] preset = GameState.TIME_PRESETS[ti];
        gs.timeControl   = (GameState.TimeControl) preset[1];
        gs.tcBaseMinutes = (int) preset[2];
        gs.tcIncSeconds  = (int) preset[3];
        gs.reset(); // 使用新时制重置

        idleMode = false; // 退出空棋盘模式
        selRow=-1; selCol=-1; legalMoves=null;
        lastFR=-1; lastFC=-1; lastTR=-1; lastTC=-1;
        aiThinking=false; renderSnapshot=null;
        animTimer.stop();
        latestStats = null;
        bestMoveArea.setText("AI尚未走棋");
        sourceLabel.setText("等待AI走棋...");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));
        updateAdvantage(0);

        if (gs.humanIsRed) {
            redSideLabel.setText("红方（你）");
            blackSideLabel.setText("黑方（AI）");
        } else {
            redSideLabel.setText("红方（AI）");
            blackSideLabel.setText("黑方（你）");
        }

        // 棋盘翻转同步
        boardFlipped = !gs.humanIsRed;
        refreshTimePanelOrder();

        updateNotation();
        updateTimeLabels();
        String side = gs.humanIsRed ? "红方（先手）" : "黑方（后手）";
        setStatus("新游戏开始，你执" + side, gs.humanIsRed);
        boardPanel.repaint();

        if (gs.redTurn != gs.humanIsRed) triggerAI();
    }

    private String getTimePresetDesc(int idx) {
        Object[] p = GameState.TIME_PRESETS[idx];
        GameState.TimeControl tc = (GameState.TimeControl)p[1];
        int base = (int)p[2], inc = (int)p[3];
        if (tc == GameState.TimeControl.INCREMENTAL)
            return base + "分+每步" + inc + "秒";
        else if (tc == GameState.TimeControl.FIXED)
            return base + "分钟包干制";
        else return "无时限";
    }


    // ===================== 暂停 =====================
    private void togglePause() {
        if (gs.gameOver) return;
        paused = !paused;
        if (pauseMenuItem != null)
            pauseMenuItem.setText(paused ? "继续(P)" : "暂停/继续(P)");
        if (paused) {
            setStatus("⏸ 游戏已暂停", true);
        } else {
            setStatus((gs.redTurn == gs.humanIsRed ? "红方（你）" : "AI") + " 走棋",
                    gs.redTurn == gs.humanIsRed);
        }
        boardPanel.repaint();
    }

    // ===================== 编辑棋局 =====================
    private void enterEditMode() {
        if (aiThinking) {
            JOptionPane.showMessageDialog(this, "AI思考中，请等待...", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // 保存当前是否暂停，进入编辑模式时自动暂停时钟
        boolean wasPaused = paused;
        paused = true;
        new EditBoardDialog(this, gs.board, gs.redTurn, boardFlipped, (newBoard, redTurn) -> {
            // 将编辑结果应用到当前游戏
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    gs.board.grid[r][c] = newBoard.grid[r][c];
            gs.redTurn = redTurn;
            gs.gameOver = false;
            gs.history.clear();
            gs.notations.clear();
            selRow = -1; selCol = -1; legalMoves = null;
            lastFR = -1; lastFC = -1; lastTR = -1; lastTC = -1;
            renderSnapshot = null;
            latestStats = null;
            updateNotation();
            bestMoveArea.setText("局面已更新");
            sourceLabel.setText("等待走棋...");
            mateLabel.setText(" ");
            mateLabel.setBackground(new Color(0xF5E6C8));
            updateAdvantage(Evaluator.evaluate(gs.board));
            setStatus((redTurn == gs.humanIsRed ? "红方（你）" : "AI") + " 走棋",
                    redTurn == gs.humanIsRed);
            paused = wasPaused;
            if (pauseMenuItem != null)
                pauseMenuItem.setText(paused ? "继续(P)" : "暂停/继续(P)");
            boardPanel.repaint();
            // 若轮到AI走，触发AI
            if (!paused && gs.redTurn != gs.humanIsRed) triggerAI();
        }, () -> {
            // 取消编辑：恢复暂停状态
            paused = wasPaused;
        }).setVisible(true);
    }

    // ===================== 引擎选择 =====================
    private void showEngineSelectDialog() {
        EngineSelectDialog dlg = new EngineSelectDialog(this, currentEngineType, currentEnginePath);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;

        EngineSelectDialog.EngineType newType = dlg.getSelectedType();
        String newPath = dlg.getEnginePath();

        // 如果切换到外部引擎
        if (newType != EngineSelectDialog.EngineType.BUILTIN) {
            if (newPath.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请先选择引擎文件！", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // 关闭旧引擎
            if (externalEngine != null) { externalEngine.stop(); externalEngine = null; }
            ExternalEngine.Protocol proto = newType == EngineSelectDialog.EngineType.EXTERNAL_UCCI
                    ? ExternalEngine.Protocol.UCCI : ExternalEngine.Protocol.UCI;
            ExternalEngine eng = new ExternalEngine(newPath, proto);
            applyStoredEngineOptions(eng);  // 预载UCI参数
            setStatus("正在连接引擎...", false);
            new Thread(() -> {
                boolean ok = false;
                try { ok = eng.start(); } catch (Exception e) { /* ignore */ }
                final boolean success = ok;
                final String engName = eng.getName();
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        externalEngine = eng;
                        currentEngineType = newType;
                        currentEnginePath = newPath;
                        if (engineMenuItem != null)
                            engineMenuItem.setText("AI引擎: " + engName + "(E)");
                        setStatus("已切换到外部引擎：" + engName, false);
                        saveEngineConfig();  // 持久化保存
                        JOptionPane.showMessageDialog(this,
                                "✅ 外部引擎连接成功：" + engName + "\n协议：" + eng.getProtocol(),
                                "引擎就绪", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        eng.stop();
                        setStatus("引擎连接失败，继续使用内置AI", false);
                        JOptionPane.showMessageDialog(this,
                                "❌ 外部引擎连接失败！\n请检查引擎路径和协议类型。\n继续使用内置AI。",
                                "连接失败", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }, "engine-connect").start();
        } else {
            // 切回内置AI
            if (externalEngine != null) { externalEngine.stop(); externalEngine = null; }
            currentEngineType = EngineSelectDialog.EngineType.BUILTIN;
            currentEnginePath = "";
            if (engineMenuItem != null) engineMenuItem.setText("AI引擎: 内置(E)");
            setStatus("已切换回内置AI引擎", false);
            saveEngineConfig();  // 持久化保存
        }
    }

    // ===================== FEN =====================
    private void saveFEN() {
        String fen = gs.board.toFEN(gs.redTurn);
        JTextArea ta = new JTextArea(fen, 3, 42);
        ta.setLineWrap(true);
        JOptionPane.showMessageDialog(this, new JScrollPane(ta),
                "保存FEN（可复制）", JOptionPane.PLAIN_MESSAGE);
    }

    private void loadFEN() {
        JTextArea ta = new JTextArea(3, 42);
        ta.setLineWrap(true);
        int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(ta),
                "粘贴FEN字符串", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        String fen = ta.getText().trim();
        if (fen.isEmpty()) return;
        boolean redT = gs.board.fromFEN(fen);
        gs.redTurn=redT; gs.gameOver=false;
        gs.history.clear(); gs.notations.clear();
        selRow=-1; selCol=-1; legalMoves=null;
        lastFR=-1; lastFC=-1; lastTR=-1; lastTC=-1;
        renderSnapshot=null;
        updateNotation();
        setStatus("已读取局面", true);
        boardPanel.repaint();
    }

    // ===================== 加载棋局（继续对弈 / 复盘分析） =====================
    private void showLoadGameDialog() {
        // 用文件选择器直接打开保存目录，棋手可选任意 .cgame 文件
        java.io.File saveDir = GameState.getSaveDir().toFile();
        if (!saveDir.exists()) {
            JOptionPane.showMessageDialog(this,
                "还没有保存过任何棋局。\n请先通过[保存棋局]菜单保存对局。",
                "加载棋局", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser(saveDir);
        fc.setDialogTitle("选择棋局文件（.cgame）");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "象棋对局文件 (*.cgame)", "cgame"));
        fc.setAcceptAllFileFilterUsed(false);

        // 构建列表预览面板
        java.util.List<GameState.GameRecord> games = GameState.loadAllGames();
        if (!games.isEmpty()) {
            // 右侧显示对局列表辅助选择
            String[] labels = new String[games.size()];
            for (int i = 0; i < games.size(); i++) {
                GameState.GameRecord r = games.get(i);
                labels[i] = r.toString();
            }
            JList<String> gameList = new JList<>(labels);
            gameList.setFont(new Font("宋体", Font.PLAIN, 12));
            gameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            gameList.setSelectedIndex(0);
            gameList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && gameList.getSelectedIndex() >= 0) {
                    fc.setSelectedFile(games.get(gameList.getSelectedIndex()).file.toFile());
                }
            });
            fc.setAccessory(new JScrollPane(gameList) {{
                setPreferredSize(new Dimension(280, 200));
                setBorder(BorderFactory.createTitledBorder("已保存对局"));
            }});
            fc.setSelectedFile(games.get(0).file.toFile());
        }

        int result = fc.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        java.io.File selectedFile = fc.getSelectedFile();
        GameState.GameRecord sel = GameState.loadGame(selectedFile.toPath());
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "棋局文件读取失败，请检查文件格式。",
                    "加载失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 询问用户：继续对弈 还是 仅复盘分析
        int mode = JOptionPane.showOptionDialog(this,
                "<html><b>" + sel.date + "</b>  " + (sel.humanIsRed ? "执红" : "执黑")
                + "  " + sel.result + "<br>共 " + sel.notations.size() + " 步<br><br>"
                + "请选择加载方式：</html>",
                "加载棋局",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"继续对弈", "查看棋谱(ReviewPanel)", "取消"},
                "继续对弈");

        if (mode == 2 || mode < 0) return;

        if (mode == 1) {
            // 用 ReviewPanel 打开复盘
            ReviewPanel.showReviewDialogWithRecord(this, sel);
            return;
        }

        // mode == 0：继续对弈
        if (!sel.inProgress) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "此对局已结束（" + sel.result + "）。\n仍要从当前局面继续？",
                    "对局已结束", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        // 停止当前AI
        if (aiThinking) {
            if (externalEngine != null) externalEngine.stopSearch();
            aiThinking = false;
        }
        if (animTimer != null) animTimer.stop();

        // 从FEN恢复局面
        if (sel.currentFen != null && !sel.currentFen.isEmpty()) {
            gs.board.fromFEN(sel.currentFen);
            gs.redTurn = sel.redTurn;
        }
        gs.humanIsRed = sel.humanIsRed;
        gs.gameOver = false;

        // 恢复时制
        if (sel.redTimeLeft >= 0)   gs.redTimeLeft   = sel.redTimeLeft;
        if (sel.blackTimeLeft >= 0) gs.blackTimeLeft = sel.blackTimeLeft;

        // 恢复棋谱
        gs.history.clear();
        gs.notations.clear();
        gs.notations.addAll(sel.notations);

        // 恢复GameId便于覆盖保存
        if (sel.file != null) {
            String fname = sel.file.getFileName().toString();
            if (fname.endsWith(".cgame"))
                gs.gameId = fname.substring(0, fname.length() - 6);
        }

        // 难度
        try { gs.difficulty = GameState.Difficulty.valueOf(sel.difficultyName); }
        catch (Exception e) { gs.difficulty = GameState.Difficulty.MEDIUM; }

        // 恢复侧边标签
        if (gs.humanIsRed) {
            redSideLabel.setText("红方（你）");
            blackSideLabel.setText("黑方（AI）");
        } else {
            redSideLabel.setText("红方（AI）");
            blackSideLabel.setText("黑方（你）");
        }
        boardFlipped = !gs.humanIsRed;
        refreshTimePanelOrder();

        selRow=-1; selCol=-1; legalMoves=null;
        lastFR=-1; lastFC=-1; lastTR=-1; lastTC=-1;
        renderSnapshot=null;
        latestStats=null;
        bestMoveArea.setText("AI尚未走棋");
        sourceLabel.setText("等待走棋...");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));

        idleMode = false;
        updateNotation();
        updateTimeLabels();
        updateAdvantage(Evaluator.evaluate(gs.board));
        String side = gs.humanIsRed ? "红方" : "黑方";
        setStatus("棋局已恢复，你执" + side + "，继续对弈", gs.humanIsRed);
        boardPanel.repaint();

        // 若轮到AI走棋，触发AI
        if (gs.redTurn != gs.humanIsRed) {
            SwingUtilities.invokeLater(this::triggerAI);
        }
    }

    // ===================== 引擎参数设置 =====================
    /**
     * 皮卡鱼/外部引擎参数设置对话框。
     * 支持 Threads/Hash/MultiPV/Move Overhead/UCI_ShowWDL 等常用UCI选项，并持久化到Preferences。
     */
    private void showEngineOptionsDialog() {
        // 只有外部引擎才能设置参数
        boolean isExternal = (externalEngine != null);

        JPanel p = new JPanel(new GridLayout(0, 2, 8, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 线程数
        JLabel lblThreads = new JLabel("线程数 (Threads)：");
        JSpinner spThreads = new JSpinner(new SpinnerNumberModel(
                Integer.parseInt(PREFS.get("opt.Threads", "1")), 1, Runtime.getRuntime().availableProcessors() * 2, 1));
        p.add(lblThreads); p.add(spThreads);

        // Hash大小（MB）
        JLabel lblHash = new JLabel("Hash大小 (MB)：");
        JSpinner spHash = new JSpinner(new SpinnerNumberModel(
                Integer.parseInt(PREFS.get("opt.Hash", "16")), 1, 4096, 16));
        p.add(lblHash); p.add(spHash);

        // MultiPV
        JLabel lblMultiPV = new JLabel("MultiPV（多条主线）：");
        JSpinner spMultiPV = new JSpinner(new SpinnerNumberModel(
                Integer.parseInt(PREFS.get("opt.MultiPV", "1")), 1, 8, 1));
        p.add(lblMultiPV); p.add(spMultiPV);

        // Move Overhead
        JLabel lblOverhead = new JLabel("移动延迟 (Move Overhead ms)：");
        JSpinner spOverhead = new JSpinner(new SpinnerNumberModel(
                Integer.parseInt(PREFS.get("opt.Move Overhead", "10")), 0, 500, 10));
        p.add(lblOverhead); p.add(spOverhead);

        // UCI_ShowWDL
        JLabel lblWDL = new JLabel("显示胜率WDL (UCI_ShowWDL)：");
        JCheckBox cbWDL = new JCheckBox("", Boolean.parseBoolean(PREFS.get("opt.UCI_ShowWDL", "false")));
        p.add(lblWDL); p.add(cbWDL);

        // 提示
        JLabel hint = new JLabel("<html><font color='#666666' size='2'>" +
                (isExternal ? "✅ 已连接外部引擎，参数设置后立即生效。" : "⚠ 当前使用内置AI，参数将在下次连接外部引擎时生效。")
                + "</font></html>");

        Object[] msg = {
            "<html><b>皮卡鱼/外部引擎参数设置</b><br><font size='2' color='#888888'>修改后点确认立即发送给引擎（建议重新开局生效）</font></html>",
            p, hint
        };

        int res = JOptionPane.showConfirmDialog(this, msg, "引擎参数设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        // 保存并应用
        String threads   = String.valueOf(spThreads.getValue());
        String hash      = String.valueOf(spHash.getValue());
        String multiPV   = String.valueOf(spMultiPV.getValue());
        String overhead  = String.valueOf(spOverhead.getValue());
        String showWDL   = cbWDL.isSelected() ? "true" : "false";

        PREFS.put("opt.Threads",        threads);
        PREFS.put("opt.Hash",           hash);
        PREFS.put("opt.MultiPV",        multiPV);
        PREFS.put("opt.Move Overhead",  overhead);
        PREFS.put("opt.UCI_ShowWDL",    showWDL);

        if (isExternal) {
            externalEngine.sendOption("Threads",       threads);
            externalEngine.sendOption("Hash",          hash);
            externalEngine.sendOption("MultiPV",       multiPV);
            externalEngine.sendOption("Move Overhead", overhead);
            externalEngine.sendOption("UCI_ShowWDL",   showWDL);
            setStatus("引擎参数已更新：" + threads + "线程，Hash=" + hash + "MB", true);
        } else {
            setStatus("参数已保存，下次连接外部引擎时生效", true);
        }
    }
    private void showDifficultyDialog() {
        String[] opts = {"简单（10秒/步）", "中等（30秒/步）", "困难（2分钟/步）"};
        int cur = gs.difficulty == GameState.Difficulty.EASY ? 0
                : gs.difficulty == GameState.Difficulty.HARD ? 2 : 1;
        int sel = JOptionPane.showOptionDialog(this, "选择AI难度：", "难度设置",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, opts, opts[cur]);
        if (sel < 0) return;
        gs.difficulty = new GameState.Difficulty[]{
                GameState.Difficulty.EASY,
                GameState.Difficulty.MEDIUM,
                GameState.Difficulty.HARD}[sel];
        setStatus("难度已设为：" + opts[sel], true);
    }

    private void showBookModeDialog() {
        OpeningBook.BookMode[] modes = OpeningBook.BookMode.values();
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        ButtonGroup bg = new ButtonGroup();
        JPanel radioPanel = new JPanel(new GridLayout(modes.length, 1, 4, 6));
        radioPanel.setOpaque(false);
        JRadioButton[] btns = new JRadioButton[modes.length];
        for (int i = 0; i < modes.length; i++) {
            btns[i] = new JRadioButton("<html><b>" + modes[i].label + "</b><br>"
                    + "<font size='2' color='#666666'>" + modes[i].desc + "</font></html>");
            btns[i].setFont(new Font("宋体", Font.PLAIN, 12));
            if (modes[i] == OpeningBook.currentMode) btns[i].setSelected(true);
            bg.add(btns[i]);
            radioPanel.add(btns[i]);
        }
        p.add(new JLabel("<html><b>开局库模式设置</b><br>"
                + "<font size='2' color='#888'>本地库包含 100+ 主流开局变例</font></html>"),
                BorderLayout.NORTH);
        p.add(radioPanel, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(this, p, "开局库设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        for (int i = 0; i < modes.length; i++) {
            if (btns[i].isSelected()) {
                OpeningBook.currentMode = modes[i];
                setStatus("开局库模式：" + modes[i].label, true);
                break;
            }
        }
    }

    // ===================== 棋谱 =====================
    private void updateNotation() {
        notationModel.setRowCount(0);
        int size = gs.notations.size();
        // 每行显示两手棋（4步）：[序1, 红1, 黑1, 序2, 红2, 黑2]
        for (int i = 0; i < size; i += 4) {
            // 第一手
            int moveNo1 = i / 2 + 1;
            String red1 = i     < size ? gs.notations.get(i)   : "";
            String blk1 = i + 1 < size ? gs.notations.get(i+1) : "";
            // 第二手（可能不存在）
            Object seq2 = "", red2 = "", blk2 = "";
            if (i + 2 < size) {
                seq2 = i / 2 + 2;
                red2 = i + 2 < size ? gs.notations.get(i+2) : "";
                blk2 = i + 3 < size ? gs.notations.get(i+3) : "";
            }
            notationModel.addRow(new Object[]{moveNo1, red1, blk1, seq2, red2, blk2});
        }
        // 自动滚动到最新一行
        if (notationModel.getRowCount() > 0) {
            int last = notationModel.getRowCount() - 1;
            notationTable.scrollRectToVisible(notationTable.getCellRect(last, 0, true));
        }
    }

    private void setStatus(String msg, boolean isRed) {
        statusLabel.setText("<html><center>" + msg + "</center></html>");
        statusLabel.setForeground(isRed ? new Color(0xB22222) : new Color(0x333333));
    }

    /** 更新"着法来源"标签 */
    private void updateSourceLabel(AIEngine.SearchStats s) {
        if (s == null) { sourceLabel.setText("等待AI走棋..."); return; }
        String html;
        if (s.source == AIEngine.MoveSource.AI_SEARCH) {
            html = String.format(
                "<html><center>🤖 <b>AI搜索</b> │ 深度<b>%d</b> │ %s节点 │ %dms</center></html>",
                s.depth, formatNodes(s.nodes), s.elapsedMs);
        } else if (s.source == AIEngine.MoveSource.CLOUD_BOOK) {
            html = String.format(
                "<html><center>☁ <b>云库</b> │ %dms</center></html>", s.elapsedMs);
        } else {
            html = "<html><center>📖 <b>本地开局库</b></center></html>";
        }
        sourceLabel.setText(html);
    }

    /** 更新"AI预测走法"文本区 —— 每行一条完整的预测后续序列 */
    private void updateBestMoveArea(AIEngine.SearchStats s) {
        if (s == null) { bestMoveArea.setText("AI尚未走棋"); return; }
        String pv = s.pvLine;
        if (pv == null || pv.trim().isEmpty()) {
            if (s.source != AIEngine.MoveSource.AI_SEARCH) {
                bestMoveArea.setText("（书库走法，无预测）");
            } else if (s.depth > 0) {
                bestMoveArea.setText("计算中...");
            } else {
                bestMoveArea.setText("AI尚未走棋");
            }
            return;
        }
        // pvLine 每行格式："▶ AI: 炮二平五" 或 "△ 对手: 马8进7"
        // 显示格式：深度N  炮二平五 → 马8进7 → 车一进一（去掉红/黑前缀，因规则已知奇红偶黑）
        String[] steps = pv.split("\n");

        // 主线（第depth层）：一行
        StringBuilder mainLine = new StringBuilder();
        mainLine.append(String.format("▶ 深度%-2d  ", s.depth));
        for (int i = 0; i < steps.length; i++) {
            String line = steps[i].trim();
            String notation;
            if (line.startsWith("▶ AI: ")) {
                notation = line.substring(6);
            } else if (line.startsWith("△ 对手: ")) {
                notation = line.substring(6);
            } else {
                notation = line;
            }
            if (i > 0) mainLine.append(" → ");
            mainLine.append(notation);
        }

        // 每个浅层子序列展示一行（去掉最后1步、2步...）
        StringBuilder sb = new StringBuilder();
        sb.append(mainLine).append("\n");
        // 展示去掉末尾 1步、2步 的子序列（提供对比参考）
        for (int cut = 1; cut < Math.min(steps.length, 4); cut++) {
            int showLen = steps.length - cut;
            if (showLen < 1) break;
            sb.append(String.format("  └ 前%-2d步  ", showLen));
            for (int i = 0; i < showLen; i++) {
                String line = steps[i].trim();
                String notation;
                if (line.startsWith("▶ AI: ")) {
                    notation = line.substring(6);
                } else if (line.startsWith("△ 对手: ")) {
                    notation = line.substring(6);
                } else {
                    notation = line;
                }
                if (i > 0) sb.append(" → ");
                sb.append(notation);
            }
            sb.append("\n");
        }

        bestMoveArea.setText(sb.toString().stripTrailing());
        bestMoveArea.setCaretPosition(0);
    }

    /** 更新绝杀提示标签（双方都能绝杀对方时均提示） */
    private void updateMateLabel(AIEngine.SearchStats s) {
        if (s == null || s.mateIn == 0) {
            mateLabel.setText(" ");
            mateLabel.setBackground(new Color(0xF5E6C8));
            return;
        }
        if (s.mateIn > 0) {
            // AI 将死对手：根据 AI 颜色确定文字
            String attacker = s.aiIsRed ? "红" : "黑";
            String defender = s.aiIsRed ? "黑" : "红";
            mateLabel.setText("⚑ " + attacker + "方 " + s.mateIn + " 步绝杀" + defender + "方！");
            mateLabel.setForeground(Color.WHITE);
            mateLabel.setBackground(new Color(0xCC2222)); // 红底白字（红杀黑）
        } else {
            // 对手将死 AI
            String attacker = s.aiIsRed ? "黑" : "红";
            String defender = s.aiIsRed ? "红" : "黑";
            mateLabel.setText("⚠ " + attacker + "方 " + (-s.mateIn) + " 步绝杀" + defender + "方！");
            mateLabel.setForeground(Color.WHITE);
            mateLabel.setBackground(new Color(0x226622)); // 绿底白字（黑杀红）
        }
    }

    /** 更新优势评分条和标签（score 正=红优，负=黑优；±99999表示终局）*/
    private void updateAdvantage(int score) {
        lastBoardScore = score;
        // 终局极值处理
        if (score >= 99999) {
            advantageBar.setValue(200);
            advantageLabel.setText("红方胜");
            advantageLabel.setForeground(new Color(0xAA1111));
            return;
        }
        if (score <= -99999) {
            advantageBar.setValue(0);
            advantageLabel.setText("黑方胜");
            advantageLabel.setForeground(new Color(0x111111));
            return;
        }
        // 将评分映射到进度条 [0,200]，100=均势
        // 每1000分大约相当于一车的优势，做非线性映射让显示更合理
        // 映射：score=0 → 100，score=1000 → ~165，score=-1000 → ~35
        double norm = score / 600.0; // 缩放
        // sigmoid-like 映射
        int barVal = (int)(100 + 100 * norm / (1 + Math.abs(norm)));
        barVal = Math.max(0, Math.min(200, barVal));
        advantageBar.setValue(barVal);

        if (Math.abs(score) < 50) {
            advantageLabel.setText("均势");
            advantageLabel.setForeground(new Color(0x555555));
        } else if (score > 0) {
            advantageLabel.setText("红方优 " + score + " 分");
            advantageLabel.setForeground(new Color(0xAA1111));
        } else {
            advantageLabel.setText("黑方优 " + (-score) + " 分");
            advantageLabel.setForeground(new Color(0x111111));
        }
    }

    // ===================== 绘制 =====================
    private void drawBoard(Graphics2D g) {
        final int C = dynCell, M = dynMargin, OX = dynOffX, OY = dynOffY;
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(Math.max(1f, C / 44f)));
        for (int r=0;r<10;r++)
            g.drawLine(OX+M, OY+M+r*C, OX+M+8*C, OY+M+r*C);
        for (int c=0;c<9;c++) {
            if (c==0||c==8) g.drawLine(OX+M+c*C, OY+M, OX+M+c*C, OY+M+9*C);
            else {
                g.drawLine(OX+M+c*C, OY+M,         OX+M+c*C, OY+M+4*C);
                g.drawLine(OX+M+c*C, OY+M+5*C,     OX+M+c*C, OY+M+9*C);
            }
        }
        g.drawLine(OX+M+3*C, OY+M,         OX+M+5*C, OY+M+2*C);
        g.drawLine(OX+M+5*C, OY+M,         OX+M+3*C, OY+M+2*C);
        g.drawLine(OX+M+3*C, OY+M+7*C,     OX+M+5*C, OY+M+9*C);
        g.drawLine(OX+M+5*C, OY+M+7*C,     OX+M+3*C, OY+M+9*C);
        int fontSize = Math.max(11, (int)(C * 20.0 / CELL));
        g.setFont(new Font("宋体", Font.BOLD, fontSize));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", OX+M+C,   OY+M+4*C+(int)(C*0.45));
        g.drawString("汉  界", OX+M+5*C, OY+M+4*C+(int)(C*0.45));
        // 标记点
        for (int[] pos : new int[][]{{2,1},{2,7},{7,1},{7,7},{3,0},{3,2},{3,4},{3,6},{3,8},{6,0},{6,2},{6,4},{6,6},{6,8}})
            drawMark(g, pos[0], pos[1]);
    }

    private void drawMark(Graphics2D g, int row, int col) {
        final int C = dynCell, M = dynMargin, OX = dynOffX, OY = dynOffY;
        int cx = OX+M+col*C, cy = OY+M+row*C, s = Math.max(3, C/12);
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(Math.max(1f, C/55f)));
        if(col>0){g.drawLine(cx-s,cy-s,cx-s,cy-2);g.drawLine(cx-s,cy-s,cx-2,cy-s);}
        if(col<8){g.drawLine(cx+s,cy-s,cx+s,cy-2);g.drawLine(cx+s,cy-s,cx+2,cy-s);}
        if(col>0){g.drawLine(cx-s,cy+s,cx-s,cy+2);g.drawLine(cx-s,cy+s,cx-2,cy+s);}
        if(col<8){g.drawLine(cx+s,cy+s,cx+s,cy+2);g.drawLine(cx+s,cy+s,cx+2,cy+s);}
        g.setStroke(new BasicStroke(1f));
    }

    private void drawLastMove(Graphics2D g) {
        if (lastFR < 0) return;
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        int sfr = toScreenRow(lastFR), sfc = toScreenCol(lastFC);
        int str = toScreenRow(lastTR), stc = toScreenCol(lastTC);

        // 起点：黄绿色实心圆背景
        g.setColor(new Color(180, 230, 60, 160));
        g.fillOval(OX+M+sfc*C-R, OY+M+sfr*C-R, R*2, R*2);
        // 终点：更亮的高亮，加粗边框
        g.setColor(new Color(120, 220, 30, 200));
        g.fillOval(OX+M+stc*C-R, OY+M+str*C-R, R*2, R*2);
        g.setColor(new Color(60, 200, 0, 230));
        g.setStroke(new BasicStroke(Math.max(2f, R/8f)));
        g.drawOval(OX+M+stc*C-R, OY+M+str*C-R, R*2, R*2);
        g.setStroke(new BasicStroke(1f));
    }

    private void drawHighlight(Graphics2D g) {
        if (aiThinking) return;
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        if (selRow != -1) {
            int scx = OX+M + toScreenCol(selCol)*C;
            int scy = OY+M + toScreenRow(selRow)*C;
            g.setColor(new Color(255, 240, 0, 180));
            g.fillOval(scx-R, scy-R, R*2, R*2);
            g.setColor(new Color(255, 200, 0, 230));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(scx-R-2, scy-R-2, R*2+4, R*2+4);
            g.setStroke(new BasicStroke(1f));
        }
        if (legalMoves != null) {
            for (int[] m : legalMoves) {
                int cx = OX+M + toScreenCol(m[1])*C;
                int cy = OY+M + toScreenRow(m[0])*C;
                if (gs.board.getPiece(m[0], m[1]) != null) {
                    g.setColor(new Color(220, 0, 0, 80));
                    g.fillOval(cx-R, cy-R, R*2, R*2);
                    g.setColor(new Color(220, 0, 0, 220));
                    g.setStroke(new BasicStroke(3.5f));
                    g.drawOval(cx-R, cy-R, R*2, R*2);
                    g.setColor(new Color(255, 80, 80, 180));
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawOval(cx-R+5, cy-R+5, (R-5)*2, (R-5)*2);
                    g.setStroke(new BasicStroke(1f));
                } else {
                    int dot = Math.max(6, R/3);
                    int big = Math.max(10, (int)(R*0.8));
                    g.setColor(new Color(0, 180, 0, 210));
                    g.fillOval(cx-big, cy-big, big*2, big*2);
                    g.setColor(new Color(255, 255, 255, 200));
                    g.fillOval(cx-dot/2, cy-dot/2, dot, dot);
                }
            }
        }
    }

    private void drawPieces(Graphics2D g, Piece[][] grid) {
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        int fontSize = Math.max(11, (int)(R * 19.0 / PIECE_R));
        for (int r=0;r<10;r++) for (int c=0;c<9;c++) {
            Piece p = grid[r][c];
            if (p == null) continue;
            int cx = OX+M + toScreenCol(c)*C;
            int cy = OY+M + toScreenRow(r)*C;
            Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
            Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
            Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);

            g.setColor(new Color(0,0,0,40));
            g.fillOval(cx-R+2, cy-R+3, R*2, R*2);
            g.setColor(bg);
            g.fillOval(cx-R, cy-R, R*2, R*2);
            g.setColor(border);
            g.setStroke(new BasicStroke(Math.max(1.5f, R/11f)));
            g.drawOval(cx-R, cy-R, R*2, R*2);
            g.setStroke(new BasicStroke(1f));
            int inner = Math.max(2, R/9);
            g.drawOval(cx-R+inner, cy-R+inner, (R-inner)*2, (R-inner)*2);
            g.setColor(text);
            g.setFont(new Font("宋体", Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            String txt = p.getDisplay();
            g.drawString(txt, cx-fm.stringWidth(txt)/2, cy+fm.getAscent()/2-1);
        }
    }

    /** AI思考中：棋盘上显示半透明遮罩 + 进度文字 */
    private void drawThinkingOverlay(Graphics2D g) {
        final int C = dynCell, M = dynMargin, OX = dynOffX, OY = dynOffY;
        g.setColor(new Color(0, 0, 0, 55));
        g.fillRoundRect(OX+M, OY+M, C*8, C*9, 12, 12);

        int cx = OX+M + C * 4;
        int baseY = OY+M + C * 4 - 20;

        // 跳动圆点动画
        long t = System.currentTimeMillis() / 350 % 4;
        StringBuilder dots = new StringBuilder();
        for (int i=0; i<t; i++) dots.append("●");
        for (int i=(int)t; i<3; i++) dots.append("○");

        // 主标题
        g.setFont(new Font("宋体", Font.BOLD, 18));
        g.setColor(new Color(255, 255, 200, 230));
        String title = "AI 思考中 " + dots;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, cx - fm.stringWidth(title)/2, baseY);

        // 实时搜索信息
        AIEngine.SearchStats s = latestStats;
        g.setFont(new Font("宋体", Font.PLAIN, 13));
        FontMetrics fm2 = g.getFontMetrics();
        int lineH = fm2.getHeight() + 3;
        int y = baseY + 24;

        if (s != null && s.source == AIEngine.MoveSource.AI_SEARCH && s.depth > 0) {
            // 来源行
            g.setColor(new Color(210, 240, 255, 210));
            String srcLine = "AI搜索  深度:" + s.depth + "  " + s.elapsedMs + "ms";
            g.drawString(srcLine, cx - fm2.stringWidth(srcLine)/2, y);
            y += lineH;

            // 绝杀提示
            if (s.mateIn > 0) {
                g.setFont(new Font("宋体", Font.BOLD, 14));
                g.setColor(new Color(255, 100, 100, 240));
                String att = s.aiIsRed ? "红" : "黑";
                String def = s.aiIsRed ? "黑" : "红";
                String mLine = "⚑ " + att + "方 " + s.mateIn + " 步绝杀" + def + "方！";
                g.drawString(mLine, cx - g.getFontMetrics().stringWidth(mLine)/2, y);
                y += lineH + 2;
                g.setFont(new Font("宋体", Font.PLAIN, 13));
            } else if (s.mateIn < 0) {
                g.setFont(new Font("宋体", Font.BOLD, 14));
                g.setColor(new Color(100, 255, 120, 240));
                String att = s.aiIsRed ? "黑" : "红";
                String def = s.aiIsRed ? "红" : "黑";
                String mLine = "⚠ " + att + "方 " + (-s.mateIn) + " 步绝杀" + def + "方！";
                g.drawString(mLine, cx - g.getFontMetrics().stringWidth(mLine)/2, y);
                y += lineH + 2;
                g.setFont(new Font("宋体", Font.PLAIN, 13));
            }

            // PV 第一步
            if (s.pvLine != null && !s.pvLine.isEmpty()) {
                g.setColor(new Color(255, 240, 180, 220));
                String[] pvLines = s.pvLine.split("\n");
                String first = pvLines[0].replace("▶ AI: ", "").replace("△ 对手: ", "");
                String pvShow = "最优: " + first;
                g.drawString(pvShow, cx - fm2.stringWidth(pvShow)/2, y);
            }
        } else if (s != null && s.source == AIEngine.MoveSource.CLOUD_BOOK) {
            g.setColor(new Color(210, 240, 255, 210));
            String line = "☁ 云库走法  " + s.elapsedMs + "ms";
            g.drawString(line, cx - fm2.stringWidth(line)/2, y);
        } else if (s != null && s.source == AIEngine.MoveSource.LOCAL_BOOK) {
            g.setColor(new Color(210, 240, 255, 210));
            String line = "📖 本地开局库";
            g.drawString(line, cx - fm2.stringWidth(line)/2, y);
        } else {
            g.setColor(new Color(210, 240, 255, 180));
            String sub = "查询云库/开局库...";
            g.drawString(sub, cx - fm2.stringWidth(sub)/2, y);
        }
    }
}
