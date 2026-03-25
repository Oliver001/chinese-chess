package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

public class ChessPanel extends JPanel {

    // ---- 尺寸（默认/初始值，用于 preferredSize 和首次布局）----
    private static final int CELL    = 66;
    private static final int MARGIN  = 46;
    private static final int PIECE_R = 26;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;
    private static final int SIDE_W  = 188;

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
    private JTextArea notationArea;
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

    // ===================== 构造 =====================
    /** 无参构造（兼容旧代码，使用默认设置） */
    public ChessPanel() {
        this(true, GameState.Difficulty.MEDIUM,
             GameState.TimeControl.INCREMENTAL, 20, 5);
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

        // 监听整体面板尺寸变化，动态调整右侧栏宽度（约占总宽 24%，最小 188px）
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                int totalW = getWidth();
                if (totalW <= 0) return;
                int newSideW = Math.max(188, (int)(totalW * 0.24));
                Dimension cur = sidePanel.getPreferredSize();
                if (cur.width != newSideW) {
                    sidePanel.setPreferredSize(new Dimension(newSideW, cur.height));
                    revalidate();
                }
            }
        });

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
        side.setMinimumSize(new Dimension(188, 500));
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

        // ── 中部：棋谱 ──
        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
        centerPanel.setOpaque(false);

        notationArea = new JTextArea();
        notationArea.setFont(new Font("宋体", Font.PLAIN, 12));
        notationArea.setEditable(false);
        notationArea.setBackground(new Color(0xFFFAF0));
        JScrollPane scroll = new JScrollPane(notationArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xC8A060)), "棋谱",
                TitledBorder.CENTER, TitledBorder.TOP,
                new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));
        centerPanel.add(scroll, BorderLayout.CENTER);

        // ── 底部信息区：着法来源 + 绝杀提示 + PV走法 ──
        JPanel pvPanel = new JPanel(new BorderLayout(0, 3));
        pvPanel.setOpaque(false);

        // 着法来源行
        sourceLabel = new JLabel("<html><center>等待AI走棋...</center></html>", SwingConstants.CENTER);
        sourceLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        sourceLabel.setForeground(new Color(0x555555));
        sourceLabel.setBackground(new Color(0xEEE0B0));
        sourceLabel.setOpaque(true);
        sourceLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        pvPanel.add(sourceLabel, BorderLayout.NORTH);

        // 绝杀提示行（放在 PV 上方，更醒目）
        mateLabel = new JLabel(" ", SwingConstants.CENTER);
        mateLabel.setFont(new Font("宋体", Font.BOLD, 13));
        mateLabel.setForeground(new Color(0xCC0000));
        mateLabel.setOpaque(true);
        mateLabel.setBackground(new Color(0xF5E6C8));
        mateLabel.setBorder(BorderFactory.createLineBorder(new Color(0xC8A060)));

        // 最优着法（纯PV走法序列）
        bestMoveArea = new JTextArea(5, 1);
        bestMoveArea.setFont(new Font("宋体", Font.PLAIN, 12));
        bestMoveArea.setEditable(false);
        bestMoveArea.setBackground(new Color(0xFFF8E1));
        bestMoveArea.setForeground(new Color(0x5C3317));
        bestMoveArea.setLineWrap(true);
        bestMoveArea.setWrapStyleWord(false);
        bestMoveArea.setText("AI尚未走棋");
        JScrollPane pvScroll = new JScrollPane(bestMoveArea);
        pvScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xC8A060)), "AI预测走法",
                TitledBorder.CENTER, TitledBorder.TOP,
                new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));

        JPanel matePvPanel = new JPanel(new BorderLayout(0, 2));
        matePvPanel.setOpaque(false);
        matePvPanel.add(mateLabel, BorderLayout.NORTH);
        matePvPanel.add(pvScroll, BorderLayout.CENTER);
        pvPanel.add(matePvPanel, BorderLayout.CENTER);

        centerPanel.add(pvPanel, BorderLayout.SOUTH);
        side.add(centerPanel, BorderLayout.CENTER);

        // ── 底部：状态行 + 按钮 ──
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setOpaque(false);

        statusLabel = new JLabel("红方先走", SwingConstants.CENTER);
        statusLabel.setFont(new Font("宋体", Font.BOLD, 13));
        statusLabel.setForeground(new Color(0xB22222));
        bottom.add(statusLabel, BorderLayout.NORTH);

        JPanel btns = new JPanel(new GridLayout(4, 2, 4, 4));
        btns.setOpaque(false);
        addBtn(btns, "悔棋",     e -> doUndo());
        addBtn(btns, "新游戏",   e -> showNewGameDialog());
        addBtn(btns, "保存局面", e -> saveFEN());
        addBtn(btns, "读取局面", e -> loadFEN());
        addBtn(btns, "难度",     e -> showDifficultyDialog());
        addBtn(btns, "历史棋谱", e -> ReviewPanel.showReviewDialog(this));
        addBtn(btns, "翻转棋盘", e -> {
            boardFlipped = !boardFlipped;
            refreshTimePanelOrder();
            boardPanel.repaint();
        });
        JButton sndBtn = new JButton(sound.isEnabled() ? "🔊 音效" : "🔇 静音");
        sndBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        sndBtn.addActionListener(e -> {
            sound.setEnabled(!sound.isEnabled());
            sndBtn.setText(sound.isEnabled() ? "🔊 音效" : "🔇 静音");
        });
        btns.add(sndBtn);
        bottom.add(btns, BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
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
            if (!gs.gameOver) {
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
                int boardScoreAfter = Evaluator.evaluate(gs.board);
                updateAdvantage(boardScoreAfter);
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
        setStatus("AI 思考中... [" + diffLabel + "]", false);
        sourceLabel.setText("<html><center>🔍 查询云库/开局库...</center></html>");
        mateLabel.setText(" ");
        mateLabel.setBackground(new Color(0xF5E6C8));
        // 优势条显示当前局面（人刚走完后的评分）
        updateAdvantage(Evaluator.evaluate(gs.board));

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

    // ===================== 难度 =====================
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

    // ===================== 棋谱 =====================
    private void updateNotation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gs.notations.size(); i++) {
            if (i % 2 == 0) sb.append(String.format("%2d. ", i/2+1));
            sb.append(gs.notations.get(i));
            if (i % 2 == 1) sb.append('\n'); else sb.append("  ");
        }
        notationArea.setText(sb.toString());
        notationArea.setCaretPosition(notationArea.getDocument().getLength());
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

    /** 更新"AI预测走法"文本区 —— 格式：每步单独一行，红用"红"黑用"黑"前缀 */
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
        // 转成用户要求格式：
        //   红 炮二平五  黑 马8进7->
        //   红 车一进一->黑 炮2退1->
        //   ...（每两步拼一行，AI步用颜色前缀，用 "->" 隔开）
        String[] lines = pv.split("\n");
        StringBuilder sb = new StringBuilder();
        // 判断AI是红还是黑
        boolean aiIsRed = s.aiIsRed;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String notation;
            boolean isRed;
            if (line.startsWith("▶ AI: ")) {
                notation = line.substring(6);
                isRed = aiIsRed;
            } else if (line.startsWith("△ 对手: ")) {
                notation = line.substring(6);
                isRed = !aiIsRed;
            } else {
                notation = line;
                isRed = (i % 2 == 0); // fallback
            }
            String prefix = isRed ? "红 " : "黑 ";
            sb.append(prefix).append(notation);
            // 每步之间用 "->" 连接，最后一步不加
            if (i < lines.length - 1) {
                sb.append("->");
                // 每两步换行（红+黑算一轮）
                if (i % 2 == 1) sb.append("\n");
            }
        }
        bestMoveArea.setText(sb.toString());
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
