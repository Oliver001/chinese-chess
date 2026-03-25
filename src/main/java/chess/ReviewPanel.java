package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.List;

/**
 * 打谱面板：加载历史对局，逐步回放并展示优势折线图。
 * 可点击棋谱列表的任意一步跳转复盘。
 */
public class ReviewPanel extends JPanel {

    // ---- 尺寸（初始默认值）----
    private static final int CELL    = 60;
    private static final int MARGIN  = 40;
    private static final int PIECE_R = 24;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;
    private static final int SIDE_W  = 220;
    private static final int CHART_H = 130;

    // ---- 动态尺寸（随面板大小实时计算）----
    private int dynCell   = CELL;
    private int dynMargin = MARGIN;
    private int dynPieceR = PIECE_R;
    private int dynOffX   = 0;
    private int dynOffY   = 0;

    private GameState.GameRecord record;
    /** 当前显示到第几步（0=初始局面，1=第一步走完，…）*/
    private int currentStep = 0;
    /** 重建局面用的 board */
    private final Board board = new Board();

    // ---- UI ----
    private JPanel boardPanel;
    private JPanel rightPanel;         // 右侧信息面板（动态宽度）
    private JList<String> moveList;
    private DefaultListModel<String> moveListModel;
    private JLabel stepLabel;
    private JLabel resultLabel;
    private ScoreChart scoreChart;
    private JButton prevBtn, nextBtn, firstBtn, lastBtn;

    // ---- AI分析 ----
    private final AIEngine reviewAI = new AIEngine();
    private JTextArea analysisArea;   // 分析结果文本区
    private JButton analyzeBtn;       // 分析当前步按钮
    private boolean analyzing = false;

    public ReviewPanel(GameState.GameRecord record) {
        this.record = record;
        setLayout(new BorderLayout(4, 4));
        setBackground(new Color(0xEEE0B0));
        setBorder(new EmptyBorder(8,8,8,8));

        buildUI();
        gotoStep(0);
    }

    // =====================================================================
    // UI 构建
    // =====================================================================
    private void buildUI() {
        // 左：棋盘
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int pw = getWidth(), ph = getHeight();
                int cellW = (pw - MARGIN * 2) / 8;
                int cellH = (ph - MARGIN * 2) / 9;
                int cell = Math.max(25, Math.min(cellW, cellH));
                int margin = Math.max(16, (int)(cell * MARGIN / (double)CELL));
                int boardPixW = margin * 2 + cell * 8;
                int boardPixH = margin * 2 + cell * 9;
                int offX = Math.max(0, (pw - boardPixW) / 2);
                int offY = Math.max(0, (ph - boardPixH) / 2);
                int pieceR = Math.max(10, (int)(cell * PIECE_R / (double)CELL));
                dynCell   = cell;
                dynMargin = margin;
                dynPieceR = pieceR;
                dynOffX   = offX;
                dynOffY   = offY;
                Graphics2D g2 = (Graphics2D)g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawPieces(g2);
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));
        add(boardPanel, BorderLayout.CENTER);

        // 右：信息面板（宽度随窗口等比扩展）
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setPreferredSize(new Dimension(SIDE_W, BOARD_H));
        right.setMinimumSize(new Dimension(SIDE_W, 400));
        right.setOpaque(false);
        rightPanel = right;

        // 顶部：对局信息
        JPanel info = new JPanel(new GridLayout(3,1,2,2));
        info.setOpaque(false);
        resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(new Font("宋体", Font.BOLD, 13));
        resultLabel.setForeground(new Color(0x8B0000));
        stepLabel   = new JLabel("", SwingConstants.CENTER);
        stepLabel.setFont(new Font("宋体", Font.PLAIN, 12));
        JLabel dateLabel = new JLabel(record.date, SwingConstants.CENTER);
        dateLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        dateLabel.setForeground(new Color(0x666666));
        info.add(resultLabel);
        info.add(stepLabel);
        info.add(dateLabel);
        right.add(info, BorderLayout.NORTH);

        // 中：走法列表（可点击）
        moveListModel = new DefaultListModel<>();
        moveListModel.addElement("  初始局面");
        for (int i = 0; i < record.notations.size(); i++) {
            String nota = record.notations.get(i);
            String prefix = (i%2==0) ? String.format("%3d. ", i/2+1) : "     ";
            moveListModel.addElement(prefix + nota);
        }
        moveList = new JList<>(moveListModel);
        moveList.setFont(new Font("宋体", Font.PLAIN, 12));
        moveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        moveList.setBackground(new Color(0xFFFAF0));
        moveList.setFixedCellHeight(20);
        moveList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = moveList.getSelectedIndex();
                if (idx >= 0) gotoStep(idx);
            }
        });
        JScrollPane listScroll = new JScrollPane(moveList);
        listScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "棋谱",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));
        right.add(listScroll, BorderLayout.CENTER);

        // 底部：优势折线图 + 翻页按钮
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setOpaque(false);

        scoreChart = new ScoreChart();
        scoreChart.setPreferredSize(new Dimension(SIDE_W, CHART_H));
        scoreChart.setMinimumSize(new Dimension(100, CHART_H));
        scoreChart.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "优势曲线",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 11), new Color(0x8B4513)));
        scoreChart.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int step = scoreChart.hitTest(e.getX(), e.getY());
                if (step >= 0) gotoStep(step);
            }
        });
        bottom.add(scoreChart, BorderLayout.NORTH);

        // 翻页按钮
        JPanel nav = new JPanel(new GridLayout(1,4,4,0));
        nav.setOpaque(false);
        firstBtn = makeNavBtn("|◀", e -> gotoStep(0));
        prevBtn  = makeNavBtn("◀",  e -> gotoStep(Math.max(0, currentStep-1)));
        nextBtn  = makeNavBtn("▶",  e -> gotoStep(Math.min(record.moves.size(), currentStep+1)));
        lastBtn  = makeNavBtn("▶|", e -> gotoStep(record.moves.size()));
        nav.add(firstBtn); nav.add(prevBtn); nav.add(nextBtn); nav.add(lastBtn);

        // AI分析按钮
        analyzeBtn = new JButton("🤖 AI分析本步");
        analyzeBtn.setFont(new Font("宋体", Font.BOLD, 11));
        analyzeBtn.setForeground(new Color(0x006600));
        analyzeBtn.addActionListener(e -> analyzeCurrentStep());

        // AI分析结果区（可滚动）
        analysisArea = new JTextArea(4, 1);
        analysisArea.setFont(new Font("宋体", Font.PLAIN, 11));
        analysisArea.setEditable(false);
        analysisArea.setBackground(new Color(0xFFF8E1));
        analysisArea.setForeground(new Color(0x3C3C00));
        analysisArea.setLineWrap(false);
        analysisArea.setText("点击「AI分析本步」获取建议走法");
        JScrollPane analysisSp = new JScrollPane(analysisArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        analysisSp.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "AI分析建议",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 11), new Color(0x8B4513)));
        analysisSp.setPreferredSize(new Dimension(SIDE_W, 110));

        JPanel navBtnPanel = new JPanel(new BorderLayout(0, 3));
        navBtnPanel.setOpaque(false);
        navBtnPanel.add(nav,         BorderLayout.NORTH);
        navBtnPanel.add(analyzeBtn,  BorderLayout.CENTER);
        navBtnPanel.add(analysisSp,  BorderLayout.SOUTH);

        bottom.add(navBtnPanel, BorderLayout.SOUTH);

        right.add(bottom, BorderLayout.SOUTH);
        add(right, BorderLayout.EAST);

        // 监听整体面板尺寸变化，动态调整右侧栏宽度（约占总宽 26%，最小 220px）
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int totalW = getWidth();
                if (totalW <= 0) return;
                int newSideW = Math.max(SIDE_W, (int)(totalW * 0.26));
                Dimension cur = rightPanel.getPreferredSize();
                if (cur.width != newSideW) {
                    rightPanel.setPreferredSize(new Dimension(newSideW, cur.height));
                    revalidate();
                }
            }
        });
    }

    // =====================================================================
    // AI 分析当前步建议走法
    // =====================================================================
    private void analyzeCurrentStep() {
        if (analyzing) return;
        analyzing = true;
        analyzeBtn.setEnabled(false);
        analyzeBtn.setText("🔍 分析中...");
        analysisArea.setText("AI正在分析当前局面，请稍候...");

        // 当前局面 board 已由 gotoStep 构建好，判断当前轮走方
        boolean isRedTurn = (currentStep % 2 == 0); // 偶数步=红先，奇数步=黑先
        // 若对局记录了humanIsRed，则第0步红先
        // 简单按步数奇偶判断：初始红先
        boolean redTurn = (currentStep % 2 == 0);

        // 使用中等难度（约10秒）做快速分析
        reviewAI.setTimeLimit(10_000);

        // 复制当前board供后台线程使用（避免前台复盘操作干扰）
        Board snapBoard = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                snapBoard.grid[r][c] = board.grid[r][c] != null ? board.grid[r][c].copy() : null;

        final boolean finalRedTurn = redTurn;
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            int[] bestMove = null;
            AIEngine.SearchStats stats = null;

            @Override protected String doInBackground() {
                // 注册统计回调收集PV
                reviewAI.setStatsListener(s -> stats = s);
                bestMove = reviewAI.getBestMove(snapBoard, finalRedTurn);
                return null;
            }

            @Override protected void done() {
                analyzing = false;
                analyzeBtn.setEnabled(true);
                analyzeBtn.setText("🤖 AI分析本步");

                if (bestMove == null || bestMove[0] == -1) {
                    analysisArea.setText("当前局面无合法走法（一方已被将死/困毙）");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                String turnStr = finalRedTurn ? "红方" : "黑方";
                sb.append("▶ 当前局面：").append(turnStr).append("走棋\n");

                // 格式化最优走法
                if (stats != null) {
                    sb.append(String.format("▶ 最优着法 [深度%d]：", stats.depth));
                    // 从棋盘获取棋子名称
                    Piece p = snapBoard.grid[bestMove[0]][bestMove[1]];
                    if (p != null) sb.append(p.getDisplay());
                    sb.append(String.format("(%d,%d)→(%d,%d)\n", bestMove[0]+1,bestMove[1]+1,bestMove[2]+1,bestMove[3]+1));

                    // 分数解读
                    int sc = stats.boardScore;
                    if (Math.abs(sc) > 5000) {
                        sb.append(sc > 0 ? "▶ 局面：红方绝杀\n" : "▶ 局面：黑方绝杀\n");
                    } else if (sc > 300) sb.append(String.format("▶ 局面评分：红方领先 %d 分\n", sc));
                    else if (sc < -300) sb.append(String.format("▶ 局面评分：黑方领先 %d 分\n", -sc));
                    else sb.append(String.format("▶ 局面评分：接近均势（%+d）\n", sc));

                    // PV主线
                    if (stats.pvLine != null && !stats.pvLine.isEmpty()) {
                        sb.append("▶ 预测后续：\n");
                        String[] pvSteps = stats.pvLine.split("\n");
                        for (String step : pvSteps) {
                            sb.append("   ").append(step
                                    .replace("▶ AI: ", (finalRedTurn?"红 ":"黑 "))
                                    .replace("△ 对手: ", (!finalRedTurn?"红 ":"黑 ")))
                              .append("\n");
                        }
                    }
                    if (stats.mateIn != 0) {
                        int steps = Math.abs(stats.mateIn);
                        boolean aiWins = stats.mateIn > 0;
                        sb.append(String.format("▶ ⚡ %s步内绝杀！（%s方）\n",
                                steps, aiWins == finalRedTurn ? turnStr : (finalRedTurn?"黑方":"红方")));
                    }
                }
                analysisArea.setText(sb.toString().stripTrailing());
                analysisArea.setCaretPosition(0);
            }
        };
        worker.execute();
    }

    private JButton makeNavBtn(String text, ActionListener al) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Dialog", Font.BOLD, 14));
        btn.addActionListener(al);
        return btn;
    }

    // =====================================================================
    // 跳转到第 step 步
    // =====================================================================
    private void gotoStep(int step) {
        step = Math.max(0, Math.min(record.moves.size(), step));
        currentStep = step;

        // 重建局面
        board.initBoard();
        for (int i = 0; i < step; i++) {
            int[] mv = record.moves.get(i);
            board.move(mv[0], mv[1], mv[2], mv[3]);
        }

        // 更新列表选中
        final int finalStep = step;
        SwingUtilities.invokeLater(() -> {
            moveList.setSelectedIndex(finalStep);
            moveList.ensureIndexIsVisible(finalStep);
        });

        // 更新标签
        int total = record.moves.size();
        stepLabel.setText("第 " + step + " 步 / 共 " + total + " 步");
        resultLabel.setText("结果：" + record.result +
            "  " + (record.humanIsRed?"执红":"执黑") +
            "  难度:" + record.difficulty);

        // 更新按钮状态
        firstBtn.setEnabled(step > 0);
        prevBtn.setEnabled(step > 0);
        nextBtn.setEnabled(step < total);
        lastBtn.setEnabled(step < total);

        // 更新折线图高亮
        scoreChart.setCurrentStep(step);

        boardPanel.repaint();
    }

    // =====================================================================
    // 绘制棋盘
    // =====================================================================
    private void drawBoard(Graphics2D g) {
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(Math.max(1f, C / 44f)));
        for (int r=0;r<10;r++)
            g.drawLine(OX+M, OY+M+r*C, OX+M+8*C, OY+M+r*C);
        for (int c=0;c<9;c++) {
            if (c==0||c==8) g.drawLine(OX+M+c*C, OY+M, OX+M+c*C, OY+M+9*C);
            else {
                g.drawLine(OX+M+c*C, OY+M,        OX+M+c*C, OY+M+4*C);
                g.drawLine(OX+M+c*C, OY+M+5*C, OX+M+c*C, OY+M+9*C);
            }
        }
        g.drawLine(OX+M+3*C, OY+M,         OX+M+5*C, OY+M+2*C);
        g.drawLine(OX+M+5*C, OY+M,         OX+M+3*C, OY+M+2*C);
        g.drawLine(OX+M+3*C, OY+M+7*C,     OX+M+5*C, OY+M+9*C);
        g.drawLine(OX+M+5*C, OY+M+7*C,     OX+M+3*C, OY+M+9*C);
        int fontSize = Math.max(10, (int)(C * 16.0 / CELL));
        g.setFont(new Font("宋体", Font.BOLD, fontSize));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", OX+M+C,   OY+M+4*C+(int)(C*0.42));
        g.drawString("汉  界", OX+M+5*C, OY+M+4*C+(int)(C*0.42));
        // 高亮当前步的走法
        if (currentStep > 0) {
            int[] mv = record.moves.get(currentStep - 1);
            g.setColor(new Color(160, 210, 80, 100));
            g.fillOval(OX+M+mv[1]*C-R, OY+M+mv[0]*C-R, R*2, R*2);
            g.fillOval(OX+M+mv[3]*C-R, OY+M+mv[2]*C-R, R*2, R*2);
        }
    }

    private void drawPieces(Graphics2D g) {
        final int C = dynCell, M = dynMargin, R = dynPieceR, OX = dynOffX, OY = dynOffY;
        int fontSize = Math.max(10, (int)(R * 17.0 / PIECE_R));
        for (int r=0;r<10;r++) for (int c=0;c<9;c++) {
            Piece p = board.grid[r][c];
            if (p==null) continue;
            int cx=OX+M+c*C, cy=OY+M+r*C;
            Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
            Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
            Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);
            g.setColor(new Color(0,0,0,40));
            g.fillOval(cx-R+2, cy-R+3, R*2, R*2);
            g.setColor(bg);
            g.fillOval(cx-R,cy-R,R*2,R*2);
            g.setColor(border);
            g.setStroke(new BasicStroke(Math.max(1.5f, R/12f)));
            g.drawOval(cx-R,cy-R,R*2,R*2);
            g.setStroke(new BasicStroke(1f));
            int inner = Math.max(2, R/8);
            g.drawOval(cx-R+inner,cy-R+inner,(R-inner)*2,(R-inner)*2);
            g.setColor(text);
            g.setFont(new Font("宋体", Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            String txt = p.getDisplay();
            g.drawString(txt, cx-fm.stringWidth(txt)/2, cy+fm.getAscent()/2-1);
        }
    }

    // =====================================================================
    // 优势折线图
    // =====================================================================
    class ScoreChart extends JPanel {
        private int highlightStep = 0;

        void setCurrentStep(int s) { highlightStep = s; repaint(); }

        /**
         * 鼠标命中测试：返回点击对应的步数（0=初始），-1=未命中
         */
        int hitTest(int mx, int my) {
            if (record.scores.isEmpty()) return -1;
            int n = record.scores.size();
            int w = getWidth() - 30, h = getHeight() - 36;
            int ox = 15, oy = 18;
            if (n <= 1) return -1;
            for (int i = 0; i <= n; i++) {
                int x = ox + (int)(i * (double)w / n);
                if (Math.abs(mx - x) <= 8) return i;
            }
            return -1;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            List<Integer> sc = record.scores;
            int n = sc.size();
            int w = getWidth() - 30, h = getHeight() - 36;
            int ox = 15, oy = 18;

            // 背景
            g2.setColor(new Color(0xFFFAF0));
            g2.fillRect(ox, oy, w, h);
            g2.setColor(new Color(0xC8A060));
            g2.drawRect(ox, oy, w, h);

            if (n == 0) {
                g2.setColor(Color.GRAY);
                g2.setFont(new Font("宋体",Font.PLAIN,11));
                g2.drawString("暂无数据", ox+w/2-20, oy+h/2);
                return;
            }

            // 零线（均势）
            int zeroY = oy + h/2;
            g2.setColor(new Color(0xAAAAAA));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                         1f, new float[]{4,3}, 0));
            g2.drawLine(ox, zeroY, ox+w, zeroY);

            // 坐标转换（将分值映射到像素）
            // ±1000分 ≈ ±h/3；超过±2000饱和
            final int SCALE = 2000;

            // 绘制折线（渐变填充）
            if (n >= 2) {
                // 先画填充面积
                int[] xPts = new int[n+2], yPts = new int[n+2];
                for (int i = 0; i < n; i++) {
                    xPts[i+1] = ox + (int)((i+1) * (double)w / n);
                    int clipped = Math.max(-SCALE, Math.min(SCALE, sc.get(i)));
                    yPts[i+1] = zeroY - (int)(clipped * (h/2.0) / SCALE);
                }
                xPts[0] = ox; yPts[0] = zeroY;
                xPts[n+1] = ox+w; yPts[n+1] = zeroY;

                Polygon poly = new Polygon(xPts, yPts, n+2);
                GradientPaint gp = new GradientPaint(0, oy, new Color(0xCC2222,true),
                                                     0, zeroY, new Color(0xFF8888,true));
                // 红色区域（红优）
                g2.setClip(ox, oy, w, h/2);
                g2.setPaint(new Color(0xCC222233, true));
                g2.fillPolygon(poly);
                // 黑色区域（黑优）
                g2.setClip(ox, zeroY, w, h/2);
                g2.setPaint(new Color(0x22222233, true));
                g2.fillPolygon(poly);
                g2.setClip(null);

                // 折线
                g2.setStroke(new BasicStroke(2f));
                for (int i = 1; i < n; i++) {
                    int x1 = ox + (int)(i * (double)w / n);
                    int x2 = ox + (int)((i+1) * (double)w / n);
                    int c1 = Math.max(-SCALE, Math.min(SCALE, sc.get(i-1)));
                    int c2 = Math.max(-SCALE, Math.min(SCALE, sc.get(i)));
                    int y1 = zeroY - (int)(c1 * (h/2.0) / SCALE);
                    int y2 = zeroY - (int)(c2 * (h/2.0) / SCALE);
                    g2.setColor(sc.get(i) >= 0 ? new Color(0xAA1111) : new Color(0x111111));
                    g2.drawLine(x1,y1,x2,y2);
                }
            }

            // 当前步高亮（竖线 + 圆点）
            if (highlightStep > 0 && highlightStep <= n) {
                int hx = ox + (int)(highlightStep * (double)w / n);
                g2.setColor(new Color(0x3399FF));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(hx, oy, hx, oy+h);
                // 评分点
                int score = sc.get(highlightStep-1);
                int clipped = Math.max(-SCALE, Math.min(SCALE, score));
                int hy = zeroY - (int)(clipped * (h/2.0) / SCALE);
                g2.setColor(new Color(0x3399FF));
                g2.fillOval(hx-4,hy-4,8,8);
                // 评分文字
                g2.setFont(new Font("宋体",Font.BOLD,10));
                String scoreStr = (score > 0 ? "红+" : score < 0 ? "黑+" : "均") +
                                  (score != 0 ? Math.abs(score) : "势");
                g2.drawString(scoreStr, Math.min(hx+4, ox+w-30), Math.max(hy-4, oy+12));
            }

            // 步数轴标（每10步一个标记）
            g2.setFont(new Font("Dialog",Font.PLAIN,9));
            g2.setColor(new Color(0x888888));
            for (int i = 0; i <= n; i += Math.max(1, n/8)) {
                int x = ox + (int)(i * (double)w / n);
                g2.drawString(String.valueOf(i), x-4, oy+h+12);
            }
        }
    }

    // =====================================================================
    // 静态工厂：弹出打谱对话框（从历史列表选择）
    // =====================================================================
    public static void showReviewDialog(Component parent) {
        List<GameState.GameRecord> games = GameState.loadAllGames();
        if (games.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "暂无历史对局记录。\n每局结束后会自动保存。",
                "历史棋谱", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 选择对局
        DefaultListModel<GameState.GameRecord> model = new DefaultListModel<>();
        for (GameState.GameRecord r : games) model.addElement(r);
        JList<GameState.GameRecord> gameList = new JList<>(model);
        gameList.setFont(new Font("宋体", Font.PLAIN, 13));
        gameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gameList.setSelectedIndex(0);
        JScrollPane sp = new JScrollPane(gameList);
        sp.setPreferredSize(new Dimension(380, 200));

        int ok = JOptionPane.showConfirmDialog(parent, sp,
            "选择对局（共" + games.size() + "局）",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        GameState.GameRecord selected = gameList.getSelectedValue();
        if (selected == null) return;

        showReviewDialogWithRecord(parent, selected);
    }

    /**
     * 直接用指定 GameRecord 打开复盘窗口（无需再选择）
     */
    public static void showReviewDialogWithRecord(Component parent, GameState.GameRecord record) {
        JFrame frame = new JFrame("打谱 — " + record.date + "  " + record.result);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setResizable(true);
        ReviewPanel panel = new ReviewPanel(record);
        frame.add(panel);
        frame.pack();
        frame.setMinimumSize(new Dimension(700, 580));
        frame.setLocationRelativeTo(parent);
        frame.setVisible(true);
    }
}
