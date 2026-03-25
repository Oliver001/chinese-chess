package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * 推演窗口 & 局面分析
 *
 * 功能：
 * 1. 棋手可在此独立窗口走动棋子，推演多种变例，不影响主对局
 * 2. 实时显示局面评分（红优/均势/黑优）
 * 3. AI分析：显示当前局面前N个候选走法（MultiPV），每条含评分+PV主线
 * 4. 悔棋（退回上一步）、重置（恢复初始传入局面）
 * 5. 走棋方切换：传入时固定，悔棋/走棋后自动切换
 */
public class ExploreDialog extends JDialog {

    // ---- 尺寸 ----
    private static final int CELL    = 62;
    private static final int MARGIN  = 42;
    private static final int PIECE_R = 24;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;

    // ---- 动态尺寸 ----
    private int dynCell   = CELL;
    private int dynMargin = MARGIN;
    private int dynPieceR = PIECE_R;
    private int dynOffX   = 0;
    private int dynOffY   = 0;

    // ---- 棋盘状态 ----
    /** 初始局面快照（重置用） */
    private final Piece[][] initGrid;
    /** 初始走棋方 */
    private final boolean initRedTurn;

    /** 当前局面 */
    private final Board board = new Board();
    /** 当前走棋方 */
    private boolean redTurn;

    /** 悔棋栈 */
    private final Deque<int[]> history = new ArrayDeque<>();
    // int[]{fr, fc, tr, tc, isRed(0/1)} + 快照在单独列表

    private static class StepRecord {
        final int fr, fc, tr, tc;
        final boolean wasRed;
        final Piece[][] snapshot;
        StepRecord(int fr, int fc, int tr, int tc, boolean wasRed, Piece[][] snap) {
            this.fr=fr; this.fc=fc; this.tr=tr; this.tc=tc; this.wasRed=wasRed; this.snapshot=snap;
        }
    }
    private final Deque<StepRecord> stepStack = new ArrayDeque<>();

    // ---- 选中 & 合法走法 ----
    private int selRow = -1, selCol = -1;
    private List<int[]> legalMoves = null;
    private int lastFR=-1, lastFC=-1, lastTR=-1, lastTC=-1;

    // ---- UI ----
    private JPanel boardPanel;
    private JLabel turnLabel;    // 当前走棋方
    private JLabel scoreLabel;   // 实时评分
    private JProgressBar scoreBar;
    private javax.swing.JTable notationTable;                          // 推演棋谱（6列表格）
    private javax.swing.table.DefaultTableModel notationModel;         // 棋谱数据模型
    private JTextArea analysisArea;  // AI分析结果
    private JButton undoBtn, resetBtn, analyzeBtn, stopAnalyzeBtn;
    private JLabel statusLabel;

    // ---- AI分析 ----
    private final AIEngine analyzeAI = new AIEngine();
    private volatile boolean analyzing = false;
    private SwingWorker<Void, String> analyzeWorker;

    // ---- 候选数量 ----
    private static final int MULTI_PV = 5;  // 显示前5个候选

    // ========================================================================

    public ExploreDialog(Component parent, Piece[][] currentGrid, boolean currentRedTurn) {
        super(SwingUtilities.getWindowAncestor(parent),
              "推演 / 局面分析", Dialog.ModalityType.MODELESS);
        this.initGrid = copyGrid(currentGrid);
        this.initRedTurn = currentRedTurn;

        // 复制初始局面到board
        restoreGrid(currentGrid);
        this.redTurn = currentRedTurn;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setResizable(true);
        buildUI();
        pack();
        setMinimumSize(new Dimension(900, 680));
        setLocationRelativeTo(parent);

        updateScoreDisplay();
        updateTurnLabel();
    }

    // ========================================================================
    // UI 构建
    // ========================================================================
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(6, 0));
        root.setBackground(new Color(0xEEE0B0));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        // ── 左：棋盘 ──
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int pw = getWidth(), ph = getHeight();
                int cellW = (pw - MARGIN * 2) / 8;
                int cellH = (ph - MARGIN * 2) / 9;
                int cell = Math.max(28, Math.min(cellW, cellH));
                int margin = Math.max(18, (int)(cell * MARGIN / (double)CELL));
                int boardPixW = margin * 2 + cell * 8;
                int boardPixH = margin * 2 + cell * 9;
                int offX = Math.max(0, (pw - boardPixW) / 2);
                int offY = Math.max(0, (ph - boardPixH) / 2);
                int pieceR = Math.max(11, (int)(cell * PIECE_R / (double)CELL));
                dynCell = cell; dynMargin = margin; dynPieceR = pieceR;
                dynOffX = offX; dynOffY = offY;
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawLastMove(g2);
                drawHighlight(g2);
                drawPieces(g2);
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));
        boardPanel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { handleClick(e.getX(), e.getY()); }
        });
        root.add(boardPanel, BorderLayout.CENTER);

        // ── 右：控制面板 ──
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setPreferredSize(new Dimension(320, BOARD_H));
        right.setOpaque(false);
        root.add(right, BorderLayout.EAST);

        // 顶部：走棋方 + 评分
        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.setOpaque(false);
        topPanel.setBorder(new EmptyBorder(0, 0, 4, 0));

        // 标题
        JLabel title = new JLabel("🔍 推演 & 局面分析", SwingConstants.CENTER);
        title.setFont(new Font("宋体", Font.BOLD, 15));
        title.setForeground(new Color(0x5C3317));
        title.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        title.setOpaque(true);
        title.setBackground(new Color(0xF0D080));
        topPanel.add(title, BorderLayout.NORTH);

        // 走棋方 + 评分行
        JPanel infoRow = new JPanel(new GridLayout(1, 2, 4, 0));
        infoRow.setOpaque(false);
        turnLabel = new JLabel("红方走棋", SwingConstants.CENTER);
        turnLabel.setFont(new Font("宋体", Font.BOLD, 13));
        turnLabel.setForeground(new Color(0xB22222));
        turnLabel.setOpaque(true);
        turnLabel.setBackground(new Color(0xFFF8E1));
        turnLabel.setBorder(BorderFactory.createLineBorder(new Color(0xC8A060)));
        scoreLabel = new JLabel("均势", SwingConstants.CENTER);
        scoreLabel.setFont(new Font("宋体", Font.BOLD, 13));
        scoreLabel.setForeground(new Color(0x555555));
        scoreLabel.setOpaque(true);
        scoreLabel.setBackground(new Color(0xFFF8E1));
        scoreLabel.setBorder(BorderFactory.createLineBorder(new Color(0xC8A060)));
        infoRow.add(turnLabel);
        infoRow.add(scoreLabel);
        topPanel.add(infoRow, BorderLayout.CENTER);

        // 优势条
        scoreBar = new JProgressBar(0, 200);
        scoreBar.setValue(100);
        scoreBar.setStringPainted(false);
        scoreBar.setBackground(new Color(0x222222));
        scoreBar.setForeground(new Color(0xCC2222));
        scoreBar.setPreferredSize(new Dimension(300, 12));
        scoreBar.setBorder(BorderFactory.createLineBorder(new Color(0xAA8844)));
        topPanel.add(scoreBar, BorderLayout.SOUTH);
        right.add(topPanel, BorderLayout.NORTH);

        // 中部：推演棋谱 + AI分析（各占约一半，可拖动）
        // 推演棋谱：6列JTable，与对弈界面一致（序,红,黑,序,红,黑）
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
        int[] colWidths = {28, 62, 62, 28, 62, 62};
        for (int ci = 0; ci < colWidths.length; ci++) {
            notationTable.getColumnModel().getColumn(ci).setPreferredWidth(colWidths[ci]);
            if (ci == 0 || ci == 3)
                notationTable.getColumnModel().getColumn(ci).setMaxWidth(36);
        }
        // 居中对齐（所有列）
        javax.swing.table.DefaultTableCellRenderer center = new javax.swing.table.DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        for (int ci = 0; ci < 6; ci++)
            notationTable.getColumnModel().getColumn(ci).setCellRenderer(center);

        JScrollPane notaScroll = new JScrollPane(notationTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        notaScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "推演棋谱",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));

        // AI分析结果
        analysisArea = new JTextArea();
        analysisArea.setFont(new Font("宋体", Font.PLAIN, 12));
        analysisArea.setEditable(false);
        analysisArea.setBackground(new Color(0xFFF8E1));
        analysisArea.setForeground(new Color(0x3C3C00));
        analysisArea.setLineWrap(true);
        analysisArea.setWrapStyleWord(false);
        analysisArea.setText("点击「AI分析」获取当前局面候选走法");
        JScrollPane anaScroll = new JScrollPane(analysisArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        anaScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "AI候选走法",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, notaScroll, anaScroll);
        centerSplit.setResizeWeight(0.35);
        centerSplit.setDividerSize(5);
        centerSplit.setContinuousLayout(true);
        centerSplit.setOpaque(false);
        centerSplit.setBorder(null);
        right.add(centerSplit, BorderLayout.CENTER);

        // 底部：按钮 + 状态
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));
        bottomPanel.setOpaque(false);

        // 操作按钮行
        JPanel btnRow1 = new JPanel(new GridLayout(1, 2, 6, 0));
        btnRow1.setOpaque(false);
        undoBtn = makeBtn("◀ 悔棋", new Color(0x5566AA), e -> doUndo());
        resetBtn = makeBtn("⟳ 重置局面", new Color(0x885522), e -> doReset());
        btnRow1.add(undoBtn);
        btnRow1.add(resetBtn);

        JPanel btnRow2 = new JPanel(new GridLayout(1, 2, 6, 0));
        btnRow2.setOpaque(false);
        analyzeBtn = makeBtn("🤖 AI分析", new Color(0x226622), e -> startAnalyze());
        stopAnalyzeBtn = makeBtn("⏹ 停止分析", new Color(0xAA2222), e -> stopAnalyze());
        stopAnalyzeBtn.setEnabled(false);
        btnRow2.add(analyzeBtn);
        btnRow2.add(stopAnalyzeBtn);

        JPanel allBtns = new JPanel(new GridLayout(2, 1, 0, 5));
        allBtns.setOpaque(false);
        allBtns.add(btnRow1);
        allBtns.add(btnRow2);
        bottomPanel.add(allBtns, BorderLayout.NORTH);

        // 状态行
        statusLabel = new JLabel("点击棋子选择走法", SwingConstants.CENTER);
        statusLabel.setFont(new Font("宋体", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(0x555555));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(0xEEE0B0));
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        right.add(bottomPanel, BorderLayout.SOUTH);
    }

    private JButton makeBtn(String text, Color fg, ActionListener al) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("宋体", Font.BOLD, 12));
        btn.setForeground(fg);
        btn.addActionListener(al);
        return btn;
    }

    // ========================================================================
    // 棋盘交互
    // ========================================================================
    private void handleClick(int px, int py) {
        if (analyzing) return;
        int screenCol = Math.round((float)(px - dynOffX - dynMargin) / dynCell);
        int screenRow = Math.round((float)(py - dynOffY - dynMargin) / dynCell);
        if (screenRow < 0 || screenRow > 9 || screenCol < 0 || screenCol > 8) {
            selRow = -1; selCol = -1; legalMoves = null;
            boardPanel.repaint(); return;
        }
        int row = screenRow, col = screenCol;

        if (selRow != -1) {
            // 尝试走法
            if (isLegal(row, col)) {
                doMove(selRow, selCol, row, col);
                selRow = -1; selCol = -1; legalMoves = null;
                boardPanel.repaint();
                return;
            }
        }
        // 选子
        Piece p = board.grid[row][col];
        if (p != null && p.isRed == redTurn) {
            selRow = row; selCol = col;
            legalMoves = board.getLegalMoves(row, col);
        } else {
            selRow = -1; selCol = -1; legalMoves = null;
        }
        boardPanel.repaint();
    }

    private boolean isLegal(int row, int col) {
        if (legalMoves == null) return false;
        for (int[] m : legalMoves) {
            if (m[0] == row && m[1] == col) return true;
        }
        return false;
    }

    private void doMove(int fr, int fc, int tr, int tc) {
        Piece[][] snap = copyGrid(board.grid);
        StepRecord rec = new StepRecord(fr, fc, tr, tc, redTurn, snap);
        stepStack.push(rec);
        board.move(fr, fc, tr, tc);
        lastFR = fr; lastFC = fc; lastTR = tr; lastTC = tc;
        redTurn = !redTurn;
        updateScoreDisplay();
        updateTurnLabel();
        updateNotationArea();
        undoBtn.setEnabled(true);
        // 简单检测将死/困毙
        if (!board.hasLegalMoves(redTurn)) {
            String who = redTurn ? "红方" : "黑方";
            statusLabel.setText(who + " 无子可走（将死/困毙）");
        } else {
            statusLabel.setText(redTurn ? "红方走棋" : "黑方走棋");
        }
    }

    private void doUndo() {
        if (stepStack.isEmpty()) return;
        StepRecord rec = stepStack.pop();
        restoreGrid(rec.snapshot);
        redTurn = rec.wasRed;
        lastFR = rec.fr; lastFC = rec.fc; lastTR = rec.tr; lastTC = rec.tc;
        selRow = -1; selCol = -1; legalMoves = null;
        undoBtn.setEnabled(!stepStack.isEmpty());
        updateScoreDisplay();
        updateTurnLabel();
        updateNotationArea();
        statusLabel.setText("已悔棋");
        boardPanel.repaint();
    }

    private void doReset() {
        stepStack.clear();
        restoreGrid(initGrid);
        redTurn = initRedTurn;
        lastFR = -1; lastFC = -1; lastTR = -1; lastTC = -1;
        selRow = -1; selCol = -1; legalMoves = null;
        undoBtn.setEnabled(false);
        updateScoreDisplay();
        updateTurnLabel();
        notationModel.setRowCount(0);
        statusLabel.setText("已重置到初始传入局面");
        boardPanel.repaint();
    }

    // ========================================================================
    // AI 分析
    // ========================================================================
    private void startAnalyze() {
        if (analyzing) return;
        analyzing = true;
        analyzeBtn.setEnabled(false);
        stopAnalyzeBtn.setEnabled(true);
        analysisArea.setText("AI正在分析，请稍候...\n（分析时仍可查看棋盘，但不能走棋）");
        statusLabel.setText("AI分析中...");

        final Board snapBoard = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                snapBoard.grid[r][c] = board.grid[r][c] != null ? board.grid[r][c].copy() : null;
        final boolean snapRedTurn = redTurn;
        final int scoreNow = chess.Evaluator.evaluate(snapBoard);

        analyzeWorker = new SwingWorker<Void, String>() {
            final List<CandidateResult> candidates = new ArrayList<>();

            @Override
            protected Void doInBackground() {
                try {
                    return doAnalyze();
                } catch (Exception ex) {
                    publish("⚠️ 分析出错：" + ex.getMessage());
                    return null;
                }
            }

            private Void doAnalyze() {
                List<int[]> allMoves = getAllLegalMoves(snapBoard, snapRedTurn);
                if (allMoves.isEmpty()) {
                    publish("当前局面无合法走法（一方已将死/困毙）");
                    return null;
                }

                // 先用静态评估快速排序，取前 MULTI_PV*2 个候选（最多10个，避免等待过长）
                int numCandidates = Math.min(MULTI_PV, allMoves.size());
                List<int[]> sorted = quickSort(snapBoard, snapRedTurn, allMoves);
                List<int[]> topCandidates = sorted.subList(0, Math.min(numCandidates * 2, sorted.size()));

                publish("正在分析 " + topCandidates.size() + " 个候选走法...");

                // 对每个候选走法用AI深搜评分（1.5秒/步），每步完成后立即 publish 进度
                for (int i = 0; i < topCandidates.size() && !isCancelled(); i++) {
                    int[] mv = topCandidates.get(i);
                    if (isCancelled()) break;

                    Board trialBoard = copyBoard(snapBoard);
                    trialBoard.move(mv[0], mv[1], mv[2], mv[3]);

                    AIEngine trialAI = new AIEngine();
                    trialAI.setTimeLimit(1500);
                    final int[] oppScore = {Evaluator.evaluate(trialBoard)};  // 默认静态评分兜底
                    final String[] oppPV = {""};
                    final AIEngine.MoveSource[] oppSrc = {AIEngine.MoveSource.AI_SEARCH};
                    trialAI.setStatsListener(s -> {
                        oppScore[0] = s.boardScore;
                        oppPV[0] = s.pvLine != null ? s.pvLine : "";
                        oppSrc[0] = s.source != null ? s.source : AIEngine.MoveSource.AI_SEARCH;
                    });

                    if (!trialBoard.hasLegalMoves(!snapRedTurn)) {
                        // 走此步后对方被将死/困毙 = 绝杀
                        int selfScore = snapRedTurn ? 99999 : -99999;
                        candidates.add(new CandidateResult(mv, selfScore, "⚡ 绝杀！",
                                AIEngine.MoveSource.AI_SEARCH, snapBoard));
                    } else {
                        try {
                            trialAI.getBestMove(trialBoard, !snapRedTurn);
                        } catch (Exception e) {
                            // 单步搜索失败，用静态评分继续
                        }
                        candidates.add(new CandidateResult(mv, oppScore[0], oppPV[0],
                                oppSrc[0], snapBoard));
                    }

                    // 每分析完一个候选，立即刷新已有结果（让用户看到进度）
                    if (!candidates.isEmpty() && !isCancelled()) {
                        publish(buildResultText(snapBoard, snapRedTurn, scoreNow, candidates, true));
                    }
                }

                if (candidates.isEmpty()) {
                    publish("分析完毕，无有效候选");
                    return null;
                }

                // 全部分析完毕，发布最终结果
                publish(buildResultText(snapBoard, snapRedTurn, scoreNow, candidates, false));
                return null;
            }

            /** 构建候选走法展示文本，inProgress=true 表示仍在分析中（标题有提示） */
            private String buildResultText(Board board, boolean isRed, int baseScore,
                                           List<CandidateResult> cands, boolean inProgress) {
                // 排序：红方走取score最大，黑方走取score最小
                List<CandidateResult> sorted = new ArrayList<>(cands);
                sorted.sort((a, b) -> isRed ? Integer.compare(b.score, a.score) : Integer.compare(a.score, b.score));

                String side = isRed ? "红方" : "黑方";
                StringBuilder sb = new StringBuilder();
                sb.append("▶ 当前局面：").append(side).append("走棋\n");
                sb.append("  局面评分：").append(scoreDesc(baseScore)).append("\n");
                if (inProgress) {
                    sb.append("  ⏳ 分析中（已完成 ").append(cands.size()).append(" 个）...\n");
                } else {
                    sb.append("  说明：【来源】为预测后续应对时所用引擎\n");
                }
                sb.append("────────────────────\n");
                sb.append(String.format("前 %d 个候选走法（🤖内置AI排序）：\n\n",
                        Math.min(MULTI_PV, sorted.size())));

                for (int i = 0; i < Math.min(MULTI_PV, sorted.size()); i++) {
                    CandidateResult cr = sorted.get(i);
                    int[] mv = cr.move;
                    Piece p = board.grid[mv[0]][mv[1]];
                    String moveStr = p != null
                        ? GameState.buildNotationStatic(mv[0], mv[1], mv[2], mv[3], board.grid, isRed)
                        : "?";
                    int scoreDelta = isRed ? (cr.score - baseScore) : (baseScore - cr.score);
                    String deltaStr = scoreDelta > 0 ? "▲+" + scoreDelta
                                   : (scoreDelta < 0 ? "▼" + scoreDelta : "—");

                    // 候选走法标题行
                    sb.append(String.format("%d. %s  %s  %s  【%s】\n",
                            i + 1, moveStr, scoreDesc(cr.score), deltaStr,
                            sourceTag(cr.oppSource)));

                    // 后续 PV 主线：格式 "   └ 候选走法 → 对手应对 → AI后续 → ..."
                    if (cr.pvLine != null && cr.pvLine.startsWith("⚡")) {
                        // 绝杀：直接显示
                        sb.append("   └ ").append(cr.pvLine).append("\n");
                    } else {
                        // 从 pvLine 提取中文走法列表（去掉 "▶ AI: " / "△ 对手: " 前缀）
                        // pvLine 每行格式：  "▶ AI: 炮二平五"  或  "△ 对手: 马8进7"
                        List<String> pvMoves = new ArrayList<>();
                        pvMoves.add(moveStr);  // 第一步：候选走法本身
                        if (cr.pvLine != null && !cr.pvLine.isEmpty()) {
                            for (String pvl : cr.pvLine.split("\n")) {
                                String cleaned = pvl.trim()
                                        .replace("▶ AI: ", "")
                                        .replace("△ 对手: ", "");
                                if (!cleaned.isEmpty()) pvMoves.add(cleaned);
                            }
                        }
                        if (pvMoves.size() > 1) {
                            sb.append("   └ ");
                            for (int k = 0; k < pvMoves.size(); k++) {
                                if (k > 0) sb.append(" → ");
                                sb.append(pvMoves.get(k));
                            }
                            sb.append("\n");
                        }
                    }

                    if (i < Math.min(MULTI_PV, sorted.size()) - 1)
                        sb.append("\n");
                }
                return sb.toString();
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    String last = chunks.get(chunks.size() - 1);
                    analysisArea.setText(last);
                    analysisArea.setCaretPosition(0);
                }
            }

            @Override
            protected void done() {
                analyzing = false;
                analyzeBtn.setEnabled(true);
                stopAnalyzeBtn.setEnabled(false);
                if (isCancelled()) {
                    statusLabel.setText("分析已停止");
                } else {
                    try {
                        get(); // 捕获 doInBackground 中未被 try-catch 处理的异常
                        statusLabel.setText("分析完成");
                    } catch (Exception e) {
                        statusLabel.setText("分析出错：" + e.getMessage());
                        analysisArea.setText("⚠️ 分析异常：" + e.getMessage()
                                + "\n请检查是否有棋子或棋盘状态异常。");
                    }
                }
            }
        };
        analyzeWorker.execute();
    }

    private void stopAnalyze() {
        if (analyzeWorker != null) analyzeWorker.cancel(true);
        analyzing = false;
        analyzeBtn.setEnabled(true);
        stopAnalyzeBtn.setEnabled(false);
        statusLabel.setText("分析已停止");
    }

    /** 快速静态排序：走此步后的局面评分（无需AI深搜） */
    private List<int[]> quickSort(Board b, boolean isRed, List<int[]> moves) {
        List<int[]> result = new ArrayList<>(moves);
        result.sort((a, b2) -> {
            Board ba = copyBoard(b); ba.move(a[0], a[1], a[2], a[3]);
            Board bb = copyBoard(b); bb.move(b2[0], b2[1], b2[2], b2[3]);
            int sa = chess.Evaluator.evaluate(ba);
            int sb2 = chess.Evaluator.evaluate(bb);
            return isRed ? Integer.compare(sb2, sa) : Integer.compare(sa, sb2);
        });
        return result;
    }

    /** 获取当前方所有合法走法 */
    private List<int[]> getAllLegalMoves(Board b, boolean isRed) {
        List<int[]> all = new ArrayList<>();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                if (b.grid[r][c] != null && b.grid[r][c].isRed == isRed)
                    all.addAll(b.getLegalMoves(r, c));
        return all;
    }

    /** 候选结果 */
    private static class CandidateResult {
        final int[] move;
        final int score;                            // 红方视角
        final String pvLine;                        // 后续PV（原始格式）
        final AIEngine.MoveSource oppSource;        // 对手应对的来源
        CandidateResult(int[] move, int score, String pvLine,
                        AIEngine.MoveSource oppSource, Board board) {
            this.move = move; this.score = score;
            this.pvLine = pvLine;
            this.oppSource = oppSource != null ? oppSource : AIEngine.MoveSource.AI_SEARCH;
        }
    }

    /** 将来源枚举转为图标标签 */
    private static String sourceTag(AIEngine.MoveSource src) {
        if (src == null) return "🤖";
        switch (src) {
            case CLOUD_BOOK: return "☁云库";
            case LOCAL_BOOK: return "📖开局库";
            default:         return "🤖内置AI";
        }
    }

    private static String scoreDesc(int score) {
        if (Math.abs(score) > 5000) return score > 0 ? "红方绝杀" : "黑方绝杀";
        if (score > 400)  return String.format("红方领先 %d 分", score);
        if (score < -400) return String.format("黑方领先 %d 分", -score);
        if (score > 100)  return String.format("红方稍优 %d 分", score);
        if (score < -100) return String.format("黑方稍优 %d 分", -score);
        return "均势（" + (score >= 0 ? "+" : "") + score + "）";
    }

    // ========================================================================
    // 绘制
    // ========================================================================
    private void drawBoard(Graphics2D g) {
        final int C = dynCell, M = dynMargin, OX = dynOffX, OY = dynOffY;
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(Math.max(1f, C / 44f)));
        for (int r = 0; r < 10; r++)
            g.drawLine(OX+M, OY+M+r*C, OX+M+8*C, OY+M+r*C);
        for (int c = 0; c < 9; c++) {
            if (c == 0 || c == 8) g.drawLine(OX+M+c*C, OY+M, OX+M+c*C, OY+M+9*C);
            else {
                g.drawLine(OX+M+c*C, OY+M,     OX+M+c*C, OY+M+4*C);
                g.drawLine(OX+M+c*C, OY+M+5*C, OX+M+c*C, OY+M+9*C);
            }
        }
        g.drawLine(OX+M+3*C, OY+M,     OX+M+5*C, OY+M+2*C);
        g.drawLine(OX+M+5*C, OY+M,     OX+M+3*C, OY+M+2*C);
        g.drawLine(OX+M+3*C, OY+M+7*C, OX+M+5*C, OY+M+9*C);
        g.drawLine(OX+M+5*C, OY+M+7*C, OX+M+3*C, OY+M+9*C);
        int fontSize = Math.max(10, (int)(C * 19.0 / CELL));
        g.setFont(new Font("宋体", Font.BOLD, fontSize));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", OX+M+C,   OY+M+4*C+(int)(C*0.43));
        g.drawString("汉  界", OX+M+5*C, OY+M+4*C+(int)(C*0.43));
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
        g.setColor(new Color(180, 230, 60, 160));
        g.fillOval(OX+M+lastFC*C-R, OY+M+lastFR*C-R, R*2, R*2);
        g.setColor(new Color(120, 220, 30, 200));
        g.fillOval(OX+M+lastTC*C-R, OY+M+lastTR*C-R, R*2, R*2);
        g.setColor(new Color(60, 200, 0, 230));
        g.setStroke(new BasicStroke(Math.max(2f, R/8f)));
        g.drawOval(OX+M+lastTC*C-R, OY+M+lastTR*C-R, R*2, R*2);
        g.setStroke(new BasicStroke(1f));
    }

    private void drawHighlight(Graphics2D g) {
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        if (selRow != -1) {
            int cx = OX+M+selCol*C, cy = OY+M+selRow*C;
            g.setColor(new Color(255, 240, 0, 180));
            g.fillOval(cx-R, cy-R, R*2, R*2);
            g.setColor(new Color(255, 200, 0, 230));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(cx-R-2, cy-R-2, R*2+4, R*2+4);
            g.setStroke(new BasicStroke(1f));
        }
        if (legalMoves != null) {
            for (int[] m : legalMoves) {
                int cx = OX+M+m[1]*C, cy = OY+M+m[0]*C;
                if (board.grid[m[0]][m[1]] != null) {
                    g.setColor(new Color(255, 80, 80, 100));
                    g.fillOval(cx-R, cy-R, R*2, R*2);
                    g.setColor(new Color(220, 50, 50, 200));
                    g.setStroke(new BasicStroke(Math.max(2.5f, R/8f)));
                    g.drawOval(cx-R, cy-R, R*2, R*2);
                    g.setStroke(new BasicStroke(Math.max(1.5f, R/12f)));
                    g.drawOval(cx-R+3, cy-R+3, R*2-6, R*2-6);
                    g.setStroke(new BasicStroke(1f));
                } else {
                    int dot = Math.max(6, R/3);
                    g.setColor(new Color(0, 200, 80, 200));
                    g.setStroke(new BasicStroke(Math.max(2f, R/11f)));
                    g.drawOval(cx-dot, cy-dot, dot*2, dot*2);
                    g.setColor(new Color(200, 255, 200, 160));
                    g.fillOval(cx-dot/2, cy-dot/2, dot, dot);
                    g.setStroke(new BasicStroke(1f));
                }
            }
        }
    }

    private void drawPieces(Graphics2D g) {
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        int fontSize = Math.max(10, (int)(R * 17.0 / PIECE_R));
        for (int r = 0; r < 10; r++) for (int c = 0; c < 9; c++) {
            Piece p = board.grid[r][c];
            if (p == null) continue;
            int cx = OX+M+c*C, cy = OY+M+r*C;
            Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
            Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
            Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);
            g.setColor(new Color(0, 0, 0, 40));
            g.fillOval(cx-R+2, cy-R+3, R*2, R*2);
            g.setColor(bg);
            g.fillOval(cx-R, cy-R, R*2, R*2);
            g.setColor(border);
            g.setStroke(new BasicStroke(Math.max(1.5f, R/12f)));
            g.drawOval(cx-R, cy-R, R*2, R*2);
            g.setStroke(new BasicStroke(1f));
            int inner = Math.max(2, R/8);
            g.drawOval(cx-R+inner, cy-R+inner, (R-inner)*2, (R-inner)*2);
            g.setColor(text);
            g.setFont(new Font("宋体", Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            String txt = p.getDisplay();
            g.drawString(txt, cx - fm.stringWidth(txt)/2, cy + fm.getAscent()/2 - 1);
        }
    }

    // ========================================================================
    // UI 更新
    // ========================================================================
    private void updateScoreDisplay() {
        int score = chess.Evaluator.evaluate(board);
        // 优势条：100=均势，>100=红优，<100=黑优
        int barVal = Math.max(0, Math.min(200, 100 + score / 20));
        scoreBar.setValue(barVal);
        if (Math.abs(score) < 50) {
            scoreLabel.setText("均势");
            scoreLabel.setForeground(new Color(0x555555));
        } else if (score > 0) {
            scoreLabel.setText("红优 +" + score);
            scoreLabel.setForeground(new Color(0xAA1111));
        } else {
            scoreLabel.setText("黑优 +" + (-score));
            scoreLabel.setForeground(new Color(0x111111));
        }
        boardPanel.repaint();
    }

    private void updateTurnLabel() {
        if (redTurn) {
            turnLabel.setText("⬤ 红方走棋");
            turnLabel.setForeground(new Color(0xB22222));
        } else {
            turnLabel.setText("⬤ 黑方走棋");
            turnLabel.setForeground(new Color(0x222222));
        }
    }

    private void updateNotationArea() {
        // 从步骤栈重建有序走法列表（时序从先到后）
        List<StepRecord> recs = new ArrayList<>(stepStack);
        Collections.reverse(recs);   // stepStack 是栈，head=最新，reverse 后 index0=第一步
        notationModel.setRowCount(0);
        if (recs.isEmpty()) return;

        // 构建中文走法列表（每步一条，与 ChessPanel.gs.notations 结构完全相同）
        // 若首步是黑方走，在列表头部插入一个空占位，使红方列始终对应偶数索引(0,2,4...)
        List<String> notaList = new ArrayList<>();
        if (!recs.get(0).wasRed) notaList.add("");   // 黑方先走：红方列留空
        for (StepRecord rec : recs) {
            notaList.add(GameState.buildNotationStatic(
                    rec.fr, rec.fc, rec.tr, rec.tc, rec.snapshot, rec.wasRed));
        }

        // 填表：与 ChessPanel.updateNotation() 逻辑完全一致
        // 每行4格：[序1, 红1, 黑1, 序2, 红2, 黑2]（两回合，每回合一红一黑）
        int size = notaList.size();
        for (int i = 0; i < size; i += 4) {
            int moveNo1 = i / 2 + 1;
            String red1 = i     < size ? notaList.get(i)   : "";
            String blk1 = i + 1 < size ? notaList.get(i+1) : "";
            Object seq2 = "", red2 = "", blk2 = "";
            if (i + 2 < size) {
                seq2 = i / 2 + 2;
                red2 = i + 2 < size ? notaList.get(i+2) : "";
                blk2 = i + 3 < size ? notaList.get(i+3) : "";
            }
            notationModel.addRow(new Object[]{moveNo1, red1, blk1, seq2, red2, blk2});
        }
        // 自动滚动到最新一行
        if (notationModel.getRowCount() > 0) {
            int last = notationModel.getRowCount() - 1;
            notationTable.scrollRectToVisible(notationTable.getCellRect(last, 0, true));
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================
    private Piece[][] copyGrid(Piece[][] src) {
        Piece[][] dst = new Piece[10][9];
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                dst[r][c] = src[r][c] != null ? src[r][c].copy() : null;
        return dst;
    }

    private void restoreGrid(Piece[][] src) {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                board.grid[r][c] = src[r][c] != null ? src[r][c].copy() : null;
    }

    private Board copyBoard(Board src) {
        Board dst = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                dst.grid[r][c] = src.grid[r][c] != null ? src.grid[r][c].copy() : null;
        return dst;
    }

    // ========================================================================
    // 工厂方法
    // ========================================================================
    public static void show(Component parent, Piece[][] grid, boolean redTurn) {
        ExploreDialog dlg = new ExploreDialog(parent, grid, redTurn);
        dlg.setVisible(true);
    }
}
